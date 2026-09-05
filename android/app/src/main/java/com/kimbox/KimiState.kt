package com.kimbox

/** 服务与界面之间共享的运行状态（单进程内，volatile 足够） */
object KimiState {
    @Volatile var status: String = "正在启动…"
    @Volatile var url: String? = null
    /** 当前引擎端口：WebView 只放行这个端口的 loopback 导航，其余一律交系统浏览器 */
    @Volatile var port: Int? = null
    @Volatile var lastError: String? = null
    @Volatile var running: Boolean = false
}
