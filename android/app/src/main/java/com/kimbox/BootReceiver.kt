package com.kimbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                context.startForegroundService(Intent(context, KimiService::class.java))
            } catch (_: Throwable) {
                // Android 12+ 对后台启动 FGS 有限制，失败就等用户手动打开
            }
        }
    }
}
