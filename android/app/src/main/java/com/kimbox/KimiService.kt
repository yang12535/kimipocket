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
import java.net.InetSocketAddress
import java.net.Socket

class KimiService : Service() {

    companion object {
        private const val CHANNEL_ID = "kimi"
        private const val NOTIF_ID = 1
    }

    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithText("正在启动…")
        acquireWakeLock()
        if (process == null) {
            stopped = false
            Thread { run() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        process?.destroy()
        process = null
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
            val port = findFreePort()
            KimiState.status = "正在启动 Kimi 引擎…"
            updateNotification("正在启动 Kimi 引擎…")
            val p = try {
                startKimiProcess(port)
            } catch (t: Throwable) {
                KimiState.lastError = t.message
                crashes++
                sleepBackoff(crashes)
                continue
            }
            process = p
            if (waitForServer(p, port, 45_000)) {
                KimiState.url = "http://127.0.0.1:$port/"
                KimiState.status = "运行中"
                KimiState.running = true
                KimiState.lastError = null
                updateNotification("Kimi 运行中，点我打开")
                crashes = 0
            } else {
                try { p.destroy() } catch (_: Throwable) {}
            }
            val code = try { p.waitFor() } catch (_: InterruptedException) { break }
            process = null
            KimiState.url = null
            KimiState.running = false
            if (stopped) break
            crashes++
            KimiState.status = "引擎退出(code=$code)，稍后重启…"
            updateNotification("Kimi 引擎已退出，准备重启")
            sleepBackoff(crashes)
        }
    }

    private fun sleepBackoff(crashes: Int) {
        val delay = if (crashes > 3) 30_000L else 5_000L
        var left = delay
        while (left > 0 && !stopped) {
            Thread.sleep(500)
            left -= 500
        }
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
            // 只绑 loopback + 随机端口，本机之外不可达；WebView 同机直连
            "--dangerous-bypass-auth",
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

    private fun waitForServer(p: Process, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return true
            } catch (_: Throwable) {
                Thread.sleep(300)
            }
        }
        return false
    }

    private fun findFreePort(): Int = try {
        java.net.ServerSocket(0).use { it.localPort }
    } catch (_: Throwable) {
        58627
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
