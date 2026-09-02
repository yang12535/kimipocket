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
    private const val RUNTIME_VERSION = 1

    // 解压后约 350MB（usr/ ~233MB + 缓存增长余量），低于这个值宁可报错也别解一半
    private const val MIN_FREE_BYTES = 700L * 1024 * 1024

    @Synchronized
    fun ensureInstalled(ctx: Context) {
        val marker = File(ctx.filesDir, "usr/.rt_version")
        val installed = try {
            if (marker.exists()) marker.readText().trim().toIntOrNull() else null
        } catch (_: Exception) { null }
        if (installed == RUNTIME_VERSION) return

        val free = StatFs(ctx.filesDir.absolutePath).availableBytes
        if (free < MIN_FREE_BYTES) {
            throw IOException("存储空间不足：需要约 ${MIN_FREE_BYTES / 1024 / 1024}MB，当前可用 ${free / 1024 / 1024}MB")
        }

        KimiState.status = "首次运行：正在部署运行环境…"
        extractTarGz(ctx, "runtime.pkg", ctx.filesDir)
        marker.parentFile?.mkdirs()
        marker.writeText("$RUNTIME_VERSION\n")
    }

    /** 初始配置只在缺失时写入，避免升级覆盖设备上已登录的状态 */
    @Synchronized
    fun ensureHome(ctx: Context) {
        val home = File(ctx.filesDir, "home")
        val ready = File(home, ".home_ready")
        if (ready.exists()) return
        // 兼容旧版安装：已有配置说明初始化过，只补 marker，绝不重解压覆盖登录态
        if (File(home, ".kimi-code/config.toml").exists()) {
            home.mkdirs()
            ready.writeText("1\n")
            return
        }
        KimiState.status = "正在写入初始配置…"
        extractTarGz(ctx, "kimihome.pkg", home)
        ready.writeText("1\n")
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
