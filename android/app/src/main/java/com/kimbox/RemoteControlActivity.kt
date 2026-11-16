package com.kimbox

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.kimbox.Ui.dp
import org.json.JSONObject

class RemoteControlActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var entryUrl: String
    private lateinit var colors: Ui.Palette
    private var fileChooser: CameraFileChooser? = null
    private var pendingAuthorizationUrl: String? = null
    private var authorizationSheet: Ui.Sheet? = null
    private var authorizationStatus: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var remoteSessionActive = false
    private var authorizationStartedAt = 0L
    private var authorizationGeneration = 0
    private val authorizationPoll = Runnable { checkAuthorization() }
    private val idleClose = Runnable {
        if (!resumed && remoteSessionActive && fileChooser?.isPending != true && pendingAuthorizationUrl == null && !isFinishing && !isDestroyed) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CameraFileChooser.discardRestored(this, savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28 && !profileInitialized) {
            WebView.setDataDirectorySuffix("remote-control")
            profileInitialized = true
        }
        entryUrl = intent.getStringExtra("url").orEmpty()
        remoteSessionActive = Uri.parse(entryUrl).getQueryParameter("rc") == "1"
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
        authorizationStartedAt = savedInstanceState?.getLong("authorization_started_at") ?: 0L

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
        bar.addView(barButton("↻", "刷新") {
            clearAuthorization()
            web.reload()
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
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
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                wv: WebView,
                cb: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                if (!validEntry(wv.url.orEmpty())) {
                    cb.onReceiveValue(null)
                    return true
                }
                fileChooser?.release()
                val chooser = CameraFileChooser(this@RemoteControlActivity, colors, cb)
                fileChooser = chooser
                try {
                    chooser.show(params)
                } catch (_: Exception) {
                    chooser.release()
                    fileChooser = null
                }
                return true
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val text = msg.message()
                if (!text.startsWith(RC_NOTIFICATION)) return false
                forwardRemoteNotification(text.removePrefix(RC_NOTIFICATION))
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (validEntry(uri.toString())) return false
                if (request.isForMainFrame && uri.scheme == "https" && uri.userInfo == null &&
                    uri.port in setOf(-1, 443)
                ) {
                    if (isAuthorizationUrl(uri)) showAuthorizationHandoff(uri.toString(), launch = true)
                    else openBrowser(uri.toString())
                    return true
                }
                if (request.isForMainFrame && request.hasGesture() && uri.scheme in listOf("http", "mailto")) {
                    openBrowser(uri.toString())
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                address.text = "🔒 " + Uri.parse(url).host.orEmpty()
                remoteSessionActive = remoteSessionActive || Uri.parse(url).getQueryParameter("rc") == "1"
                CookieManager.getInstance().flush()
                installNotificationHook()
                maybeRenameOwnDevice()
                scheduleAuthorizationCheck(0)
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(entryUrl)
        pendingAuthorizationUrl?.let { showAuthorizationHandoff(it, launch = false) }
    }

    private fun showAuthorizationHandoff(url: String, launch: Boolean) {
        if (pendingAuthorizationUrl != url || authorizationStartedAt == 0L) {
            authorizationStartedAt = System.currentTimeMillis()
        }
        authorizationGeneration++
        pendingAuthorizationUrl = url
        authorizationSheet?.dismiss()
        val sheet = Ui.Sheet(this, colors, "在浏览器中完成登录",
            "请在外部浏览器完成授权；返回后会自动检查登录状态。")
        authorizationStatus = TextView(this).apply {
            text = "等待授权完成…"
            textSize = 13f
            setTextColor(colors.sub)
        }
        sheet.add(authorizationStatus!!, topMarginDp = 10)
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
        sheet.addFooter(Ui.button(this, colors, "检查授权状态", primary = false) {
            authorizationStatus?.text = "正在检查登录状态…"
            scheduleAuthorizationCheck(0)
        })
        authorizationSheet = sheet
        sheet.dialog.setOnDismissListener {
            if (authorizationSheet === sheet) {
                authorizationSheet = null
                authorizationStatus = null
            }
        }
        sheet.show()
        scheduleAuthorizationCheck(0)
        if (launch) openBrowser(url)
    }

    private fun scheduleAuthorizationCheck(delay: Long = 1500L) {
        handler.removeCallbacks(authorizationPoll)
        if (resumed && pendingAuthorizationUrl != null && !isFinishing && !isDestroyed) {
            handler.postDelayed(authorizationPoll, delay)
        }
    }

    private fun clearAuthorization() {
        handler.removeCallbacks(authorizationPoll)
        authorizationGeneration++
        pendingAuthorizationUrl = null
        authorizationStartedAt = 0L
        val sheet = authorizationSheet
        authorizationSheet = null
        authorizationStatus = null
        sheet?.dismiss()
    }

    private fun checkAuthorization() {
        if (!resumed || pendingAuthorizationUrl == null || !::web.isInitialized) return
        if (System.currentTimeMillis() - authorizationStartedAt > 30 * 60 * 1000L) {
            clearAuthorization()
            toast("授权等待已结束，请在设备页重新登录")
            return
        }
        val current = web.url.orEmpty()
        if (!validEntry(current)) { scheduleAuthorizationCheck(); return }
        val generation = authorizationGeneration
        val origin = "https://${Uri.parse(current).host}"
        // The RC page alone performs device-token polling and exchanges its token for a
        // same-origin cookie. Observe only the resulting boolean; never move credentials
        // into native code or reload the page while its pending device code lives in JS.
        web.evaluateJavascript("""
            (() => {
              if (location.origin !== '$origin') return 'unknown';
              let p = window.__kimboxAuthorizationProbe;
              if (!p || p.generation !== $generation) {
                p = {generation: $generation, result: 'pending', busy: false};
                window.__kimboxAuthorizationProbe = p;
              }
              if (p.result === 'authorized') return p.result;
              const error = document.querySelector('.err-text');
              if (error && error.textContent.trim()) return 'ended';
              const result = p.result;
              if (!p.busy) {
                p.busy = true;
                const controller = new AbortController();
                const timeout = setTimeout(() => controller.abort(), 5000);
                fetch('/auth/me', {credentials: 'same-origin', cache: 'no-store',
                    redirect: 'error', signal: controller.signal})
                  .then(r => {
                    if (r.status === 401 || r.status === 403) return null;
                    if (!r.ok || !(r.headers.get('content-type') || '').includes('application/json')) throw new Error();
                    return r.json();
                  })
                  .then(j => { p.result = j && typeof j.user_id === 'string' && j.user_id.trim() ? 'authorized' : 'pending'; })
                  .catch(() => { p.result = 'network_error'; })
                  .finally(() => { clearTimeout(timeout); p.busy = false; });
              }
              return result;
            })()
        """.trimIndent()) { result ->
            if (generation != authorizationGeneration || pendingAuthorizationUrl == null || !resumed) return@evaluateJavascript
            when (result) {
                "\"authorized\"" -> {
                    CookieManager.getInstance().flush()
                    clearAuthorization()
                    installNotificationHook()
                    maybeRenameOwnDevice()
                    toast("远程控制已登录")
                }
                "\"ended\"" -> {
                    clearAuthorization()
                    toast("本次授权已结束，请在设备页查看提示或重新登录")
                }
                else -> {
                    authorizationStatus?.text = if (result == "\"network_error\"")
                        "暂时无法检查，网络恢复后会自动重试" else "等待授权完成，状态会自动更新…"
                    scheduleAuthorizationCheck()
                }
            }
        }
    }

    private fun installNotificationHook() {
        val current = web.url.orEmpty()
        if (!validEntry(current)) return
        val origin = "https://${Uri.parse(current).host}"
        web.evaluateJavascript("""
            (() => {
              if (location.origin !== '$origin' || window.__kimboxRcNotifications) return;
              window.__kimboxRcNotifications = true;
              function KimboxNotification(title, options) {
                const opts = options || {};
                console.log('$RC_NOTIFICATION' + JSON.stringify({
                  title: String(title || ''), body: String(opts.body || ''), tag: String(opts.tag || '')
                }));
                this.onclick = null;
              }
              KimboxNotification.permission = 'granted';
              KimboxNotification.requestPermission = () => Promise.resolve('granted');
              KimboxNotification.prototype.close = function() {};
              KimboxNotification.prototype.addEventListener = function() {};
              KimboxNotification.prototype.removeEventListener = function() {};
              window.Notification = KimboxNotification;
            })()
        """.trimIndent(), null)
    }

    private fun forwardRemoteNotification(raw: String) {
        if (raw.length > 16 * 1024 || isFinishing || isDestroyed) return
        val data = try { JSONObject(raw) } catch (_: Exception) { return }
        val title = data.optString("title").take(256).ifBlank { "远程控制通知" }
        val body = data.optString("body").take(1024).ifBlank { "点击查看远程任务结果" }
        val tag = data.optString("tag").take(256).ifBlank { "rc-generic" }
        remoteSessionActive = true
        scheduleIdleClose()
        TaskCompletionMonitor.ensureChannel(this)
        val click = Intent(this, RemoteControlActivity::class.java)
            .setData(Uri.parse(entryUrl))
            .putExtra("url", entryUrl)
            .putExtra("dark", colors.dark)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, click,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, TaskCompletionMonitor.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("远程控制")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        getSystemService(NotificationManager::class.java).notify("rc:$tag", 3, notification)
    }

    private fun scheduleIdleClose() {
        handler.removeCallbacks(idleClose)
        if (!resumed && remoteSessionActive && fileChooser?.isPending != true && pendingAuthorizationUrl == null) handler.postDelayed(idleClose, IDLE_TIMEOUT_MS)
    }

    private fun maybeRenameOwnDevice() {
        val id = intent.getStringExtra("rename_device_id").orEmpty()
        val alias = intent.getStringExtra("device_alias").orEmpty().trim()
        val current = web.url.orEmpty()
        if (id.isBlank() || alias.isBlank() || !validEntry(current)) return
        val origin = "https://${Uri.parse(current).host}"
        val quotedId = JSONObject.quote(id)
        val quotedAlias = JSONObject.quote(alias)
        web.evaluateJavascript("""
            (() => {
              if (location.origin !== '$origin') return;
              fetch('/v1/remote/devices', {credentials: 'same-origin', cache: 'no-store', redirect: 'error'})
                .then(r => r.ok ? r.json() : null)
                .then(j => {
                  if (!j || !Array.isArray(j.devices)) return null;
                  const device = j.devices.find(d => d.device_id === $quotedId);
                  if (!device) return null;
                  const current = String(device.alias || '').trim();
                  if (current && current.toLowerCase() !== 'localhost') return null;
                  return fetch('/v1/remote/devices/' + encodeURIComponent($quotedId), {
                    method: 'PATCH', credentials: 'same-origin', redirect: 'error',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({alias: $quotedAlias})
                  });
                }).catch(() => {});
            })()
        """.trimIndent(), null)
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (fileChooser?.onActivityResult(requestCode, resultCode, data) == true) {
            fileChooser = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        fileChooser?.saveState(outState)
        outState.putString("pending_authorization_url", pendingAuthorizationUrl)
        outState.putLong("authorization_started_at", authorizationStartedAt)
        if (::web.isInitialized) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.removeCallbacks(idleClose)
        scheduleAuthorizationCheck(0)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(authorizationPoll)
        scheduleIdleClose()
        if (::web.isInitialized) CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!::web.isInitialized) {
            finish()
            return
        }
        web.evaluateJavascript(MainActivity.CLOSE_WEB_OVERLAY_JS) { result ->
            val closedOverlay = result != null && result != "null" && result.trim('"').isNotEmpty()
            if (closedOverlay || isDestroyed || isFinishing) return@evaluateJavascript
            if (web.canGoBack()) web.goBack() else finish()
        }
    }

    override fun onDestroy() {
        clearAuthorization()
        handler.removeCallbacks(idleClose)
        fileChooser?.release()
        fileChooser = null
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
        private const val RC_NOTIFICATION = "__kimbox_rc_notification:"
        private const val IDLE_TIMEOUT_MS = 15 * 60 * 1000L
        private var profileInitialized = false

        private fun isAuthorizationUrl(uri: Uri): Boolean =
            uri.host in setOf("www.kimi.com", "kimi.com", "www.kimi.ai", "kimi.ai") &&
                uri.path == "/code/authorize_device"

        fun validEntry(url: String): Boolean {
            val uri = Uri.parse(url)
            return uri.scheme == "https" && uri.host in setOf("code-rc.kimi.com", "code-rc.kimi.ai") &&
                uri.userInfo == null && uri.port in setOf(-1, 443)
        }
    }
}
