package com.kimbox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import com.kimbox.Ui.dp
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private lateinit var floating: Ui.FloatingEntry
    private val handler = Handler(Looper.getMainLooper())
    private var loadedUrl: String? = null

    // 网页外观：kimi web 把选择存在 localStorage「kimi-web.color-scheme」（light/dark/system，缺省 system）。
    // targetSdk 28 的 WebView 里 prefers-color-scheme 不可靠，「跟随系统」由原生判断明暗后写进
    // <html data-color-scheme>；网页再把实际底色报回来（console 消息），原生状态栏/面板跟着变色。
    private var webScheme = "system"
    private var palette: Ui.Palette = Ui.LIGHT
    // 钩子是否已在当前页面里跑起来（收到过回报才算）；没跑起来时的外观选择先存着，钩子就绪后补上
    private var themeHookReady = false
    private var pendingScheme: String? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // SAF 导出：用户从 picker 选完目标 URI 后，onActivityResult 把暂存的文件写过去
    private var pendingExportFile: File? = null

    companion object {
        private const val FILE_CHOOSER_REQ = 42
        private const val EXPORT_PICKER_REQ = 43
        private const val STORAGE_PERMISSION_REQ = 100
        private const val THEME_MSG = "__kimbox_theme:"

        /**
         * 注入网页的外观钩子（幂等，可反复执行以更新系统明暗）。
         * - 模式为 system（或从没选过）时，按原生给的系统明暗写 data-color-scheme；
         *   网页自己改模式时会先写 localStorage 再改属性，观察者据此重新落地，不会互相打架。
         * - 每次落地后把「模式|实际底色」经 console 报给原生。
         */
        private fun themeHookJs(sysDark: Boolean) = """
            (function(sysDark){
              var K='kimi-web.color-scheme', d=document.documentElement;
              window.__kimboxSysDark = sysDark;
              function mode(){ try { var v = localStorage.getItem(K); return (v==='light'||v==='dark') ? v : 'system'; } catch(e) { return 'system'; } }
              function apply(){
                var m = mode();
                var want = m === 'system' ? (window.__kimboxSysDark ? 'dark' : 'light') : m;
                if (d.dataset.colorScheme !== want) d.dataset.colorScheme = want;
                requestAnimationFrame(function(){
                  var bg = document.body ? getComputedStyle(document.body).backgroundColor : '';
                  console.log('$THEME_MSG' + m + '|' + want + '|' + bg);
                });
              }
              window.__kimboxSetScheme = function(m){ try { localStorage.setItem(K, m); } catch(e) {} apply(); };
              if (!window.__kimboxThemeHooked) {
                window.__kimboxThemeHooked = true;
                new MutationObserver(function(){ setTimeout(apply, 0); })
                  .observe(d, {attributes:true, attributeFilter:['data-color-scheme']});
              }
              apply();
            })($sysDark);
        """.trimIndent()
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

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                themeHookReady = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectThemeHook()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val m = msg.message()
                if (m.startsWith(THEME_MSG)) {
                    onThemeReport(m.removePrefix(THEME_MSG))
                    return true
                }
                return false
            }

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
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val bar = ProgressBar(this).apply { isIndeterminate = true }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
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
        floating = Ui.FloatingEntry(this, root,
            onTap = { showSettingsDialog() },
            onLongPress = { quickToggleTheme() })

        setContentView(root)
        // 网页报回实际底色之前，先按系统明暗上色，避免冷启动白闪/黑闪
        applyPalette(if (systemDark()) Ui.DARK else Ui.LIGHT, null)

        loadCachedRelease()
        refreshDot()
        checkForUpdates(manual = false)

        handler.post(poller)
    }

    // ── 外观 ────────────────────────────────────────────────

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun injectThemeHook() {
        if (::web.isInitialized) web.evaluateJavascript(themeHookJs(systemDark()), null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统切深色/浅色：manifest 声明了 uiMode，Activity 不重建，这里手动把新明暗告诉网页
        injectThemeHook()
        if (KimiState.url == null || overlay.visibility == View.VISIBLE) {
            applyPalette(if (systemDark()) Ui.DARK else Ui.LIGHT, null)
        }
    }

    /** 网页报回：「模式|实际明暗|body 底色」 */
    private fun onThemeReport(report: String) {
        val parts = report.split('|')
        themeHookReady = true
        pendingScheme?.let { pending ->
            // 页面没就绪时选的外观，钩子一就绪就补上；补上后会再回报一次，这次的回报直接丢掉
            pendingScheme = null
            setWebScheme(pending)
            return
        }
        webScheme = parts.getOrNull(0) ?: "system"
        val dark = parts.getOrNull(1) == "dark"
        applyPalette(if (dark) Ui.DARK else Ui.LIGHT, parseCssColor(parts.getOrNull(2)))
    }

    private fun parseCssColor(css: String?): Int? {
        val m = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)""").find(css ?: return null) ?: return null
        val alpha = m.groupValues[4].toFloatOrNull() ?: 1f
        if (alpha < 0.5f) return null // 透明底色说明 body 没设背景，退回调色板默认
        return Color.rgb(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /** 状态栏、导航栏、加载页、悬浮钮统一换色；barColor 为网页真实底色（拿不到就用调色板） */
    @Suppress("DEPRECATION")
    private fun applyPalette(p: Ui.Palette, barColor: Int?) {
        palette = p
        val bg = barColor ?: if (p.dark) Color.rgb(18, 18, 18) else Color.WHITE
        window.statusBarColor = bg
        window.navigationBarColor = bg
        var flags = window.decorView.systemUiVisibility
        flags = if (p.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        flags = if (p.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
        web.setBackgroundColor(bg)
        overlay.setBackgroundColor(bg)
        statusText.setTextColor(p.text)
        floating.applyPalette(p)
    }

    private fun setWebScheme(mode: String) {
        webScheme = mode
        if (themeHookReady && ::web.isInitialized) {
            web.evaluateJavascript("window.__kimboxSetScheme && window.__kimboxSetScheme('$mode')", null)
        } else {
            pendingScheme = mode
        }
    }

    /** 长按悬浮钮：在亮/暗之间一键切换（基于当前实际明暗取反，之后不再跟随系统） */
    private fun quickToggleTheme() {
        if (KimiState.url == null) {
            Toast.makeText(this, "引擎还没就绪，稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val next = if (palette.dark) "light" else "dark"
        setWebScheme(next)
        Toast.makeText(this, if (next == "dark") "已切到暗色" else "已切到亮色", Toast.LENGTH_SHORT).show()
    }

    /** 引擎版本直接读运行时里的 package.json（agent 用 npm 自升级后也能显示真实版本） */
    private fun engineVersion(): String? = try {
        val f = File(filesDir, "usr/lib/node_modules/@moonshot-ai/kimi-code/package.json")
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(f.readText())?.groupValues?.get(1)
    } catch (_: Exception) { null }

    private fun isStoragePermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+：READ/WRITE 拿不到 /sdcard 原始路径，唯一通道是所有文件访问（special appop）
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun showSettingsDialog() {
        val p = palette
        val engine = engineVersion()
        val sheet = Ui.Sheet(this, p, "口袋Kimi",
            "App $appVersion" + (if (engine != null) " · 引擎 $engine" else "") + " · 长按悬浮钮可快速切换亮/暗")

        sheet.sectionLabel("外观", topMarginDp = 4)
        val modes = listOf("system", "light", "dark")
        sheet.add(Ui.segmented(this, p, listOf("跟随系统", "亮色", "暗色"),
            modes.indexOf(webScheme).coerceAtLeast(0)) { i ->
            if (KimiState.url == null) {
                Toast.makeText(this, "引擎还没就绪，稍后再试", Toast.LENGTH_SHORT).show()
            } else {
                setWebScheme(modes[i])
                // 面板配色要跟着变，换色后重开一次面板最省事
                handler.postDelayed({ if (sheet.dialog.isShowing) { sheet.dismiss(); showSettingsDialog() } }, 250)
            }
        })

        sheet.sectionLabel("常用")
        val update = latestRelease
        val updateSub = when {
            update == null -> "从 GitHub 获取最新版本"
            updateAvailable -> "有新版本 ${update.version}"
            isNewerVersion(appVersion, update.version) -> "本机 $appVersion 比线上 ${update.version} 新"
            else -> "已是最新"
        }
        sheet.add(Ui.row(this, p, "↻", "检查更新", updateSub,
            trailing = if (updateAvailable) "NEW" else "›",
            trailingColor = if (updateAvailable) p.danger else null,
            dot = updateUnseen) { sheet.dismiss(); showUpdateDialog() })
        sheet.add(Ui.row(this, p, "⟳", "刷新页面", "页面卡住、一直转圈或断连时用") {
            sheet.dismiss(); refreshWebPage()
        })
        sheet.add(Ui.row(this, p, "⇩", "导出文件", "把 Kimi 放进 ~/exports 的文件存到手机里") {
            sheet.dismiss(); showExportDialog()
        })

        sheet.sectionLabel("权限")
        val granted = isStoragePermissionGranted()
        sheet.add(Ui.row(this, p, "▣", "手机存储",
            if (granted) "已允许 Kimi 读写下载、相册等公共目录" else "允许后 Kimi 才能读写手机里的文件",
            trailing = if (granted) "已开启" else "去开启",
            trailingColor = if (granted) p.sub else p.text) {
            sheet.dismiss(); showStoragePermissionWarning()
        })
        sheet.show()
    }

    /**
     * 手动刷新页面：WS 断连/页面卡「请求中」无响应时的用户自救入口（issue #22）。
     * 不能用 web.reload()——引擎重启后端口会变，reload 只会重复请求旧端口；
     * 始终加载 KimiState.url 里的最新地址。同步复位 loadedUrl 与遮罩，
     * 与 poller 的加载分支行为保持一致。
     */
    private fun refreshWebPage() {
        val url = KimiState.url
        if (url == null) {
            Toast.makeText(this, "引擎未就绪，暂无页面可刷新", Toast.LENGTH_SHORT).show()
            return
        }
        loadedUrl = url
        web.loadUrl(url)
        overlay.visibility = View.GONE
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
        floating.setDot(updateUnseen)
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
            p.getString("latestTags", "")?.split("|")?.filter { it.isNotEmpty() }?.distinct() ?: emptyList())
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
                    .map { it.groupValues[1] }.distinct().toList()
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
        val p = palette
        val sheet = Ui.Sheet(this, p,
            if (hasUpdate) "发现新版本 ${info.version}" else "已是最新版本",
            if (hasUpdate) "当前 $appVersion → 最新 ${info.version}"
            else "当前 $appVersion · 线上最新 ${info.version}")

        if (info.tags.isNotEmpty()) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (t in info.tags) {
                chips.addView(Ui.chip(this, p, t), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
            sheet.add(chips, topMarginDp = 2)
        }
        val notes = info.notes.trim()
        if (notes.isNotEmpty()) {
            sheet.add(TextView(this).apply {
                text = MarkdownLite.render(notes, p) { dp(it) }
                textSize = 14.5f
                setTextColor(p.text)
                setLineSpacing(dp(3).toFloat(), 1f)
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = Ui.rounded(if (p.dark) p.surface else Color.rgb(250, 250, 250), dp(14).toFloat(),
                    p.divider, dp(1))
            }, topMarginDp = 6)
            sheet.maxBodyHeight(0.55f)
        }

        val close = Ui.button(this, p, "关闭", primary = false) { sheet.dismiss() }
        val action = if (hasUpdate) {
            markUpdateSeen()
            Ui.button(this, p, "前往下载", primary = true) {
                sheet.dismiss()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "打不开浏览器，请手动访问：\n" + info.url, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // 没更新时也给个手动刷新入口，防止缓存太旧
            Ui.button(this, p, "重新检查", primary = true) { sheet.dismiss(); checkForUpdates(manual = true) }
        }
        sheet.addFooter(Ui.buttonRow(this, close, action))
        sheet.show()
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
        val p = palette
        val sheet = Ui.Sheet(this, p, "导出文件", "选一个文件，再选保存位置")
        for (f in files.sortedByDescending { it.lastModified() }) {
            sheet.add(Ui.row(this, p, "▤", f.name, formatSize(f.length())) {
                sheet.dismiss(); launchExportPicker(f)
            })
        }
        sheet.maxBodyHeight(0.6f)
        sheet.show()
    }

    private fun formatSize(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
        else -> "%.1f MB".format(n / 1024.0 / 1024.0)
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
