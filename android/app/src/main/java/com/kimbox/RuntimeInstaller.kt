package com.kimbox

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * 把 assets 里的 tar.gz 解到应用私有目录。
 * runtime.tar.gz -> filesDir/   (顶层是 usr/)
 * kimihome.tar.gz -> filesDir/home/  (顶层是 .kimi-code/)
 */
object RuntimeInstaller {

    // 每次 runtime.tar.gz 内容变化时 +1，触发已装机型的重解压
    private const val RUNTIME_VERSION = 1

    @Synchronized
    fun ensureInstalled(ctx: Context) {
        val marker = File(ctx.filesDir, "usr/.rt_version")
        val installed = try {
            if (marker.exists()) marker.readText().trim().toIntOrNull() else null
        } catch (_: Exception) { null }
        if (installed == RUNTIME_VERSION) return

        KimiState.status = "首次运行：正在部署运行环境…"
        extractTarGz(ctx, "runtime.pkg", ctx.filesDir)
        marker.parentFile?.mkdirs()
        marker.writeText("$RUNTIME_VERSION\n")
    }

    /** 初始配置只在缺失时写入，避免升级覆盖设备上已登录的状态 */
    @Synchronized
    fun ensureHome(ctx: Context) {
        val home = File(ctx.filesDir, "home")
        if (File(home, ".kimi-code/config.toml").exists()) return
        KimiState.status = "正在写入初始配置…"
        extractTarGz(ctx, "kimihome.pkg", home)
    }

    private fun extractTarGz(ctx: Context, asset: String, dest: File) {
        ctx.assets.open(asset).use { raw ->
            TarArchiveInputStream(GZIPInputStream(BufferedInputStream(raw, 128 * 1024))).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                var count = 0
                while (entry != null) {
                    val name = entry!!.name.removePrefix("./")
                    if (name.isNotEmpty() && !name.contains("..")) {
                        val out = File(dest, name)
                        when {
                            entry!!.isDirectory -> out.mkdirs()
                            entry!!.isSymbolicLink -> {
                                out.parentFile?.mkdirs()
                                out.delete()
                                Os.symlink(entry!!.linkName, out.path)
                            }
                            entry!!.isFile -> {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fos ->
                                    val buf = ByteArray(128 * 1024)
                                    var n = tar.read(buf)
                                    while (n > 0) {
                                        fos.write(buf, 0, n)
                                        n = tar.read(buf)
                                    }
                                }
                                if (entry!!.mode and 64 != 0) out.setExecutable(true, true)
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
