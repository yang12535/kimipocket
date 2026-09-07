package com.kimbox

import android.content.Context
import android.os.StatFs
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * 把 assets 里的 .pkg（gzip 压缩的 tar）解到应用私有目录。
 * runtime.pkg -> filesDir/   (顶层是 usr/)
 * kimihome.pkg -> filesDir/home/  (顶层是 .kimi-code/)
 */
object RuntimeInstaller {

    // 每次 runtime.pkg 内容变化时 +1，触发已装机型的重解压（不动 home/，不影响登录态）
    private const val RUNTIME_VERSION = 7

    // home/ 种子文件版本号：当种子内容变化时 +1，触发已装机型的覆盖更新（仅限用户未修改的文件）。
    // 覆盖决策唯一依据：磁盘文件哈希 ∈ KNOWN_SEED_HASHES（已知官方发布哈希集合）。
    // .seedhash 文件仅作诊断参考，不作为覆盖授权依据。
    private const val SEED_VERSION = 5

    /**
     * 已知官方种子哈希表：path → 该文件在所有历史发布版中的 SHA-256 集合。
     * 覆盖决策的唯一依据——磁盘上的文件哈希在此集合中 → 说明是未被修改的官方种子，可安全覆盖；
     * 不在集合中 → 用户改过或来源不明，一律保留不覆盖。
     * 每次发布新种子内容时，把旧哈希和新哈希都保留在集合里。
     */
    private val KNOWN_SEED_HASHES: Map<String, Set<String>> = mapOf(
        ".kimi-code/AGENTS.md" to setOf(
            // v0.1.1（v0.1.0 尚无此文件）
            "72d892a1d77a46850404b086e39310f7a8e45bf9d484d085073616a9039a40a7",
            // v0.2.0 / v0.2.1 / v0.2.2 / v0.2.3（四版内容相同）
            "211e9e4317c4b5137883415199ecc6e265b7fcefedaa2913dc9ddca3d7fb2a97",
        ),
        ".kimi-code/skills/kimipocket-update/SKILL.md" to setOf(
            // v0.2.0
            "9aea19e0503bbc6c0d5a5fda7b59f6de60908e1b1c5be798e0ac23be30a0416a",
            // v0.2.1
            "384fc5775e2637d73b23a877fd396ead5b3971468afca3d72c3f7e2cdb386e5a",
            // v0.2.2
            "eceb4163251f46a35d3a410f1bfac2b2316c9427f883cadcf7be0f53001c470d",
            // v0.2.3
            "34e04579e56ee956eeca44ec6c02b1347e6972fdce6dff2cc5a1bff85840aa12",
            // v0.2.4
            "8fe6820be30e5a6affe37aa568825ee7a7d65850427743f5dc319794168ccc66",
        ),
    )

    // 解压后约 350MB（usr/ ~233MB + 缓存增长余量），低于这个值宁可报错也别解一半
    private const val MIN_FREE_BYTES = 700L * 1024 * 1024

    /** 运行时关键文件：marker 正确但这些文件缺失/不可执行时，视为运行时损坏需重解压 */
    private val RUNTIME_CRITICAL_FILES = listOf(
        "usr/bin/node",
        "usr/lib/libtermux-exec.so",
    )

    @Synchronized
    fun ensureInstalled(ctx: Context) {
        val usr = File(ctx.filesDir, "usr")
        val marker = File(usr, ".rt_version")
        val installed = try {
            if (marker.exists()) marker.readText().trim().toIntOrNull() else null
        } catch (_: Exception) { null }
        if (installed == RUNTIME_VERSION) {
            // Pre-flight：即使 marker 正确，也验证关键可执行文件存在且可执行。
            // 缺失/不可执行说明运行时被损坏（误删、文件系统错误等），需要重解压。
            // 有界保护：由调用方（KimiService）控制同一启动内最多触发一次，避免死循环。
            val missing = RUNTIME_CRITICAL_FILES.firstOrNull { path ->
                val f = File(ctx.filesDir, path)
                !f.exists() || !f.canExecute()
            }
            if (missing == null) return
            android.util.Log.w("kimbox", "runtime pre-flight failed: $missing missing or not executable, re-extracting")
        }

        // 只要 usr/ 还在就得先清：版本升级（marker 在）或上次清理失败的半截树
        // （marker 已删但残留幸存，installed == null 也不能放过）。覆盖式解压会在
        // 目录↔文件 翻转、agent 留下的外部符号链接上抛异常，也会和残留混出缝合怪引擎
        if (usr.isDirectory) {
            KimiState.status = if (installed == null) "清理上次未完成的残留环境…" else "运行环境升级：正在清理旧环境…"
            // 清不干净就别往上盖：残留 + 新解压 = 缝合怪引擎。中止后下次启动重走本路径重试
            if (!deleteRecursivelyNoFollow(usr)) {
                throw IOException("旧环境清理不完整（可能有文件被占用），已中止；请重新打开 App 重试")
            }
        }

        val free = StatFs(ctx.filesDir.absolutePath).availableBytes
        if (free < MIN_FREE_BYTES) {
            throw IOException("存储空间不足：需要约 ${MIN_FREE_BYTES / 1024 / 1024}MB，当前可用 ${free / 1024 / 1024}MB")
        }

        KimiState.status = if (installed == null) "首次运行：正在部署运行环境…" else "运行环境升级：正在部署新环境…"
        try {
            extractTarGz(ctx, "runtime.pkg", ctx.filesDir)
            marker.parentFile?.mkdirs()
            marker.writeText("$RUNTIME_VERSION\n")
        } catch (t: Throwable) {
            // 解一半的树宁可整个删掉：下次启动从干净状态重来，
            // 避免同一个坏条目在每次启动时重复炸死（只能清数据的死局）
            if (!deleteRecursivelyNoFollow(usr)) {
                throw IOException("部署失败且残留清理不完整，请重新打开 App 重试", t)
            }
            throw t
        }
    }

    /** 初始配置只在缺失时写入，避免升级覆盖设备上已登录的状态 */
    @Synchronized
    fun ensureHome(ctx: Context) {
        val home = File(ctx.filesDir, "home")
        val ready = File(home, ".home_ready")
        if (ready.exists()) {
            ensureAgentsMd(ctx)
            return
        }
        // 兼容旧版/异常状态：.kimi-code 目录在就说明初始化过，只补 marker 和缺失的注入文件，
        // 绝不整包覆盖（agent 可能删过个别文件如 config.toml，覆盖会把用户定制重置回出厂）
        if (File(home, ".kimi-code").isDirectory) {
            home.mkdirs()
            ready.writeText("1\n")
            ensureAgentsMd(ctx)
            return
        }
        KimiState.status = "正在写入初始配置…"
        extractTarGz(ctx, "kimihome.pkg", home)
        ready.writeText("1\n")
        // 首次解压后立即登记种子哈希和版本：此时文件确定是官方种子，
        // 避免后续 SEED_VERSION 变化时因缺哈希而无法识别官方文件
        registerSeedHashes(ctx)
    }

    /**
     * 老装机补偿 + 版本更新：记忆模块的注入文件缺失时单独补种（不整包重解压）。
     * 当 SEED_VERSION 增加时，仅覆盖已知官方种子（通过 KNOWN_SEED_HASHES 判定）：
     * - 文件不存在 → 写入新种子
     * - 文件存在且哈希 ∈ KNOWN_SEED_HASHES → 未修改的官方种子 → 覆盖为新版本
     * - 文件存在且哈希 ∉ KNOWN_SEED_HASHES → 用户改过或来源不明 → 保留不覆盖
     *
     * .seedhash 仅作诊断记录（写入当前文件的实际哈希），不作为覆盖授权依据。
     * 兼容旧版写错基线的设备：错误基线不再被信任，天然自愈。
     */
    @Synchronized
    fun ensureAgentsMd(ctx: Context) {
        val seeds = listOf(
            ".kimi-code/AGENTS.md",
            ".kimi-code/skills/kimipocket-update/SKILL.md",
        )

        val versionFile = File(ctx.filesDir, "home/.kimi-code/.seed_version")
        val installedVersion = try {
            if (versionFile.exists()) versionFile.readText().trim().toIntOrNull() else null
        } catch (_: Exception) { null }
        val needsUpdate = installedVersion != SEED_VERSION

        for (name in seeds) {
            val target = File(ctx.filesDir, "home/$name")
            val data = readAssetEntry(ctx, "kimihome.pkg", name) ?: continue
            val hashFile = File(target.parentFile, "${target.name}.seedhash")

            if (!target.exists()) {
                // 文件缺失：始终补种
                target.parentFile?.mkdirs()
                target.writeBytes(data)
                hashFile.writeText(sha256hex(data))
                android.util.Log.i("kimbox", "seeded home/$name (missing)")
            } else if (needsUpdate) {
                val currentHash = sha256hex(target.readBytes())
                val knownHashes = KNOWN_SEED_HASHES[name] ?: emptySet()
                if (currentHash in knownHashes) {
                    // 磁盘文件是已知官方种子 → 安全覆盖
                    target.writeBytes(data)
                    hashFile.writeText(sha256hex(data))
                    android.util.Log.i("kimbox", "updated seed home/$name (v$SEED_VERSION)")
                } else {
                    // 哈希不在已知官方集合中 → 用户改过或来源不明 → 保留
                    hashFile.writeText(currentHash)
                    android.util.Log.i("kimbox", "skipping seed home/$name: not a known official seed hash")
                }
            }
            // else: 版本相同且文件存在 → 不动
        }

        if (needsUpdate) {
            versionFile.parentFile?.mkdirs()
            versionFile.writeText("$SEED_VERSION\n")
        }
    }

    /**
     * 首次解压 home 后立即登记种子信息：写入实际解压出来的文件哈希 + 当前 SEED_VERSION。
     * 此时内容确定是官方种子，可安全登记。与 Fix #1 共用 KNOWN_SEED_HASHES 做缺哈希设备的识别迁移。
     */
    internal fun registerSeedHashes(ctx: Context) {
        val seeds = listOf(
            ".kimi-code/AGENTS.md",
            ".kimi-code/skills/kimipocket-update/SKILL.md",
        )
        for (name in seeds) {
            val target = File(ctx.filesDir, "home/$name")
            if (!target.isFile) continue
            val hashFile = File(target.parentFile, "${target.name}.seedhash")
            hashFile.writeText(sha256hex(target.readBytes()))
        }
        val versionFile = File(ctx.filesDir, "home/.kimi-code/.seed_version")
        versionFile.parentFile?.mkdirs()
        versionFile.writeText("$SEED_VERSION\n")
    }

    /** SHA-256 哈希（十六进制字符串） */
    private fun sha256hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun readAssetEntry(ctx: Context, asset: String, wantName: String): ByteArray? {
        ctx.assets.open(asset).use { raw ->
            TarArchiveInputStream(GZIPInputStream(BufferedInputStream(raw, 64 * 1024))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    if (entry.isFile && entry.name.removePrefix("./") == wantName) {
                        return tar.readBytes()
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        return null
    }

    private fun extractTarGz(ctx: Context, asset: String, dest: File) {
        val destCanon = dest.canonicalPath
        ctx.assets.open(asset).use { raw ->
            TarArchiveInputStream(GZIPInputStream(BufferedInputStream(raw, 128 * 1024))).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                var count = 0
                while (entry != null) {
                    val e = entry!!
                    val name = e.name.removePrefix("./")
                    if (name.isNotEmpty()) {
                        val out = File(dest, name)
                        // 防路径逃逸：canonicalPath 会解析已存在的中间符号链接，
                        // "usr/lib/evil -> /sdcard" 之后再解 "usr/lib/evil/x" 会被这里拦下
                        if (!out.canonicalPath.startsWith(destCanon + File.separator)) {
                            throw IOException("tar 条目越界：$name")
                        }
                        when {
                            e.isDirectory -> out.mkdirs()
                            e.isSymbolicLink -> {
                                val link = e.linkName
                                val resolved = if (File(link).isAbsolute) {
                                    File(link).canonicalPath
                                } else {
                                    File(out.parentFile, link).canonicalPath
                                }
                                if (!resolved.startsWith(destCanon + File.separator)) {
                                    throw IOException("tar 符号链接越界：$name -> $link")
                                }
                                out.parentFile?.mkdirs()
                                out.delete()
                                Os.symlink(link, out.path)
                            }
                            e.isFile -> {
                                out.parentFile?.mkdirs()
                                // 先删再写：若同名符号链接已存在，直接写会穿透到链接目标
                                if (out.exists() || out.isDirectory) out.delete()
                                FileOutputStream(out).use { fos ->
                                    val buf = ByteArray(128 * 1024)
                                    var n = tar.read(buf)
                                    while (n > 0) {
                                        fos.write(buf, 0, n)
                                        n = tar.read(buf)
                                    }
                                }
                                if (e.mode and 64 != 0) out.setExecutable(true, true)
                            }
                        }
                        count++
                        if (count % 300 == 0) KimiState.status = "正在部署运行环境…($count)"
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }
}
