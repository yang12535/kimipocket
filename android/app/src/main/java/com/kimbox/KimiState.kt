package com.kimbox

/** 服务与界面之间共享的运行状态（单进程内，volatile 足够） */
object KimiState {
    @Volatile var status: String = "正在启动…"
    @Volatile var url: String? = null
    @Volatile var lastError: String? = null
    @Volatile var running: Boolean = false
}
