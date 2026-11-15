package com.kimbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.kimbox.Ui.dp

class RemoteControlActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var entryUrl: String
    private lateinit var colors: Ui.Palette
    private var pendingAuthorizationUrl: String? = null
    private var authorizationSheet: Ui.Sheet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28 && !profileInitialized) {
            WebView.setDataDirectorySuffix("remote-control")
            profileInitialized = true
        }
        entryUrl = intent.getStringExtra("url").orEmpty()
        if (!validEntry(entryUrl)) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT < 28) {
            openBrowser(entryUrl)
            finish()
            return
        }
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        colors = if (intent.getBooleanExtra("dark", systemDark)) Ui.DARK else Ui.LIGHT
        applyPalette(colors)
        pendingAuthorizationUrl = savedInstanceState?.getString("pending_authorization_url")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.bg)
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(colors.bg)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun barButton(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(colors.text)
            contentDescription = desc
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    if (colors.dark) android.graphics.Color.argb(40, 255, 255, 255) else android.graphics.Color.argb(24, 0, 0, 0)),
                null, Ui.rounded(android.graphics.Color.WHITE, dp(20).toFloat()))
            isClickable = true
            setOnClickListener { onClick() }
        }
        bar.addView(barButton("‹", "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val address = TextView(this).apply {
            text = "🔒 " + Uri.parse(entryUrl).host
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            isSingleLine = true
            setTextColor(colors.sub)
            background = Ui.rounded(colors.surface, dp(16).toFloat())
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        bar.addView(address, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        bar.addView(barButton("↻", "刷新") { web.reload() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bar)
        root.addView(View(this).apply { setBackgroundColor(colors.divider) }, LinearLayout.LayoutParams(-1, 1))
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (validEntry(uri.toString())) return false
                if (request.isForMainFrame && uri.scheme == "https" && uri.userInfo == null &&
                    uri.port in setOf(-1, 443)
                ) {
                    showAuthorizationHandoff(uri.toString(), launch = true)
                    return true
                }
                if (request.isForMainFrame && request.hasGesture() && uri.scheme in listOf("http", "mailto")) {
                    openBrowser(uri.toString())
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                address.text = "🔒 " + Uri.parse(url).host.orEmpty()
                CookieManager.getInstance().flush()
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(entryUrl)
        pendingAuthorizationUrl?.let { showAuthorizationHandoff(it, launch = false) }
    }

    private fun showAuthorizationHandoff(url: String, launch: Boolean) {
        pendingAuthorizationUrl = url
        authorizationSheet?.dismiss()
        val sheet = Ui.Sheet(this, colors, "在外部浏览器中授权",
            "OAuth 已离开内置页面。请在外部浏览器完成授权，然后返回这里刷新远程页面。")
        sheet.add(TextView(this).apply {
            text = url
            textSize = 12.5f
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(colors.text)
            setTextIsSelectable(true)
            background = Ui.rounded(colors.surface, dp(12).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }, topMarginDp = 12)
        sheet.addFooter(Ui.buttonRow(this,
            Ui.button(this, colors, "复制 URL", primary = false) { copyLink(url) },
            Ui.button(this, colors, "打开授权页", primary = true) { openBrowser(url) }
        ))
        sheet.addFooter(Ui.button(this, colors, "我已完成授权，刷新页面", primary = false) {
            pendingAuthorizationUrl = null
            authorizationSheet = null
            sheet.dismiss()
            web.loadUrl(entryUrl)
        })
        authorizationSheet = sheet
        sheet.dialog.setOnDismissListener {
            if (authorizationSheet === sheet) authorizationSheet = null
        }
        sheet.show()
        if (launch) openBrowser(url)
    }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("没有可用的浏览器，请复制 URL 手动打开")
        } catch (_: SecurityException) {
            toast("无法打开浏览器，请复制 URL 手动打开")
        }
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OAuth 授权链接", url))
        toast("授权 URL 已复制")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_authorization_url", pendingAuthorizationUrl)
        if (::web.isInitialized) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (::web.isInitialized) CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        authorizationSheet?.dismiss()
        authorizationSheet = null
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun applyPalette(p: Ui.Palette) {
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        var flags = window.decorView.systemUiVisibility
        flags = if (p.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        flags = if (p.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
    }

    companion object {
        private var profileInitialized = false

        fun validEntry(url: String): Boolean {
            val uri = Uri.parse(url)
            return uri.scheme == "https" && uri.host in setOf("code-rc.kimi.com", "code-rc.kimi.ai") &&
                uri.userInfo == null && uri.port in setOf(-1, 443)
        }
    }
}
