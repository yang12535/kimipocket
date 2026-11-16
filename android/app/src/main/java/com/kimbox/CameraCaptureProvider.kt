package com.kimbox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拍照临时文件的唯一出口：只认 `cacheDir/camera-capture/` 里由 [newPhotoFile] 生成的照片，
 * 对外形如 `content://<包名>.camera.capture/capture/IMG_20261115_101500_123.jpg`。
 * 相机 App 凭 Intent 里的一次性写授权把整张 JPEG 写进来，WebView 再用同一个 URI 读回上传。
 *
 * 边界：
 * - manifest 里注册为 exported=false + grantUriPermissions=true，没被显式授权的 App 打开就直接失败；
 * - 本类再校验 scheme/authority/路径段/文件名/规范化父目录，`..`、软链接、其他目录一律拒绝；
 * - 照片放 cache 子目录，系统清缓存会回收；[purge] 只删超过 [MAX_AGE_MS] 的旧照片，
 *   打开选择器和进程冷启动时各清一次，不会在 WebView 消费之前把它删掉。
 */
class CameraCaptureProvider : ContentProvider() {

    companion object {
        /** 与 AndroidManifest.xml 里的 `android:authorities="${applicationId}.camera.capture"` 对应 */
        private const val AUTHORITY_SUFFIX = ".camera.capture"
        private const val DIR = "capture"
        private const val DIR_NAME = "camera-capture"
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private val NAME_RE = Regex("""^IMG_\d{8}_\d{6}_\d{3}\.jpg$""")

        private fun dir(ctx: Context): File = File(ctx.cacheDir, DIR_NAME).apply { mkdirs() }

        fun authority(ctx: Context): String = ctx.packageName + AUTHORITY_SUFFIX

        /** 专用目录里随机（按毫秒递增）取一个还没被占用的文件名，并先建好空文件 */
        fun newPhotoFile(ctx: Context): File {
            val dir = dir(ctx)
            if (!dir.isDirectory) throw IOException("无法创建拍照缓存目录")
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            var t = System.currentTimeMillis()
            repeat(1000) {
                val f = File(dir, "IMG_" + fmt.format(Date(t)) + ".jpg")
                if (!f.exists() && f.createNewFile()) return f
                t += 1
            }
            throw IOException("拍照缓存目录不可写")
        }

        fun uriFor(ctx: Context, file: File): Uri {
            require(isManaged(ctx, file)) { "不是拍照临时文件：${file.absolutePath}" }
            return Uri.Builder()
                .scheme("content")
                .authority(authority(ctx))
                .appendPath(DIR)
                .appendPath(file.name)
                .build()
        }

        /** file 是否是本 provider 管辖的那一个（文件名与规范化父目录都要对得上） */
        fun isManaged(ctx: Context, file: File): Boolean = try {
            NAME_RE.matches(file.name) && file.canonicalFile.parentFile == dir(ctx).canonicalFile
        } catch (_: Exception) {
            false
        }

        /** 回收旧临时照片；正在上传的照片是刚写的，远没到 [maxAgeMs] */
        fun purge(ctx: Context, maxAgeMs: Long = MAX_AGE_MS) {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            dir(ctx).listFiles()?.forEach { f ->
                if (f.isFile && NAME_RE.matches(f.name) && f.lastModified() < cutoff) f.delete()
            }
        }

        /**
         * 撤掉交给相机 App 的那次性授权。自家进程按 owner 身份读 URI，不受影响，
         * 所以 WebView 后续照常能读到照片；这里只清掉外部 App 的残留授权。
         */
        fun revokeGrants(ctx: Context, uri: Uri) {
            try {
                ctx.revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // 没有授权可撤时也会抛，忽略
            }
        }

        /** 严格把外部 URI 映射回专用目录里的文件；任何一处对不上都当作不认识的 URI */
        private fun fileFor(ctx: Context, uri: Uri): File? {
            if (uri.scheme != "content" || uri.authority != authority(ctx)) return null
            val segs = uri.pathSegments
            if (segs.size != 2 || segs[0] != DIR) return null
            if (!NAME_RE.matches(segs[1])) return null
            val f = File(dir(ctx), segs[1])
            // NAME_RE 已经挡掉 ../，这里再比一次规范化父目录，防目录本身被换成软链接
            return if (f.canonicalFile.parentFile == dir(ctx).canonicalFile) f else null
        }

        private fun modeToFlags(mode: String): Int = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("不支持的打开模式：$mode")
        }
    }

    override fun onCreate(): Boolean {
        // 进程冷启动顺手捞一次：上次进程被杀、没来得及删的旧照片在这里回收
        val ctx = context
        if (ctx != null) {
            try {
                purge(ctx)
            } catch (_: Exception) {
                // 清理失败不影响正常使用
            }
        }
        return true
    }

    override fun getType(uri: Uri): String? {
        val ctx = context ?: return null
        return if (fileFor(ctx, uri) != null) "image/jpeg" else null
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val file = fileFor(ctx, uri) ?: return null
        // 相机 App 常问名字和大小；其他列一律给 null，不泄露任何路径信息
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()
        for (c in cols) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(if (file.isFile) file.length() else 0L)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: throw FileNotFoundException("没有 context")
        val file = fileFor(ctx, uri) ?: throw FileNotFoundException("未授权的拍照 URI：$uri")
        val flags = modeToFlags(mode)
        if (mode == "r" && !file.isFile) throw FileNotFoundException("照片不存在：${file.name}")
        return ParcelFileDescriptor.open(file, flags)
    }

    // 这个 provider 只负责「相机写入、WebView 读出」，不提供增删改
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("拍照临时文件不支持 insert")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException("拍照临时文件不支持 update")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("拍照临时文件不支持 delete")
}
