package com.kimbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class KimiService : Service() {

    companion object {
        private const val CHANNEL_ID = "kimi"
        private const val NOTIF_ID = 1

        // 固定端口让 WebView 的 localStorage（同源按端口隔离）在重启后仍然有效；
        // 被占用时才退到随机端口
        private const val PREFERRED_PORT = 17234

        // 连续崩溃这么多次后放弃自动重启，避免烧电死循环
        private const val MAX_CRASHES = 5
    }

    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopped = false
    @Volatile private var engineThreadRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithText("正在启动…")
        acquireWakeLock()
        if (!engineThreadRunning) {
            engineThreadRunning = true
            stopped = false
            Thread {
                try {
                    run()
                } finally {
                    engineThreadRunning = false
                }
            }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        val p = process
        process = null
        if (p != null) {
            p.destroy()
            Thread {
                try {
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
                } catch (_: Throwable) {
                    try { p.destroyForcibly() } catch (_: Throwable) {}
                }
            }.start()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        KimiState.running = false
        KimiState.url = null
        super.onDestroy()
    }

    private fun run() {
        try {
            RuntimeInstaller.ensureInstalled(applicationContext)
            RuntimeInstaller.ensureHome(applicationContext)
            prepareWorkspace()
            startKimiLoop()
        } catch (t: Throwable) {
            android.util.Log.e("kimbox", "engine bootstrap failed", t)
            KimiState.lastError = t.stackTraceToString().take(600)
            KimiState.status = "启动失败"
            updateNotification("启动失败：${t.message}")
        }
    }

    private fun workspaceDir(): File {
        // 工作目录放外部应用私有目录：文件管理器可见，朋友能拿到产出物
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "workspace")
    }

    private fun prepareWorkspace() {
        val ws = workspaceDir()
        ws.mkdirs()
        val readme = File(ws, "README.txt")
        if (!readme.exists()) {
            readme.writeText(
                """
                这是 Kimi 在手机上的工作目录。

                Kimi 帮你写的代码、做的文件都放在这里。
                想让它干活，直接在聊天框里说人话就行，比如：
                「帮我做一个记录每天开销的网页」

                本目录位置：文件管理器 → Android/data/com.kimbox/files/workspace
                """.trimIndent() + "\n"
            )
        }
    }

    private fun startKimiLoop() {
        var crashes = 0
        while (!stopped) {
            val port = pickPort()
            KimiState.status = "正在启动 Kimi 引擎…"
            updateNotification("正在启动 Kimi 引擎…")
            val p = try {
                startKimiProcess(port)
            } catch (t: Throwable) {
                KimiState.lastError = t.message
                crashes++
                if (giveUpIfHopeless(crashes)) break
                sleepBackoff(crashes)
                continue
            }
            process = p
            val token = if (waitForServer(p, port, 45_000)) readServerToken(10_000) else null
            if (token != null) {
                // Web UI 从 URL fragment 读 token（#token=...），fragment 不会随请求发出
                KimiState.url = "http://127.0.0.1:$port/#token=" + URLEncoder.encode(token, "UTF-8")
                KimiState.status = "运行中"
                KimiState.running = true
                KimiState.lastError = null
                updateNotification("Kimi 运行中，点我打开")
                crashes = 0
            } else {
                KimiState.lastError = "引擎未能正常就绪（无 server token）"
                try { p.destroy() } catch (_: Throwable) {}
            }
            val code = try { p.waitFor() } catch (_: InterruptedException) { break }
            process = null
            KimiState.url = null
            KimiState.running = false
            if (stopped) break
            crashes++
            if (giveUpIfHopeless(crashes)) break
            KimiState.status = "引擎退出(code=$code)，稍后重启…"
            updateNotification("Kimi 引擎已退出，准备重启")
            sleepBackoff(crashes)
        }
    }

    private fun giveUpIfHopeless(crashes: Int): Boolean {
        if (crashes < MAX_CRASHES) return false
        KimiState.status = "引擎连续崩溃 $crashes 次，已停止自动重启，请重新打开 App"
        updateNotification("引擎反复崩溃，已停止。请重新打开 App")
        stopSelf()
        return true
    }

    private fun sleepBackoff(crashes: Int) {
        val delay = if (crashes > 3) 30_000L else 5_000L
        var left = delay
        while (left > 0 && !stopped) {
            Thread.sleep(500)
            left -= 500
        }
    }

    /** 引擎首启时自动生成并复用 files/home/.kimi-code/server.token（0600） */
    private fun readServerToken(timeoutMs: Long): String? {
        val f = File(filesDir, "home/.kimi-code/server.token")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !stopped) {
            try {
                if (f.isFile) {
                    val t = f.readText().trim()
                    if (t.isNotEmpty()) return t
                }
            } catch (_: Throwable) {}
            Thread.sleep(300)
        }
        return null
    }

    private fun startKimiProcess(port: Int): Process {
        val prefix = File(filesDir, "usr")
        val home = File(filesDir, "home").apply { mkdirs() }
        File(prefix, "tmp").mkdirs()
        File(filesDir, "logs").mkdirs()

        val pb = ProcessBuilder(
            "${prefix.path}/bin/node",
            "${prefix.path}/lib/node_modules/@moonshot-ai/kimi-code/dist/main.mjs",
            "web",
            "--port", port.toString(),
            // 只绑 loopback；REST/WS 走内置 bearer token（server.token），同机其他 App 无法冒用
            "--no-open",
            "--web-title", "口袋Kimi"
        )
        pb.directory(workspaceDir())
        val env = pb.environment()
        env["PREFIX"] = prefix.path
        env["HOME"] = home.path
        env["PATH"] = "${prefix.path}/bin"
        env["LD_LIBRARY_PATH"] = "${prefix.path}/lib"
        // termux-exec：修正脚本 shebang（#!/usr/bin/env 等），没有它 npm/kimi 子进程会挂
        env["LD_PRELOAD"] = "${prefix.path}/lib/libtermux-exec.so"
        env["TMPDIR"] = "${prefix.path}/tmp"
        env["LANG"] = "C.UTF-8"
        env["TERM"] = "xterm-256color"
        env["SHELL"] = "${prefix.path}/bin/bash"
        pb.redirectErrorStream(true)

        val p = pb.start()
        val logFile = File(filesDir, "logs/kimi.log")
        Thread {
            try {
                FileWriter(logFile, true).use { w ->
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (logFile.length() > 2 * 1024 * 1024) logFile.writeText("")
                        w.write(line + "\n")
                        w.flush()
                    }
                }
            } catch (_: Throwable) {}
        }.start()
        return p
    }

    /** 不能只验 TCP connect——端口可能被别的进程占用；要求 HTTP 指纹确认是本引擎 */
    private fun waitForServer(p: Process, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return false
            if (probeHealthz(port)) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun probeHealthz(port: Int): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$port/api/v1/healthz")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.inputStream.bufferedReader().use { it.readText() }.contains("\"ok\"")
        } catch (_: Throwable) {
            false
        }
    }

    private fun pickPort(): Int {
        try {
            ServerSocket(PREFERRED_PORT).use { return PREFERRED_PORT }
        } catch (_: Throwable) {}
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (_: Throwable) {
            58627
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kimi 后台服务", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("口袋Kimi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithText(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kimbox:engine").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }
}
