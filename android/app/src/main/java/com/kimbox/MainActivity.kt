package com.kimbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var loadedUrl: String? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val FILE_CHOOSER_REQ = 42
    }

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

        handler.post(poller)
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
