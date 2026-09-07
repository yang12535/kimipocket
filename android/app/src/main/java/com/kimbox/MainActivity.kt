package com.kimbox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var loadedUrl: String? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // SAF 导出：用户从 picker 选完目标 URI 后，onActivityResult 把暂存的文件写过去
    private var pendingExportFile: File? = null

    companion object {
        private const val FILE_CHOOSER_REQ = 42
        private const val EXPORT_PICKER_REQ = 43
        private const val MENU_SETTINGS = 1
        private const val STORAGE_PERMISSION_REQ = 100
    }

    /** agent 把要导出的文件放这里，用户走 设置菜单 → 导出文件（SAF）保存到公共位置 */
    private fun exportsDir(): File = File(filesDir, "home/exports")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            startForegroundService(Intent(this, KimiService::class.java))
        } catch (t: Throwable) {
            KimiState.lastError = t.message
        }

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 页面只来自本机引擎，不需要 file:// / content:// 访问
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // 只有「当前引擎端口」的 loopback 地址留在站内；
                // 其他 loopback 端口（如同机恶意 App 开的仿冒页面）与外部链接一律交系统浏览器
                val enginePort = KimiState.port
                val isEngine = enginePort != null &&
                    (u.host == "127.0.0.1" || u.host == "localhost") && u.port == enginePort
                return if (isEngine) {
                    false
                } else {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    true
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                wv: WebView,
                cb: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = cb
                return try {
                    val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        val accepts = params.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                        if (accepts.size == 1) type = accepts[0]
                    }
                    startActivityForResult(Intent.createChooser(i, "选择文件"), FILE_CHOOSER_REQ)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val bar = ProgressBar(this).apply { isIndeterminate = true }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(24, 24, 28))
            addView(bar)
            addView(statusText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 40; leftMargin = 60; rightMargin = 60 })
        }

        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        loadCachedRelease()
        refreshDot()
        checkForUpdates(manual = false)

        handler.post(poller)
    }

    // 右上角设置入口：二级菜单放「检查更新」「导出文件」等低频操作，不再占用主界面
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 有新版且未点看过时齿轮带红点
        val dot = if (updateUnseen) " 🔴" else ""
        menu.add(0, MENU_SETTINGS, 0, "⚙️ 设置$dot")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) {
            showSettingsDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun isStoragePermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+：READ/WRITE 拿不到 /sdcard 原始路径，唯一通道是所有文件访问（special appop）
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun showSettingsDialog() {
        val dot = if (updateUnseen) " 🔴" else ""
        val storageStatus = if (isStoragePermissionGranted()) "存储权限（已开启）" else "存储权限"
        val items = arrayOf("检查更新$dot", "导出文件", storageStatus)
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showUpdateDialog()
                    1 -> showExportDialog()
                    2 -> showStoragePermissionWarning()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 存储权限：先弹醒目风险警告，确认后再走系统授权流程。
     * Android 11+（API 30+）：跳系统「所有文件访问权限」开关页（MANAGE_EXTERNAL_STORAGE，special appop）。
     * Android 10 及以下：READ/WRITE 运行时权限弹窗；已永久拒绝时直接跳系统应用详情页。
     */
    private fun showStoragePermissionWarning() {
        if (isStoragePermissionGranted()) {
            Toast.makeText(this, "存储权限已开启", Toast.LENGTH_SHORT).show()
            return
        }
        val warning = android.text.SpannableStringBuilder(
            "授予此权限后，Kimi（AI agent）可以读写手机公共存储中的全部文件" +
            "（照片、下载、文档等）。\n\n" +
            "⚠️ 风险说明：\n" +
            "• 误操作或恶意指令可能损坏或泄露你的数据\n" +
            "• 建议仅在需要时开启，用完可到系统设置关闭\n" +
            "• Kimi 将能直接访问 /sdcard/ 下的所有公共目录"
        ).apply {
            setSpan(android.text.style.ForegroundColorSpan(Color.RED), 0, length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        AlertDialog.Builder(this)
            .setTitle("⚠️ 存储权限 — 风险警告")
            .setMessage(warning)
            .setPositiveButton("我已了解，继续授权") { _, _ -> requestStoragePermission() }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 存储权限请求状态机（仅 Android 10 及以下；API 30+ 走 special appop 跳设置页，不经过这里）。
     * 三态，由 SharedPreferences "storageRequested" + shouldShowRequestPermissionRationale 联合判定：
     *
     * ┌─────────────────────────────────────────────────────────────────────────────────────┐
     * │ 状态          │ storageRequested │ shouldShowRationale │ 行为                       │
     * ├─────────────────────────────────────────────────────────────────────────────────────┤
     * │ A. 首次未问过 │ false             │ false（系统首次）    │ 调 requestPermissions，    │
     * │               │                   │                      │ 并置标记 true              │
     * │ B. 问过被拒    │ true              │ true                 │ 再次 requestPermissions   │
     * │ （可重试）     │                   │ （用户未勾选不再询问）│ （系统仍弹窗）             │
     * │ C. 永久拒绝    │ true              │ false                │ 跳系统应用详情页，让用户  │
     * │ （不再询问）    │                   │                      │ 手动开启                  │
     * └─────────────────────────────────────────────────────────────────────────────────────┘
     *
     * 关键：storageRequested 标记只从 false → true，被拒时绝不清回 false。
     * 否则永久拒绝后会死循环：每次点击都重新走 requestPermissions（系统静默拒绝、无弹窗），
     * 永远到不了跳系统设置的分支。
     */
    private fun requestStoragePermission() {
        if (isStoragePermissionGranted()) return
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+：special appop，系统不弹运行时对话框，
            // 只能把用户带到「所有文件访问权限」开关页手动开；无「永久拒绝」概念，每次点击都跳。
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
                Toast.makeText(this, "请在打开的页面中允许「所有文件访问权限」，然后返回", Toast.LENGTH_LONG).show()
            } catch (e: ActivityNotFoundException) {
                // 个别 ROM 没有直达页，退到总列表页
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    Toast.makeText(this, "请在列表中找到口袋Kimi并允许「所有文件访问权限」", Toast.LENGTH_LONG).show()
                } catch (e2: ActivityNotFoundException) {
                    Toast.makeText(this, "无法打开授权页，请手动到系统设置 → 特殊权限设置 → 所有文件访问权限", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val prefs = getPreferences(MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("storageRequested", false)
        val canShowDialog = shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (!askedBefore && !canShowDialog) {
            // 状态 A：首次未问过 — shouldShowRequestPermissionRationale 首次固定返回 false，
            // 必须调一次 requestPermissions 让系统弹窗；同时落盘标记，后续不再走此分支
            prefs.edit().putBoolean("storageRequested", true).apply()
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQ
            )
            return
        }
        if (askedBefore && !canShowDialog) {
            // 状态 C：永久拒绝 — 用户勾选了「不再询问」或系统不再弹窗，
            // 再 requestPermissions 只会被静默拒绝，直接跳系统应用详情页
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
                Toast.makeText(this, "请在应用权限中开启「存储」或「文件和媒体」权限", Toast.LENGTH_LONG).show()
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "无法打开应用设置页，请手动到系统设置 → 应用 → 口袋Kimi → 权限", Toast.LENGTH_LONG).show()
            }
            return
        }
        // 状态 B：问过被拒但可重试 — shouldShowRequestPermissionRationale=true，
        // 系统仍会弹授权弹窗，直接 requestPermissions
        requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE),
            STORAGE_PERMISSION_REQ
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == STORAGE_PERMISSION_REQ) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "已授权，Kimi 现在可以访问公共目录", Toast.LENGTH_LONG).show()
            } else if (grantResults.isEmpty()) {
                // 空数组 = 用户取消/交互被打断（Android 官方契约）。
                // 系统尚未记下拒绝、rationale 仍为 false，恢复 storageRequested 标记
                // 允许下次正常弹授权窗而不是直接跳系统设置页。
                getPreferences(MODE_PRIVATE).edit().putBoolean("storageRequested", false).apply()
                Toast.makeText(this, "授权已取消，可稍后在设置中重新开启", Toast.LENGTH_SHORT).show()
            } else {
                // 真正的拒绝（数组非空且全 denied）：保留 storageRequested 标记。
                // 标记只记录「是否问过」，用于区分首次与永久拒绝。
                // 清回 false 会导致永久拒绝后死循环（每次点击都重走 requestPermissions 而非跳系统设置）
                Toast.makeText(this, "权限被拒绝，可稍后在设置中重新开启", Toast.LENGTH_LONG).show()
            }
            invalidateOptionsMenu()
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    // ── 检查更新（GitHub Releases）────────────────────────────
    // release 简介约定：开头可带 [标签] 分类（如 [apk底层更新-只能从更新升级]），
    // 这里解析出来原样展示；红点缓存进 SharedPreferences，下次启动立即显示
    private data class ReleaseInfo(val version: String, val url: String, val notes: String, val tags: List<String>)

    private var latestRelease: ReleaseInfo? = null
    private var updateAvailable = false
    private var updateUnseen = false
    private var checkingUpdate = false

    // 不用 BuildConfig（会多出 Java 编译任务踩 AGP 的 JDK 坑），PackageManager 直取
    @Suppress("DEPRECATION")
    private val appVersion: String
        get() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"

    /** 有新版且用户还没点开看过：已看过的版本不再亮红点，直到出现更新的 release */
    private fun refreshDot() {
        updateUnseen = updateAvailable &&
            latestRelease?.version != getPreferences(MODE_PRIVATE).getString("seenVersion", null)
        invalidateOptionsMenu()
    }

    private fun markUpdateSeen() {
        val v = latestRelease?.version ?: return
        getPreferences(MODE_PRIVATE).edit().putString("seenVersion", v).apply()
        refreshDot()
    }

    private fun loadCachedRelease() {
        val p = getPreferences(MODE_PRIVATE)
        val v = p.getString("latestVersion", null) ?: return
        latestRelease = ReleaseInfo(
            v,
            p.getString("latestUrl", "") ?: "",
            p.getString("latestNotes", "") ?: "",
            p.getString("latestTags", "")?.split("|")?.filter { it.isNotEmpty() } ?: emptyList())
        updateAvailable = isNewerVersion(v, appVersion)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (manual) Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        Thread {
            var info: ReleaseInfo? = null
            var error: String? = null
            try {
                val conn = URL("https://api.github.com/repos/yang12535/kimipocket/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "kimipocket/" + appVersion)
                if (conn.responseCode != 200) throw Exception("HTTP " + conn.responseCode)
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val body = json.optString("body", "")
                val tags = Regex("\\[([^\\]]+)\\]").findAll(body.take(300))
                    .map { it.groupValues[1] }.toList()
                info = ReleaseInfo(
                    json.getString("tag_name").removePrefix("v"),
                    json.getString("html_url"),
                    body, tags)
            } catch (t: Throwable) {
                error = t.message
            }
            val manualFlag = manual
            runOnUiThread {
                checkingUpdate = false
                if (info != null) {
                    latestRelease = info
                    updateAvailable = isNewerVersion(info.version, appVersion)
                    getPreferences(MODE_PRIVATE).edit()
                        .putString("latestVersion", info.version)
                        .putString("latestUrl", info.url)
                        .putString("latestNotes", info.notes)
                        .putString("latestTags", info.tags.joinToString("|"))
                        .apply()
                    refreshDot()
                    if (manualFlag) showUpdateDialog()
                } else if (manualFlag) {
                    Toast.makeText(this, "检查更新失败：$error", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun showUpdateDialog() {
        val info = latestRelease
        if (info == null) {
            // 还没有任何检查结果：现场拉一次，拉回来自动再弹
            checkForUpdates(manual = true)
            return
        }
        val hasUpdate = isNewerVersion(info.version, appVersion)
        val sb = StringBuilder()
        sb.append("当前版本：").append(appVersion).append('\n')
        sb.append("最新版本：").append(info.version)
        if (hasUpdate) sb.append("  🔴 有更新")
        sb.append('\n')
        if (info.tags.isNotEmpty()) {
            sb.append('\n')
            for (t in info.tags) sb.append('[').append(t).append(']')
            sb.append('\n')
        }
        // 简介太长只给前 800 字，全文走「前往下载」
        val notes = info.notes.trim()
        if (notes.isNotEmpty()) {
            sb.append('\n').append(if (notes.length > 800) notes.take(800) + "…" else notes)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (hasUpdate) "发现新版本" else "已是最新")
            .setMessage(sb.toString())
            .setNegativeButton("关闭", null)
        if (hasUpdate) {
            markUpdateSeen()
            dlg.setPositiveButton("前往下载") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "打不开浏览器，请手动访问：\n" + info.url, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // 没更新时也给个手动刷新入口，防止缓存太旧
            dlg.setPositiveButton("重新检查") { _, _ -> checkForUpdates(manual = true) }
        }
        dlg.show()
    }

    private val poller = object : Runnable {
        override fun run() {
            val url = KimiState.url
            if (url != loadedUrl) {
                if (url == null) {
                    // 引擎重启后新 URL 往往与旧的逐字符相同（同端口同 token）：
                    // 不清掉 loadedUrl 的话既不重载页面也不撤遮罩，界面永久卡在加载页
                    loadedUrl = null
                    overlay.visibility = View.VISIBLE
                } else {
                    loadedUrl = url
                    web.loadUrl(url)
                    overlay.visibility = View.GONE
                }
            }
            val err = KimiState.lastError
            statusText.text = KimiState.status + if (err != null) "\n\n$err" else ""
            handler.postDelayed(this, 500)
        }
    }

    private fun showExportDialog() {
        val dir = exportsDir()
        val files = if (dir.isDirectory) dir.listFiles()?.filter { it.isFile } ?: emptyList() else emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "没有可导出的文件\n让 Kimi 先把文件放到 ~/exports/ 目录", Toast.LENGTH_LONG).show()
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要导出的文件")
            .setItems(names) { _, which -> launchExportPicker(files[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchExportPicker(file: File) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            pendingExportFile = file
            startActivityForResult(intent, EXPORT_PICKER_REQ)
        } catch (e: ActivityNotFoundException) {
            pendingExportFile = null
            Toast.makeText(this, "系统没有可用的文件保存器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeExportToUri(file: File, uri: Uri) {
        Thread {
            try {
                val out = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("无法打开目标文件（openOutputStream 返回 null）")
                out.use { file.inputStream().use { src -> src.copyTo(it) } }
                handler.post {
                    Toast.makeText(this, "已导出：${file.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                pendingExportFile = null
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQ) {
            val cb = fileChooserCallback
            fileChooserCallback = null
            if (cb == null) return
            if (resultCode == RESULT_OK && data != null) {
                val uris = mutableListOf<Uri>()
                val cd = data.clipData
                if (cd != null) {
                    for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
                } else {
                    data.data?.let { uris.add(it) }
                }
                cb.onReceiveValue(uris.toTypedArray())
            } else {
                cb.onReceiveValue(null)
            }
        } else if (requestCode == EXPORT_PICKER_REQ) {
            val file = pendingExportFile
            pendingExportFile = null
            if (resultCode == RESULT_OK && data?.data != null && file != null) {
                // 持久化 URI 权限以备异步写入——只请求 picker 实际授予的权限（data.flags 掩码），
                // 固定传 READ|WRITE 会在只授予一种权限的 picker 上抛 SecurityException
                try {
                    val persistFlags = data!!.flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if (persistFlags != 0) {
                        contentResolver.takePersistableUriPermission(data.data!!, persistFlags)
                    }
                } catch (_: Exception) { /* 部分 picker 不支持持久化，不影响当次写入 */ }
                writeExportToUri(file, data.data!!)
            }
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        if (isFinishing && ::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
