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

        // 时间窗内崩溃这么多次后放弃自动重启，避免烧电死循环。
        // 注意是「窗口内累计」而不是「连续」：引擎能启动、跑一会再被杀（phantom killer/OOM）
        // 是国产手机最常见的死法，连续计数会被每次成功启动清零而永远摸不到上限
        private const val MAX_CRASHES = 5
        private const val CRASH_WINDOW_MS = 10 * 60 * 1000L

        // token 明文会被引擎启动 banner 打进 stdout（进而落 kimi.log），落盘与上屏前都要脱敏
        private val RE_FRAG_TOKEN = Regex("#token=\\S+")
        private val RE_BANNER_TOKEN = Regex("(?i)token:\\s+\\S+")
    }

    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopped = false
    @Volatile private var engineThreadRunning = false

    /** 本次引擎进程启动时 kimi.log 的字节偏移：自愈判定只看本次新产生的输出，不吃历史旧错误 */
    @Volatile private var logStartOffset = 0L
    /** 从 server.token 读到的当前 token，用于日志脱敏（不用于网络探测——见 waitForServer） */
    @Volatile private var currentToken: String? = null
    /** 引擎 banner 自报的绑定端口（从本次 stdout 解析），用于确认 healthz 应答者是我们的进程 */
    @Volatile private var bannerPort: Int? = null

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
        var attempts = 0
        while (!stopped) {
            try {
                RuntimeInstaller.ensureInstalled(applicationContext)
                RuntimeInstaller.ensureHome(applicationContext)
                prepareWorkspace()
                startKimiLoop()
                return
            } catch (t: Throwable) {
                attempts++
                android.util.Log.e("kimbox", "engine bootstrap failed (attempt $attempts)", t)
                KimiState.lastError = t.stackTraceToString().take(600)
                if (attempts >= 3) {
                    KimiState.status = "启动失败（已自动重试 $attempts 次）。请重新打开 App；仍不行则在系统设置里清除本应用数据（会退出登录）后重试"
                    updateNotification("启动失败：${t.message}")
                    return
                }
                KimiState.status = "启动失败（${t.message}），30 秒后自动重试（$attempts/3）…"
                updateNotification("启动失败，稍后自动重试")
                var left = 30_000L
                while (left > 0 && !stopped) {
                    Thread.sleep(500)
                    left -= 500
                }
            }
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

                本目录位置：Android/data/com.kimbox/files/workspace
                （安卓 11 起手机上的文件管理器可能进不去这个目录：连电脑可以看到，
                 或者直接让 Kimi 把文件复制到 Download/下载 目录）
                """.trimIndent() + "\n"
            )
        }
    }

    private fun startKimiLoop() {
        val crashTimes = ArrayDeque<Long>()
        while (!stopped) {
            val port = pickPort()
            KimiState.status = "正在启动 Kimi 引擎…"
            updateNotification("正在启动 Kimi 引擎…")
            val p = try {
                startKimiProcess(port)
            } catch (t: Throwable) {
                KimiState.lastError = t.message
                if (giveUpIfHopeless(recordCrash(crashTimes))) break
                sleepBackoff(crashTimes.size)
                continue
            }
            process = p
            val token = if (waitForServer(p, port, 45_000)) readServerToken(10_000) else null
            if (token != null) {
                currentToken = token
                // Web UI 从 URL fragment 读 token（#token=...），fragment 不会随请求发出
                // 先写 port 再写 url：UI 看到新 url 就会 loadUrl，此时 port 必须已就绪，
                // 否则 shouldOverrideUrlLoading 会把引擎导航误判成外部链接跳浏览器
                KimiState.port = port
                KimiState.url = "http://127.0.0.1:$port/#token=" + URLEncoder.encode(token, "UTF-8")
                KimiState.status = "运行中"
                KimiState.running = true
                KimiState.lastError = null
                updateNotification("Kimi 运行中，点我打开")
            } else {
                KimiState.lastError = "引擎未能正常就绪（无 server token）"
                try { p.destroy() } catch (_: Throwable) {}
            }
            val code = try { p.waitFor() } catch (_: InterruptedException) { break }
            process = null
            KimiState.url = null
            KimiState.port = null
            KimiState.running = false
            if (stopped) break
            val crashes = recordCrash(crashTimes)
            // 把引擎日志尾巴亮出来，别让小白用户只看到"退出码=1"干瞪眼；
            // 只取本次进程新产生的输出：历史旧错误文本不该触发对完好运行时的自愈
            val logTail = tailOfLog(logStartOffset)
            if (logTail != null) KimiState.lastError = logTail
            if (looksLikeBrokenRuntime(logTail) && trySelfHeal()) {
                // 自愈后是全新运行时，崩溃窗口一并清零重新计
                crashTimes.clear()
                continue
            }
            if (giveUpIfHopeless(crashes)) break
            KimiState.status = "引擎退出(code=$code)，稍后重启…"
            updateNotification("Kimi 引擎已退出，准备重启")
            sleepBackoff(crashes)
        }
    }

    private fun recordCrash(crashTimes: ArrayDeque<Long>): Int {
        val now = System.currentTimeMillis()
        crashTimes.addLast(now)
        while (!crashTimes.isEmpty() && now - crashTimes.first() > CRASH_WINDOW_MS) {
            crashTimes.removeFirst()
        }
        return crashTimes.size
    }

    private fun redact(s: String): String {
        var out = s
        currentToken?.let { if (it.isNotEmpty()) out = out.replace(it, "***") }
        return out
            .replace(RE_FRAG_TOKEN, "#token=***")
            .replace(RE_BANNER_TOKEN, "Token: ***")
    }

    private fun tailOfLog(fromOffset: Long): String? {
        return try {
            val f = File(filesDir, "logs/kimi.log")
            if (!f.isFile) return null
            val bytes = f.readBytes()
            var off = fromOffset
            // 日志被 2MB 截断重置过的话偏移会越界，退回整份尾巴
            if (off < 0 || off > bytes.size) off = 0
            if (bytes.size - off > 2048L) off = bytes.size - 2048L
            redact(String(bytes, off.toInt(), (bytes.size - off).toInt()))
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
                .takeLast(3).joinToString("\n").take(500).ifBlank { null }
        } catch (_: Throwable) { null }
    }

    /** agent 有能力改坏自家运行时（如手工覆盖系统库），这类错误重试无用，只能重建 */
    private fun looksLikeBrokenRuntime(logTail: String?): Boolean {
        if (logTail == null) return false
        return logTail.contains("CANNOT LINK EXECUTABLE") ||
            logTail.contains("cannot locate symbol") ||
            logTail.contains("Cannot find module") ||
            logTail.contains("MODULE_NOT_FOUND")
    }

    /** 变砖自愈：清掉被污染的 usr/ 重解压（home/ 登录态不动）。每次服务运行只自愈一次，防死循环 */
    private var selfHealed = false
    private fun trySelfHeal(): Boolean {
        if (selfHealed) return false
        selfHealed = true
        return try {
            KimiState.status = "检测到运行时损坏，正在自动修复（重装引擎，不影响登录）…"
            updateNotification("运行时损坏，正在自动修复…")
            deleteRecursivelyNoFollow(File(filesDir, "usr"))
            RuntimeInstaller.ensureInstalled(applicationContext)
            RuntimeInstaller.ensureHome(applicationContext)
            true
        } catch (t: Throwable) {
            KimiState.lastError = "自动修复失败：${t.message}"
            false
        }
    }

    private fun giveUpIfHopeless(crashes: Int): Boolean {
        if (crashes < MAX_CRASHES) return false
        KimiState.status = "引擎在 10 分钟内崩溃 $crashes 次，已停止自动重启。请重新打开 App；仍不行则在系统设置里清除本应用数据（会退出登录）后重试"
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

        // 记录本次启动的日志起点（自愈判定只看新增输出），并预载 token 供日志脱敏
        val logFile = File(filesDir, "logs/kimi.log")
        logStartOffset = if (logFile.isFile) logFile.length() else 0L
        if (currentToken == null) {
            currentToken = try {
                File(filesDir, "home/.kimi-code/server.token").readText().trim().ifEmpty { null }
            } catch (_: Throwable) { null }
        }
        bannerPort = null

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
        val bannerRe = Regex("""Local:\s+http://127\.0\.0\.1:(\d+)""")
        Thread {
            try {
                FileWriter(logFile, true).use { w ->
                    p.inputStream.bufferedReader().forEachLine { line ->
                        // banner 里的绑定端口来自进程自己的 stdout，同机抢端口者无法伪造这一条
                        val m = bannerRe.find(line)
                        if (m != null) bannerPort = m.groupValues[1].toIntOrNull()
                        if (logFile.length() > 2 * 1024 * 1024) logFile.writeText("")
                        w.write(redact(line) + "\n")
                        w.flush()
                    }
                }
            } catch (_: Throwable) {}
        }.start()
        return p
    }

    /**
     * 两道验证缺一不可：
     * 1) healthz HTTP 指纹（不能只验 TCP connect——端口可能被别的进程占用）
     * 2) 引擎 banner 自报端口与探测端口一致——healthz 无需认证、谁都能伪造应答，
     *    但 banner 来自我们子进程的 stdout，同机恶意 App 抢端口后写不进这条日志。
     *    注意：绝不在探测请求里带 token（伪造者应答 200 不需要知道 token，带了反而白送）。
     */
    private fun waitForServer(p: Process, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return false
            if (bannerPort == port && probeHealthz(port)) return true
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
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            body.contains("\"code\":0") && body.contains("\"ok\":true")
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
