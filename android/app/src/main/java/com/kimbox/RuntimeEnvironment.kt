package com.kimbox

import android.content.Context
import java.io.File

/**
 * 终端等原生辅助进程共用的运行时环境。与 KimiService 启动引擎时保持一致：
 * HOME 在应用私有目录，工作目录在外部 workspace，PREFIX 指向解压出来的 usr/。
 */
object RuntimeEnvironment {

    const val TERM = "xterm-256color"

    fun prefix(ctx: Context): File = File(ctx.filesDir, "usr")

    fun home(ctx: Context): File = File(ctx.filesDir, "home")

    fun workspace(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "workspace")

    fun tmp(ctx: Context): File = File(prefix(ctx), "tmp")

    fun prepare(ctx: Context) {
        home(ctx).mkdirs()
        tmp(ctx).mkdirs()
        workspace(ctx).mkdirs()
    }

    /** 终端依赖的几个可执行文件是否都已就位（运行时尚未部署完时不要硬起） */
    fun ready(ctx: Context): Boolean {
        val bin = File(prefix(ctx), "bin")
        return listOf("script", "setsid", "bash", "stty").all { File(bin, it).canExecute() }
    }

    fun apply(pb: ProcessBuilder, ctx: Context) {
        val prefix = prefix(ctx)
        val env = pb.environment()
        env["PREFIX"] = prefix.path
        env["HOME"] = home(ctx).path
        env["PATH"] = "${prefix.path}/bin:/system/bin:/system/xbin"
        env["LD_LIBRARY_PATH"] = "${prefix.path}/lib"
        // termux-exec：与引擎相同，保证 shebang / 子进程能正常加载
        env["LD_PRELOAD"] = "${prefix.path}/lib/libtermux-exec.so"
        env["TMPDIR"] = tmp(ctx).path
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["TERM"] = TERM
        env["COLORTERM"] = "truecolor"
        env["SHELL"] = "${prefix.path}/bin/bash"
        pb.directory(workspace(ctx))
    }
}
