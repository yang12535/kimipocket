package com.kimbox

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Update only app-owned hooks; never replace the user's installed runtime or home. */
object RuntimePatches {
    private val paths = listOf(
        "usr/libexec/kimbox-deb-rewrite.js",
        "usr/libexec/kimbox-deb-rewrite",
        "usr/etc/apt/apt.conf.d/99-kimbox"
    )

    fun install(context: Context) {
        try {
            installChecked(context)
        } catch (_: Exception) {
            // A small hook update must never trigger deleting the installed runtime/home.
            android.util.Log.w("kimbox", "Package hook update failed; will retry next start")
        }
    }

    private fun installChecked(context: Context) {
        val files = paths.associateWith { path -> context.assets.open("runtime-patches/$path").use { it.readBytes() } }
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (path, bytes) -> digest.update(path.toByteArray()); digest.update(bytes) }
        val version = digest.digest().joinToString("") { "%02x".format(it) }
        val prefix = File(context.filesDir, "usr").canonicalFile
        val marker = File(prefix, ".kimbox_hooks_version")
        if (marker.isFile && marker.readText().trim() == version && paths.all { File(context.filesDir, it).isFile }) return
        // Read and validate the whole set before changing anything. Keep originals for rollback.
        val originals = files.keys.associateWith { path ->
            val target = File(context.filesDir, path)
            require(target.canonicalPath.startsWith(prefix.path + "/")) { "运行时钩子路径不在应用目录内" }
            require(!target.exists() || target.isFile) { "运行时钩子不是普通文件" }
            if (target.isFile) target.readBytes() to (Os.stat(target.path).st_mode and 511) else null
        }
        for (path in files.keys) {
            val target = File(context.filesDir, path)
            target.parentFile!!.mkdirs()
            val backup = File(target.path + ".before-issue26")
            if (target.isFile && !backup.exists()) target.copyTo(backup)
        }
        val changed = mutableListOf<String>()
        try {
            for ((path, bytes) in files) {
                changed.add(path)
                write(File(context.filesDir, path), bytes, if (path.endsWith("kimbox-deb-rewrite")) 493 else 420)
            }
            write(marker, (version + "\n").toByteArray(), 384)
        } catch (error: Exception) {
            for (path in changed.asReversed()) {
                try {
                    val target = File(context.filesDir, path)
                    val old = originals[path]
                    if (old == null) target.delete() else write(target, old.first, old.second)
                } catch (rollback: Exception) { error.addSuppressed(rollback) }
            }
            // An interrupted/failed update is always retried before the next engine start.
            marker.delete()
            throw error
        }
    }

    private fun write(target: File, bytes: ByteArray, mode: Int) {
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            Os.fchmod(output.fd, mode)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
