package com.kimbox

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用运行时自带的 util-linux `script` 起一个真实 PTY，不依赖 NDK/node-pty。
 *
 * `setsid` 让 script、shell、resize 助手和前台命令都带上唯一的 KIMBOX_TERM_TOKEN。
 * util-linux 的 script 会给子 shell 再开一个独立 session，所以关闭时不能只杀 script
 * 的进程组：按 token 和 /proc 父子关系收集整棵进程树，再逐组、逐进程收尾。
 */
class TerminalSession(
    context: Context,
    initialCols: Int,
    initialRows: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onOutput(data: ByteArray)
        fun onExit(exitCode: Int)
        fun onStartFailed(message: String)
    }

    private companion object {
        const val MIN_SIZE = 2
        const val MAX_COLS = 500
        const val MAX_ROWS = 300
        const val READ_CHUNK = 8 * 1024
        // 输出队列有界：页面/渲染跟不上时反压到 PTY，而不是把终端内容堆进 Java 堆
        const val MAX_PENDING_CHUNKS = 64
        const val MAX_BATCH = 64 * 1024
        const val CLOSE_HUP_MS = 400L
        const val CLOSE_TERM_MS = 700L
        const val CLOSE_KILL_MS = 200L
        const val DRAIN_TIMEOUT_MS = 5000L
        const val TOKEN_ENV = "KIMBOX_TERM_TOKEN"
        const val MAX_ENVIRON_BYTES = 512 * 1024
    }

    private val appContext = context.applicationContext
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val terminalDir = File(appContext.filesDir, "terminal")
    private val pidFile = File(terminalDir, "session-$token.pid")

    private val queue = ArrayBlockingQueue<ByteArray>(MAX_PENDING_CHUNKS)
    private val pumpDone = CountDownLatch(1)

    @Volatile private var closed = false
    // stdout 已 EOF：pump 可以排空队列后退出了
    @Volatile private var inputClosed = false
    @Volatile private var cols = initialCols.coerceIn(MIN_SIZE, MAX_COLS)
    @Volatile private var rows = initialRows.coerceIn(MIN_SIZE, MAX_ROWS)
    @Volatile private var fifo: File? = null

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var pumpThread: Thread? = null

    // 自己所在进程组：无论 /proc 里读到什么，都不向它发信号
    private val selfPgid: Int by lazy {
        readProcInfo(android.os.Process.myPid())?.pgid ?: -1
    }
    // pidfile 里 root 进程的 starttime；后续清理必须与它一致，防止 pid 复用误杀
    @Volatile private var rootStartTime = -1L

    fun start() {
        if (!RuntimeEnvironment.ready(appContext)) {
            listener.onStartFailed("运行环境还没部署完成，请先回主界面等引擎启动后再试")
            return
        }
        try {
            RuntimeEnvironment.prepare(appContext)
            terminalDir.mkdirs()
            val resizeFifo = prepareFifo()
            fifo = resizeFifo

            val p = launch(resizeFifo)
            process = p
            stdin = p.outputStream

            pumpThread = Thread({ pumpLoop() }, "terminal-pump").apply {
                isDaemon = true
                start()
            }
            Thread({ readLoop(p.inputStream) }, "terminal-reader").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            cleanupFiles()
            try { process?.destroyForcibly() } catch (_: Throwable) {}
            listener.onStartFailed("无法启动终端：${t.message ?: "未知错误"}")
        }
    }

    fun write(data: ByteArray) {
        if (closed || data.isEmpty()) return
        val out = stdin ?: return
        synchronized(this) {
            if (closed) return
            try {
                out.write(data)
                out.flush()
            } catch (_: Throwable) {
            }
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(MIN_SIZE, MAX_COLS)
        val r = newRows.coerceIn(MIN_SIZE, MAX_ROWS)
        if (closed || (c == cols && r == rows)) return
        cols = c
        rows = r

        val f = fifo ?: return
        Thread({
            val line = "$c $r\n".toByteArray(Charsets.US_ASCII)
            var sent = false
            var attempt = 0
            while (!closed && !sent && attempt < 3) {
                try {
                    val fd = Os.open(f.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
                    try {
                        Os.write(fd, line, 0, line.size)
                        sent = true
                    } finally {
                        try { Os.close(fd) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    // 助手正好在 EOF/重新打开 FIFO 的窗口里，稍后重试
                }
                attempt++
                if (!sent && attempt < 3) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { attempt = 3 }
                }
            }
        }, "terminal-resize").apply { isDaemon = true }.start()
    }

    fun close() {
        if (closed) return
        closed = true
        pumpThread?.interrupt()
        val p = process
        try { stdin?.close() } catch (_: Throwable) {}
        stdin = null

        if (p == null) {
            cleanupFiles()
            return
        }
        Thread({ terminate(p) }, "terminal-close").apply { isDaemon = true }.start()
    }

    private fun launch(resizeFifo: File?): Process {
        val prefix = RuntimeEnvironment.prefix(appContext)
        val bash = File(prefix, "bin/bash")
        val command = listOf(
            File(prefix, "bin/script").path,
            "-q", "-f", "-e",
            "-c", buildShellCommand(bash, resizeFifo),
            "/dev/null"
        ).joinToString(" ") { shQuote(it) }

        // 先写 pid 再 exec：不依赖 Process.pid()/反射，Android 上也能拿到 session 首进程
        val wrapper = "echo \$\$ > ${shQuote(pidFile.path)}; exec $command"
        val pb = ProcessBuilder(File(prefix, "bin/setsid").path, bash.path, "-c", wrapper)
        RuntimeEnvironment.apply(pb, appContext)
        pb.environment()[TOKEN_ENV] = token
        pb.redirectErrorStream(true)
        return pb.start()
    }

    private fun buildShellCommand(bash: File, resizeFifo: File?): String {
        // token 放在 -c 命令行里：即使 FIFO 创建失败，root pid 也能被唯一识别
        val initial = ": $token; stty cols $cols rows $rows 2>/dev/null"
        if (resizeFifo == null) return "$initial; exec ${shQuote(bash.path)} -i"

        // FIFO 每收到一行 "列 行" 就通过 /dev/tty 改 PTY 尺寸，内核会给前台进程发 SIGWINCH。
        // 外层 while 让助手在每次写入方关闭 FIFO 后重新打开，不退出。
        val helper = "( while :; do while read c r; do " +
            "stty cols \"\$c\" rows \"\$r\" < /dev/tty 2>/dev/null; done < " +
            shQuote(resizeFifo.path) + "; done ) & "
        return "$initial; $helper" + "exec ${shQuote(bash.path)} -i"
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun prepareFifo(): File? = try {
        val f = File(terminalDir, "resize-$token.fifo")
        if (f.exists()) f.delete()
        Os.mkfifo(f.path, 384) // 0600
        f
    } catch (_: Throwable) {
        null
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(READ_CHUNK)
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val chunk = buf.copyOf(n)
                while (!closed) {
                    if (queue.offer(chunk, 200, TimeUnit.MILLISECONDS)) break
                }
            }
        } catch (_: Throwable) {
        } finally {
            inputClosed = true
            // 做退出判定前先把剩余输出交给页面，避免 login 链接/最后几行被吞
            try {
                pumpDone.await(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            if (!closed) notifyExit()
            try { input.close() } catch (_: Throwable) {}
        }
    }

    private fun notifyExit() {
        val p = process ?: return
        val code = try {
            if (p.waitFor(2, TimeUnit.SECONDS)) p.exitValue() else -1
        } catch (_: Throwable) {
            -1
        }
        // -1 表示 stdout 已关但进程还没退：交给 close() 继续清理进程树
        if (!closed) listener.onExit(code)
    }

    private fun pumpLoop() {
        val batch = ByteArrayOutputStream(MAX_BATCH)
        try {
            while (!closed) {
                val first = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (first == null) {
                    if (inputClosed) break
                    continue
                }

                batch.reset()
                batch.write(first, 0, first.size)
                while (batch.size() < MAX_BATCH) {
                    val next = queue.poll() ?: break
                    batch.write(next, 0, next.size)
                }
                if (batch.size() > 0 && !closed) listener.onOutput(batch.toByteArray())
            }
        } finally {
            pumpDone.countDown()
        }
    }

    private fun terminate(p: Process) {
        try {
            var snapshot = snapshotProcesses()
            if (snapshot.isNotEmpty()) {
                signalSnapshot(snapshot, OsConstants.SIGHUP)
                if (!waitGone(snapshot, CLOSE_HUP_MS)) {
                    snapshot = snapshotProcesses()
                    signalSnapshot(snapshot, OsConstants.SIGTERM)
                    if (!waitGone(snapshot, CLOSE_TERM_MS)) {
                        snapshot = snapshotProcesses()
                        signalSnapshot(snapshot, OsConstants.SIGKILL)
                        waitGone(snapshot, CLOSE_KILL_MS)
                    }
                }
            }
            // 兜底：即使 /proc 里什么都没读到，也要结束 ProcessBuilder 的直接子进程
            try { p.destroy() } catch (_: Throwable) {}
            try {
                if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            } catch (_: Throwable) {
                try { p.destroyForcibly() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            try { p.destroyForcibly() } catch (_: Throwable) {}
        } finally {
            closeStreams(p)
            cleanupFiles()
        }
    }

    /**
     * 当前会话仍活着的进程：token 命中（含 script/shell/助手/前台命令）+ 这些进程的子孙。
     * 快照同时记下 starttime，最终发信号前再核对一次，避免 pid 被复用时误杀别的进程。
     */
    private fun snapshotProcesses(): Map<Int, Long> {
        val out = LinkedHashMap<Int, Long>()
        val visited = HashSet<Int>()
        val byToken = processesWithToken()
        for (pid in byToken) {
            collectDescendants(pid, out, visited)
        }

        val root = readPidFile()
        if (root > 0 && root !in byToken && rootLooksOurs(root)) {
            val info = readProcInfo(root)
            if (info != null) {
                val expected = rootStartTime
                if (expected <= 0 || expected == info.startTime) {
                    rootStartTime = info.startTime
                    collectDescendants(root, out, visited)
                }
            }
        }
        out.remove(android.os.Process.myPid())
        return out
    }

    private fun processesWithToken(): Set<Int> {
        val result = HashSet<Int>()
        val needle = "$TOKEN_ENV=$token".toByteArray(Charsets.UTF_8)
        val names = try { File("/proc").list() } catch (_: Throwable) { null } ?: return result
        val self = android.os.Process.myPid()
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            if (pid == self) continue
            if (containsToken(File("/proc/$pid/environ"), needle)) result.add(pid)
        }
        return result
    }

    private fun containsToken(file: File, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return false
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(4096)
                var matched = 0
                var total = 0
                while (total < MAX_ENVIRON_BYTES) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    for (i in 0 until n) {
                        val b = buf[i]
                        if (b == needle[matched]) {
                            matched++
                            if (matched == needle.size) return true
                        } else {
                            matched = if (b == needle[0]) 1 else 0
                        }
                    }
                }
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * pidfile 可能是别的东西写的，root 必须带本会话唯一 token（wrapper 的 pidfile 路径
     * 或 script -c 里的 FIFO 路径/标记），不能只看通用的 script 路径。
     */
    private fun rootLooksOurs(pid: Int): Boolean {
        if (pid == android.os.Process.myPid()) return false
        val cmdline = try {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            return false
        }
        return cmdline.contains(token)
    }

    private fun collectDescendants(pid: Int, out: MutableMap<Int, Long>, visited: MutableSet<Int>) {
        if (pid <= 0 || !visited.add(pid)) return
        val info = readProcInfo(pid) ?: return
        out[pid] = info.startTime
        val children = try {
            File("/proc/$pid/task/$pid/children").readText().trim()
        } catch (_: Throwable) {
            ""
        }
        if (children.isEmpty()) return
        for (part in children.split(' ')) {
            val child = part.toIntOrNull() ?: continue
            collectDescendants(child, out, visited)
        }
    }

    /** 发信号前逐个核对 starttime；pid 已被复用或已成僵尸的跳过 */
    private fun signalSnapshot(startTimes: Map<Int, Long>, sig: Int) {
        val self = android.os.Process.myPid()
        val verified = ArrayList<Int>(startTimes.size)
        val groups = HashSet<Int>()
        for ((pid, started) in startTimes) {
            if (pid == self) continue
            val info = readProcInfo(pid) ?: continue
            if (info.startTime != started || info.state == 'Z') continue
            verified.add(pid)
            if (info.pgid > 0 && info.pgid != selfPgid) groups.add(info.pgid)
        }
        for (g in groups) {
            try { Os.kill(-g, sig) } catch (_: Throwable) {}
        }
        for (pid in verified) {
            try { Os.kill(pid, sig) } catch (_: Throwable) {}
        }
    }

    private fun waitGone(startTimes: Map<Int, Long>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            var alive = false
            for ((pid, started) in startTimes) {
                val info = readProcInfo(pid)
                if (info != null && info.startTime == started && info.state != 'Z') {
                    alive = true
                    break
                }
            }
            if (!alive) return true
            if (System.currentTimeMillis() >= deadline) return false
            try { Thread.sleep(40) } catch (_: InterruptedException) { return false }
        }
    }

    private data class ProcInfo(val state: Char, val pgid: Int, val startTime: Long)

    private fun readProcInfo(pid: Int): ProcInfo? {
        if (pid <= 0) return null
        val text = try { File("/proc/$pid/stat").readText() } catch (_: Throwable) { return null }
        val close = text.lastIndexOf(')')
        if (close < 0) return null
        val fields = text.substring(close + 1).trim().split(' ')
        if (fields.size < 20) return null
        val state = fields[0].firstOrNull() ?: return null
        val pgid = fields[2].toIntOrNull() ?: return null
        val startTime = fields[19].toLongOrNull() ?: return null
        return ProcInfo(state, pgid, startTime)
    }

    private fun readPidFile(): Int = try {
        pidFile.readText().trim().toIntOrNull()?.takeIf { it > 0 } ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun closeStreams(p: Process) {
        try { p.outputStream.close() } catch (_: Throwable) {}
        try { p.inputStream.close() } catch (_: Throwable) {}
        try { p.errorStream.close() } catch (_: Throwable) {}
    }

    private fun cleanupFiles() {
        try { fifo?.delete() } catch (_: Throwable) {}
        fifo = null
        try { pidFile.delete() } catch (_: Throwable) {}
    }
}
