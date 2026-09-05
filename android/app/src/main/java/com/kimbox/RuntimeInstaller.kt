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
    private const val RUNTIME_VERSION = 6

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

        if (installed != null && usr.isDirectory) {
            // 版本升级 = 清空后全新解压。覆盖式解压会在 目录↔文件 翻转、agent 留下的外部
            // 符号链接上抛异常（每次启动同一位置炸死），也会和 agent 自升级的 npm 树
            // 混出「缝合怪」引擎（新版残留文件 + 打包旧文件）
            KimiState.status = "运行环境升级：正在清理旧环境…"
            deleteRecursivelyNoFollow(usr)
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
            deleteRecursivelyNoFollow(usr)
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

    /** 老装机补偿：记忆模块的注入文件缺失时单独补种（不整包重解压，不覆盖已存在的文件） */
    @Synchronized
    fun ensureAgentsMd(ctx: Context) {
        val seeds = listOf(
            ".kimi-code/AGENTS.md",
            ".kimi-code/skills/kimipocket-update/SKILL.md",
        )
        for (name in seeds) {
            val target = File(ctx.filesDir, "home/$name")
            if (target.exists()) continue
            val data = readAssetEntry(ctx, "kimihome.pkg", name) ?: continue
            target.parentFile?.mkdirs()
            target.writeBytes(data)
            android.util.Log.i("kimbox", "seeded home/$name")
        }
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
