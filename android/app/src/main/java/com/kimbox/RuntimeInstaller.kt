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
    // 通过比对设备文件哈希与上次种子哈希判断用户是否改过：
    // - 哈希匹配 → 用户未改 → 安全覆盖
    // - 哈希不匹配 → 用户改过 → 跳过并记日志
    private const val SEED_VERSION = 3

    // 解压后约 350MB（usr/ ~233MB + 缓存增长余量），低于这个值宁可报错也别解一半
    private const val MIN_FREE_BYTES = 700L * 1024 * 1024

    @Synchronized
    fun ensureInstalled(ctx: Context) {
        val usr = File(ctx.filesDir, "usr")
        val marker = File(usr, ".rt_version")
        val installed = try {
            if (marker.exists()) marker.readText().trim().toIntOrNull() else null
        } catch (_: Exception) { null }
        if (installed == RUNTIME_VERSION) return

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
    }

    /**
     * 老装机补偿 + 版本更新：记忆模块的注入文件缺失时单独补种（不整包重解压）。
     * 当 SEED_VERSION 增加时，覆盖用户未修改过的旧种子（通过哈希比对判断）：
     * - 文件不存在 → 写入新种子
     * - 文件存在且哈希匹配上次种子 → 用户未改 → 覆盖为新版本
     * - 文件存在但哈希不匹配 → 用户改过 → 跳过并记日志
     *
     * 首次迁移（无哈希文件）：不覆盖现有文件（安全默认），但记录当前哈希作为基线，
     * 后续版本更新可正确检测用户修改。
     */
    @Synchronized
    fun ensureAgentsMd(ctx: Context) {
        val seeds = listOf(
            ".kimi-code/AGENTS.md",
            ".kimi-code/skills/kimipocket-update/SKILL.md",
        )

        // 检查种子版本是否需要更新
        val versionFile = File(ctx.filesDir, "home/.kimi-code/.seed_version")
        val installedVersion = try {
            if (versionFile.exists()) versionFile.readText().trim().toIntOrNull() else null
        } catch (_: Exception) { null }
        val needsUpdate = installedVersion != SEED_VERSION

        for (name in seeds) {
            val target = File(ctx.filesDir, "home/$name")
            val data = readAssetEntry(ctx, "kimihome.pkg", name) ?: continue
            val hashFile = File(target.parentFile, "${target.name}.seedhash")
            val lastSeedHash = try {
                if (hashFile.exists()) hashFile.readText().trim() else null
            } catch (_: Exception) { null }

            if (!target.exists()) {
                // 文件缺失：始终补种
                target.parentFile?.mkdirs()
                target.writeBytes(data)
                hashFile.writeText(sha256hex(data))
                android.util.Log.i("kimbox", "seeded home/$name (missing)")
            } else if (needsUpdate) {
                // 版本变更：检查用户是否修改过
                val currentHash = sha256hex(target.readBytes())
                if (lastSeedHash != null && currentHash == lastSeedHash) {
                    // 哈希匹配 → 用户未改 → 安全覆盖
                    target.writeBytes(data)
                    hashFile.writeText(sha256hex(data))
                    android.util.Log.i("kimbox", "updated seed home/$name (v$SEED_VERSION)")
                } else {
                    // 哈希不匹配或首次迁移（lastSeedHash=null）→ 用户可能改过 → 跳过
                    // 但记录当前哈希作为基线，下次版本更新时可正确检测
                    if (lastSeedHash == null) {
                        hashFile.writeText(currentHash)
                        android.util.Log.i("kimbox", "baseline hash recorded for home/$name (first migration)")
                    } else {
                        android.util.Log.i("kimbox", "skipping seed home/$name: user-customized")
                    }
                }
            }
            // else: 版本相同且文件存在 → 不动
        }

        // 写入版本标记
        if (needsUpdate) {
            versionFile.parentFile?.mkdirs()
            versionFile.writeText("$SEED_VERSION\n")
        }
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
