package com.kimbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.widget.Toast
import java.io.File
import java.io.IOException

/**
 * 接管 `<input type=file>`：先问用户「拍照 / 选择文件」，拍照走系统相机 ACTION_IMAGE_CAPTURE，
 * 照片经 [CameraCaptureProvider] 的受限 content:// URI 交给相机写满整张 JPEG，再交给 WebView 上传。
 *
 * 宿主（MainActivity / RemoteControlActivity）要做的四件事：
 * ```
 * onShowFileChooser: fileChooser?.release(); fileChooser = CameraFileChooser(this, palette, cb)
 *                    .also { it.show(params) }
 * onActivityResult:  if (fileChooser?.onActivityResult(code, resultCode, data) == true) { fileChooser = null; return }
 * onSaveInstanceState / onCreate: saveState(outState) / discardRestored(this, savedInstanceState)
 * onDestroy:         fileChooser?.release(); fileChooser = null
 * ```
 *
 * 不申请 CAMERA 权限（action 交给系统相机，省掉运行时授权），也不引 androidx。
 */
class CameraFileChooser(
    private val activity: Activity,
    private val palette: Ui.Palette,
    private var callback: ValueCallback<Array<Uri>>?,
) {

    companion object {
        /** 两个请求码要避开宿主自己的 42 / 43 / 100 */
        const val REQ_CAMERA = 19280
        const val REQ_FILES = 19281
        private const val SS_PENDING = "com.kimbox.camera.pending"

        /** accept 里常见扩展名到 MIME 的映射，只用于把选择器的类型限制带过去 */
        private val EXT_MIME = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif",
            "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv",
            "json" to "application/json", "mp4" to "video/mp4", "mov" to "video/quicktime",
        )

        /**
         * Activity 重建（进程被系统杀掉、相机还在前台）时，上次没交付的临时照片没人认领，
         * 在 onCreate 里顺手删掉；只删专用目录里符合条件的文件，别的路径一律不碰。
         */
        @JvmStatic
        fun discardRestored(ctx: Context, state: Bundle?) {
            val path = state?.getString(SS_PENDING) ?: return
            state.remove(SS_PENDING)
            try {
                val f = File(path)
                if (CameraCaptureProvider.isManaged(ctx, f)) f.delete()
            } catch (_: Exception) {
                // 删不掉就留给定期清理
            }
        }
    }

    private var pendingFile: File? = null
    private var pendingUri: Uri? = null
    private var sheet: Ui.Sheet? = null
    private var done = false

    /** 面板取消或启动失败也会结束选择，不一定产生 onActivityResult。 */
    val isPending: Boolean get() = !done

    // 由 FileChooserParams 解析出来的约束
    private var mimes: List<String> = emptyList()
    private var anyType = false
    private var multiple = false
    private var allowCamera = false
    private var captureOnly = false

    /** 入口：弹出「拍照 / 选择文件」或在 capture 属性下直接开相机 */
    fun show(params: FileChooserParams) {
        if (done) return
        parse(params)
        try {
            CameraCaptureProvider.purge(activity)
        } catch (_: Exception) {
            // 清理失败不影响选择
        }
        when {
            captureOnly -> startCamera()
            allowCamera -> showChoiceSheet()
            else -> startFilePicker()
        }
    }

    /** 返回 true 表示这个 requestCode 归本类管 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQ_CAMERA -> onCameraResult(resultCode)
            REQ_FILES -> if (resultCode == Activity.RESULT_OK) {
                deliver(collectUris(data))
            } else {
                finish(null)
            }
            else -> return false
        }
        return true
    }

    /** 用户/宿主主动取消：释放 callback，删掉还没交付的临时照片 */
    fun cancel() {
        dismissSheet()
        discardPending()
        finish(null)
    }

    /** Activity 销毁时调用：关掉面板、清掉未交付的照片，并结束这次 file chooser */
    fun release() {
        dismissSheet()
        discardPending()
        finish(null)
    }

    fun saveState(outState: Bundle) {
        val pending = pendingFile
        if (pending != null) outState.putString(SS_PENDING, pending.absolutePath)
        else outState.remove(SS_PENDING)
    }

    // ── 意图构造 ────────────────────────────────────────────

    private fun parse(params: FileChooserParams) {
        val accepts = params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.substringBefore(';').trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        // 先把 accept 归一到 MIME：.jpg/.jpeg 之类别名才有机会被后面的相机判断认出
        anyType = accepts.isEmpty() || accepts.contains("*/*")
        mimes = mutableListOf<String>().apply {
            for (a in accepts) {
                when {
                    a.contains('/') -> add(a)
                    a.startsWith(".") && a.length > 1 -> EXT_MIME[a.substring(1)]?.let { add(it) }
                }
            }
        }.distinct()
        multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        // 相机只会产出 JPEG：accept 不限类型，或归一后含 image/*、image/jpeg 时才让「拍照」出现
        allowCamera = anyType || mimes.any { it == "image/*" || it == "image/jpeg" }
        captureOnly = params.isCaptureEnabled && allowCamera
    }

    private fun showChoiceSheet() {
        val imageOnly = !anyType && mimes.isNotEmpty() &&
            mimes.all { it == "image/*" || it.startsWith("image/") }
        val s = Ui.Sheet(
            activity, palette,
            if (imageOnly) "发送图片" else "添加文件",
            "拍照会直接上传整张照片，也可以从手机上选文件",
        )
        if (hasCameraApp()) {
            s.add(Ui.row(
                a = activity, p = palette, icon = "◉", title = "拍照",
                subtitle = "用系统相机拍一张", trailing = null,
                onClick = { dismissSheet(); startCamera() },
            ))
        }
        s.add(Ui.row(
            a = activity, p = palette, icon = "▤", title = "选择文件",
            subtitle = if (multiple) "可多选" else null, trailing = null,
            onClick = { dismissSheet(); startFilePicker() },
        ), topMarginDp = 6)
        s.addFooter(Ui.buttonRow(activity,
            Ui.button(activity, palette, "取消", primary = false,
                onClick = { dismissSheet(); finish(null) })))
        // 返回键 / 点面板外 = 取消，必须把 callback 结束掉，否则网页的输入框一直转圈
        s.dialog.setOnCancelListener { finish(null) }
        s.dialog.setOnDismissListener { if (sheet === s) sheet = null }
        sheet = s
        try {
            s.show()
        } catch (_: Exception) {
            // Activity 已经不可见时弹不出来（BadToken 等），直接按取消处理
            dismissSheet()
            finish(null)
        }
    }

    private fun startCamera() {
        val file = try {
            CameraCaptureProvider.newPhotoFile(activity)
        } catch (e: IOException) {
            toast("无法创建临时照片：${e.message}")
            finish(null)
            return
        }
        val uri = CameraCaptureProvider.uriFor(activity, file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // EXTRA_OUTPUT 里的 URI 不算 intent 的 data，必须靠 ClipData 才能拿到一次性写授权
            clipData = ClipData.newRawUri("photo", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingFile = file
        pendingUri = uri
        try {
            activity.startActivityForResult(intent, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            discardPending()
            toast("没有可用的相机应用，改为选择文件")
            startFilePicker()
        } catch (e: Exception) {
            discardPending()
            toast("无法打开相机：${e.message}")
            finish(null)
        }
    }

    private fun startFilePicker() {
        if (!openPicker(Intent.ACTION_OPEN_DOCUMENT) && !openPicker(Intent.ACTION_GET_CONTENT)) {
            toast("没有文件选择器")
            finish(null)
        }
    }

    private fun openPicker(action: String): Boolean {
        // anyType 时用 */*；其余情况带上归一后的 MIME 限制
        val types: List<String> = if (anyType) emptyList() else mimes
        val intent = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (types.size == 1) types[0] else "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return try {
            activity.startActivityForResult(intent, REQ_FILES)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    // ── 结果处理 ────────────────────────────────────────────

    private fun onCameraResult(resultCode: Int) {
        val file = pendingFile
        val uri = pendingUri
        pendingFile = null
        pendingUri = null
        // 相机已经结束，撤掉它的写/读授权；自家读不看授权，WebView 后面照常能读到
        if (uri != null) CameraCaptureProvider.revokeGrants(activity, uri)
        if (resultCode != Activity.RESULT_OK) {
            file?.delete()
            finish(null)
            return
        }
        if (file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            toast("拍照没有成功：相机没有写入照片")
            finish(null)
            return
        }
        // 临时照片保留到 WebView 读完为止，回收交给 CameraCaptureProvider.purge
        finish(arrayOf(CameraCaptureProvider.uriFor(activity, file)))
    }

    private fun collectUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val out = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { out.add(it) }
        } else {
            data.data?.let { out.add(it) }
        }
        if (!multiple && out.size > 1) return listOf(out[0])
        return out
    }

    private fun deliver(uris: List<Uri>) {
        if (uris.isEmpty()) finish(null) else finish(uris.toTypedArray())
    }

    /** 只会生效一次；之后任何迟到的结果都不会二次回调 WebView */
    private fun finish(result: Array<Uri>?) {
        if (done) return
        done = true
        val cb = callback
        callback = null
        if (cb != null) {
            try {
                cb.onReceiveValue(result)
            } catch (_: Exception) {
                // WebView 已经丢掉这次选择（页面跳走等），忽略
            }
        }
    }

    private fun discardPending() {
        val file = pendingFile
        val uri = pendingUri
        pendingFile = null
        pendingUri = null
        if (uri != null) CameraCaptureProvider.revokeGrants(activity, uri)
        file?.delete()
    }

    private fun dismissSheet() {
        val s = sheet ?: return
        sheet = null
        try {
            s.dismiss()
        } catch (_: Exception) {
            // 已经关掉了
        }
    }

    private fun hasCameraApp(): Boolean = try {
        activity.packageManager.resolveActivity(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) != null
    } catch (_: Exception) {
        false
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
