package com.kimbox

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Toast
import java.io.File

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
    }

    /** agent 把要导出的文件放这里，用户通过导出按钮走 SAF 保存到公共位置 */
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

        // 右下角浮动导出按钮：点它走 SAF 把 ~/exports/ 里的文件存到公共位置
        val exportBtn = TextView(this).apply {
            text = "\uD83D\uDCE4" // 📤
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC333333.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { showExportDialog() }
            setOnLongClickListener {
                Toast.makeText(this@MainActivity, "导出文件", Toast.LENGTH_SHORT).show()
                true
            }
        }
        val btnSize = (56 * resources.displayMetrics.density).toInt()
        val btnMargin = (16 * resources.displayMetrics.density).toInt()
        root.addView(exportBtn, FrameLayout.LayoutParams(btnSize, btnSize).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, btnMargin, btnMargin)
        })

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
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
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
                // 系统 picker 返回的 URI 已带写权限，持久化以备异步线程使用
                try {
                    contentResolver.takePersistableUriPermission(
                        data.data!!,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
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
