package com.kimbox

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 不跟随符号链接的递归删除。
 *
 * Kotlin 的 File.deleteRecursively() 会遍历目录符号链接的目标：usr/ 里若有指向
 * home/ 或工作目录的软链（npm link 之类），链接目标里的用户文件会被一起删掉。
 * 这里用 walkFileTree（默认不 FOLLOW_LINKS），符号链接只删链接本身。
 *
 * 尽力而为语义：deleteIfExists 返回 false 只说明条目已不在，不算失败；
 * 单条删除抛异常不中断遍历（避免留下半删的树），最后汇总成返回值。
 */
internal fun deleteRecursivelyNoFollow(root: File): Boolean {
    val path = root.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
    var ok = true
    try {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    Files.deleteIfExists(file)
                } catch (_: Throwable) {
                    ok = false
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                ok = false
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    ok = false
                } else {
                    try {
                        Files.deleteIfExists(dir)
                    } catch (_: Throwable) {
                        ok = false
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
    } catch (_: Throwable) {
        ok = false
    }
    return ok
}
