package com.kimbox

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.kimbox.Ui.dp
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 临时交互终端：加载本地 assets 里的 xterm 页面，通过 JS bridge 对接 TerminalSession。
 * 只用于 kimi login / 手动命令，关闭页面即结束会话，不写历史。
 */
class TerminalActivity : Activity() {

    companion object {
        private const val PAGE_URL = "file:///android_asset/terminal/index.html"
        private const val MIN_SIZE = 2
        private const val MAX_COLS = 500
        private const val MAX_ROWS = 300
        private const val MAX_COPY_CHARS = 1024 * 1024
        private const val MAX_PASTE_CHARS = 256 * 1024
        private const val MAX_URL_CHARS = 8 * 1024
        // 页面真正解析完一块输出后才回 ACK；未 ACK 的块最多 4 个
        private const val MAX_OUTPUT_IN_FLIGHT = 4
        // 只放行页面实际用到的文件，别的资产/路径一律不给
        private val LOCAL_ASSETS = setOf(
            "index.html", "app.css", "app.js", "xterm.css", "xterm.js", "addon-fit.js"
        )
        private val BG = Color.rgb(17, 17, 20)
        private val BAR = Color.rgb(24, 24, 28)
        private val TEXT = Color.rgb(244, 244, 245)
        private val SUB = Color.rgb(161, 161, 170)
    }

    private lateinit var web: WebView
    @Volatile private var session: TerminalSession? = null
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    private var pageReady = false
    private var closing = false
    private var exitDialogShowing = false

    // JS bridge -> WebView 的投递有界：页面真正解析完并 ACK 后才归还额度，反压到 PTY
    private val outputCredits = Semaphore(MAX_OUTPUT_IN_FLIGHT)
    private val pendingAcks = ConcurrentHashMap.newKeySet<Int>()
    private val nextOutputSeq = AtomicInteger(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(BG))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applyDarkChrome()

        web = WebView(this)
        configureWeb()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        web.loadUrl(PAGE_URL)
    }

    @Suppress("DEPRECATION")
    private fun applyDarkChrome() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        var flags = window.decorView.systemUiVisibility
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.decorView.systemUiVisibility = flags
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(16), 0, dp(8), 0)
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "临时终端"
            textSize = 16f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = "关闭本页会结束正在运行的命令"
            textSize = 11.5f
            setTextColor(SUB)
        })
        val close = TextView(this).apply {
            text = "关闭"
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { requestClose() }
        }
        bar.addView(titles, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        return bar
    }

    @Suppress("DEPRECATION")
    private fun configureWeb() {
        web.setBackgroundColor(BG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            // 终端字体由 xterm 自己量，别叠加系统字号缩放
            textZoom = 100
            // 页面只允许本地 assets；即使 CSP 被绕过，WebView 也发不出网络请求
            blockNetworkLoads = true
            blockNetworkImage = true
        }
        web.addJavascriptInterface(Bridge(), "KimboxTerm")
        web.webChromeClient = object : WebChromeClient() {
            // 不把页面 console 写进 logcat，避免终端内容意外落盘
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isLocalAsset(request.url)) return false
                openExternal(request.url)
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? =
                if (isLocalAsset(request.url)) null else emptyResponse()

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && !pageReady) {
                    Toast.makeText(this@TerminalActivity, "终端页面加载失败", Toast.LENGTH_LONG).show()
                    finish()
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                closeSession()
                if (!isFinishing) finish()
                return true
            }
        }
    }

    /**
     * file:///android_asset 的 URL 在 WebView 里有两种解析结果：
     * host 为空、path=/android_asset/terminal/...；或 host=android_asset、path=/terminal/...
     * 两种都只放行 terminal 目录下明确列出的文件，避免目录内其他资产或路径穿越被加载。
     */
    private fun isLocalAsset(uri: Uri): Boolean {
        if (!"file".equals(uri.scheme, true)) return false
        val host = uri.host
        val path = uri.path ?: return false
        val relative = when {
            host.isNullOrEmpty() && path.startsWith("/android_asset/terminal/") ->
                path.substring("/android_asset".length)
            host == "android_asset" && path.startsWith("/terminal/") -> path
            else -> return false
        }
        if (!relative.startsWith("/terminal/")) return false
        return relative.substring("/terminal/".length) in LOCAL_ASSETS
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun maybeStartSession() {
        if (!pageReady || session != null || closing) return
        val s = TerminalSession(applicationContext, cols, rows, sessionListener)
        session = s
        s.start()
        if (session === s) {
            web.requestFocus()
            web.evaluateJavascript("window.__kimboxTerm&&window.__kimboxTerm.focus()", null)
        }
    }

    private val sessionListener = object : TerminalSession.Listener {
        override fun onOutput(data: ByteArray) {
            if (closing) return
            var acquired = false
            try {
                // ACK 前不投递下一块：页面慢时由 outputCredits 反压到 Session 队列和 PTY
                while (!closing) {
                    if (outputCredits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                        acquired = true
                        break
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!acquired) return
            if (closing) {
                outputCredits.release()
                return
            }

            val seq = nextOutputSeq.getAndIncrement()
            pendingAcks.add(seq)
            runOnUiThread {
                if (closing || isFinishing || isDestroyed || !pageReady) {
                    releaseOutput(seq)
                    return@runOnUiThread
                }
                try {
                    val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
                    web.evaluateJavascript(
                        "window.__kimboxTerm&&window.__kimboxTerm.output('$b64',$seq)",
                        null
                    )
                } catch (_: Throwable) {
                    releaseOutput(seq)
                }
            }
        }

        override fun onExit(exitCode: Int) {
            runOnUiThread {
                val s = session
                session = null
                s?.close()
                if (!closing && !isFinishing && !isDestroyed) {
                    web.evaluateJavascript(
                        "window.__kimboxTerm&&window.__kimboxTerm.exit($exitCode)", null
                    )
                }
            }
        }

        override fun onStartFailed(message: String) {
            runOnUiThread {
                session = null
                Toast.makeText(this@TerminalActivity, message, Toast.LENGTH_LONG).show()
                if (!closing && pageReady) {
                    web.evaluateJavascript(
                        "window.__kimboxTerm&&window.__kimboxTerm.failed(" +
                            JSONObject.quote(message) + ")", null
                    )
                }
            }
        }
    }

    /** JS 里的 xterm 真正解析完这块数据后才会调用 ack；重复 ACK 不会多还额度 */
    private fun releaseOutput(seq: Int) {
        if (pendingAcks.remove(seq)) outputCredits.release()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun ready() {
            runOnUiThread {
                if (closing) return@runOnUiThread
                pageReady = true
                maybeStartSession()
            }
        }

        @JavascriptInterface
        fun ack(seq: Int) {
            releaseOutput(seq)
        }

        @JavascriptInterface
        fun write(data: String): Boolean = session?.write(data) ?: false

        @JavascriptInterface
        fun openUrl(url: String) {
            if (url.isEmpty() || url.length > MAX_URL_CHARS) return
            val uri = try { Uri.parse(url) } catch (_: Throwable) { return }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return
            runOnUiThread {
                if (!closing && !isFinishing && !isDestroyed) {
                    openExternal(uri)
                    web.requestFocus()
                }
            }
        }

        @JavascriptInterface
        fun resize(newCols: Int, newRows: Int) {
            cols = newCols.coerceIn(MIN_SIZE, MAX_COLS)
            rows = newRows.coerceIn(MIN_SIZE, MAX_ROWS)
            session?.resize(cols, rows)
        }

        @JavascriptInterface
        fun copy(text: String): Boolean {
            if (text.isEmpty() || text.length > MAX_COPY_CHARS) return false
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return false
                cm.setPrimaryClip(ClipData.newPlainText("终端文本", text))
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun paste(): String {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return ""
                val clip = cm.primaryClip ?: return ""
                if (clip.itemCount == 0) return ""
                val text = clip.getItemAt(0)
                    .coerceToText(this@TerminalActivity)?.toString().orEmpty()
                if (text.length > MAX_PASTE_CHARS) text.substring(0, MAX_PASTE_CHARS) else text
            } catch (_: Throwable) {
                ""
            }
        }
    }

    private fun requestClose() {
        if (exitDialogShowing) return
        if (session == null || closing) {
            finish()
            return
        }
        exitDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("结束终端？")
            .setMessage("正在运行的命令会被终止，会话内容不会保存。")
            .setPositiveButton("结束") { _, _ ->
                exitDialogShowing = false
                finish()
            }
            .setNegativeButton("继续使用") { _, _ -> exitDialogShowing = false }
            .setOnCancelListener { exitDialogShowing = false }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (session != null && !closing) requestClose() else finish()
    }

    private fun closeSession() {
        val s = session
        session = null
        s?.close()
    }

    override fun onDestroy() {
        closing = true
        closeSession()
        if (::web.isInitialized) {
            try { web.removeJavascriptInterface("KimboxTerm") } catch (_: Throwable) {}
            try { web.destroy() } catch (_: Throwable) {}
        }
        super.onDestroy()
    }
}
