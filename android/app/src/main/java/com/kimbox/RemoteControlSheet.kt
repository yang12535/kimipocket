package com.kimbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject

class RemoteControlSheet(private val activity: Activity, private val palette: () -> Ui.Palette) {
    private var busy = false

    fun show() {
        val port = KimiState.port
        if (port == null || !KimiState.running) {
            toast("请等待本机引擎启动完成")
            return
        }
        if (busy) return
        busy = true
        val loading = AlertDialog.Builder(activity).setMessage("正在读取远程控制状态…").create()
        loading.show()
        Thread {
            try {
                val api = EngineApi(activity.applicationContext, port)
                val status = api.request("/api/v1/remote-control")
                val region = try { api.request("/api/v1/oauth/region").optString("region") } catch (_: Exception) { "" }
                val ownUrl = status.optString("url")
                val origin = if (RemoteControlActivity.validEntry(ownUrl)) "https://${Uri.parse(ownUrl).host}"
                    else when (region) {
                        "global" -> "https://code-rc.kimi.ai"
                        "mainland-cn" -> "https://code-rc.kimi.com"
                        else -> null
                    }
                val devices = try { api.request("/api/v1/remote-control/devices").optJSONArray("devices") } catch (_: Exception) { null }
                activity.runOnUiThread {
                    busy = false
                    val showResult = loading.isShowing && !activity.isFinishing && !activity.isDestroyed
                    loading.dismiss()
                    if (!showResult) return@runOnUiThread
                    val sheet = Ui.Sheet(activity, palette(), "远程控制", "使用同一 Kimi 账号；控制功能需账号具备相应会员资格")
                    val enabled = status.optBoolean("enabled")
                    val state = when (status.optString("state")) {
                        "on" -> "已开启，其他设备可以控制本机"
                        "starting" -> "正在连接…"
                        "stopping" -> "正在关闭…"
                        else -> if (status.has("error")) "连接失败，可重试或检查登录与网络" else "未开启"
                    }
                    sheet.add(Ui.row(activity, palette(), "⇄", "让其他设备控制本机", state,
                        trailing = if (enabled) "关闭" else "开启") {
                        sheet.dismiss()
                        toggle(port, !enabled)
                    })
                    if (RemoteControlActivity.validEntry(ownUrl)) {
                        sheet.add(Ui.row(activity, palette(), "↗", "打开本机远程页面", "可用同一账号在其他设备访问") {
                            sheet.dismiss(); open(ownUrl)
                        })
                    }
                    sheet.sectionLabel("控制其他设备")
                    if (devices != null && origin != null) for (i in 0 until devices.length()) {
                        val device = devices.optJSONObject(i) ?: continue
                        val id = device.optString("device_id")
                        if (id == status.optString("device_id") || id.isBlank()) continue
                        val online = device.optString("status") == "online"
                        sheet.add(Ui.row(activity, palette(), "▣", device.optString("alias").ifBlank { "Kimi 设备" },
                            if (online) "在线 · 点此控制" else "离线 · 需先在该设备开启 kimi rc") {
                            if (online) { sheet.dismiss(); open("$origin/devices/${Uri.encode(id)}/?rc=1&from=kimi_code_cli") }
                            else toast("请先在目标设备开启远程控制")
                        })
                    }
                    if (origin != null) sheet.add(Ui.row(activity, palette(), "◎", "打开远程设备页面", "设备页在内置页面运行，仅 OAuth 跳转外部浏览器") {
                        sheet.dismiss(); open("$origin/")
                    })
                    else sheet.add(Ui.row(activity, palette(), "!", "暂时无法确定账号地区", "请检查 Kimi 登录与网络后刷新") { sheet.dismiss(); show() })
                    sheet.add(Ui.row(activity, palette(), "↻", "刷新状态", "重新获取设备和连接状态") { sheet.dismiss(); show() })
                    sheet.show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    busy = false
                    loading.dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed) toast(e.message ?: "无法读取远程控制状态")
                }
            }
        }.start()
    }

    private fun toggle(port: Int, enabled: Boolean) {
        if (busy) return
        busy = true
        toast(if (enabled) "正在连接远程控制…" else "正在关闭远程控制…")
        Thread {
            val result = try {
                EngineApi(activity.applicationContext, port).request("/api/v1/remote-control", JSONObject().put("enabled", enabled))
                null
            } catch (e: Exception) { e.message ?: "操作失败" }
            activity.runOnUiThread {
                busy = false
                if (!activity.isFinishing && !activity.isDestroyed) {
                    result?.let { toast(it) }
                    show()
                }
            }
        }.start()
    }

    private fun open(url: String) {
        if (!RemoteControlActivity.validEntry(url)) return
        // Android 8.x cannot give two WebView processes independent data directories.
        val intent = if (android.os.Build.VERSION.SDK_INT < 28) Intent(Intent.ACTION_VIEW, Uri.parse(url))
            else Intent(activity, RemoteControlActivity::class.java).putExtra("url", url)
                .putExtra("dark", palette().dark)
        try { activity.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { toast("没有可用的浏览器") }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
