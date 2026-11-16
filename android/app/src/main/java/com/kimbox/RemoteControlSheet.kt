package com.kimbox

import android.app.Activity
import android.graphics.Color
import com.kimbox.Ui.dp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject

class RemoteControlSheet(private val activity: Activity, private val palette: () -> Ui.Palette) {
    private var busy = false
    private var selfDeviceId = ""

    fun show() {
        val port = KimiState.port
        if (port == null || !KimiState.running) {
            toast("请等待本机引擎启动完成")
            return
        }
        if (busy) return
        busy = true
        val loading = Ui.Sheet(activity, palette(), "远程控制").apply {
            add(android.widget.LinearLayout(activity).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, activity.dp(12), 0, activity.dp(20))
                addView(android.widget.ProgressBar(activity).apply {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(palette().sub)
                }, android.widget.LinearLayout.LayoutParams(activity.dp(22), activity.dp(22)))
                addView(android.widget.TextView(activity).apply {
                    text = "正在读取连接状态与设备…"
                    textSize = 14f
                    setTextColor(palette().sub)
                    setPadding(activity.dp(12), 0, 0, 0)
                })
            })
        }
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
                    val showResult = loading.dialog.isShowing && !activity.isFinishing && !activity.isDestroyed
                    loading.dismiss()
                    if (!showResult) return@runOnUiThread
                    val p = palette()
                    val sheet = Ui.Sheet(activity, p, "远程控制", "同一 Kimi 账号下的设备可互相控制（需会员资格）")
                    sheet.maxBodyHeight(0.72f)
                    val enabled = status.optBoolean("enabled")
                    val stateKey = status.optString("state")
                    val (stateLabel, stateTone) = when (stateKey) {
                        "on" -> "已开启" to p.ok
                        "starting" -> "连接中" to p.sub
                        "stopping" -> "关闭中" to p.sub
                        else -> if (status.has("error")) "连接失败" to p.danger else "未开启" to p.sub
                    }
                    val stateHint = when (stateKey) {
                        "on" -> "其他设备现在可以控制本机"
                        "starting", "stopping" -> "请稍候，完成后点刷新"
                        else -> if (status.has("error")) "可重试，或检查 Kimi 登录与网络" else "开启后，同账号的其他设备可以控制本机"
                    }

                    // 本机卡片：状态 + 主操作按钮
                    val hero = android.widget.LinearLayout(activity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(16))
                        addView(android.widget.LinearLayout(activity).apply {
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            addView(android.widget.TextView(activity).apply {
                                text = "本机"
                                textSize = 16f
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                setTextColor(p.text)
                            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                            addView(Ui.pill(activity, p, stateLabel, stateTone))
                        })
                        addView(android.widget.TextView(activity).apply {
                            text = stateHint
                            textSize = 13f
                            setTextColor(p.sub)
                            setPadding(0, activity.dp(4), 0, activity.dp(12))
                        })
                        val toggleBtn = Ui.button(activity, p, if (enabled) "关闭远程控制" else "开启远程控制", primary = !enabled) {
                            sheet.dismiss(); toggle(port, !enabled)
                        }
                        if (enabled) toggleBtn.background = Ui.rounded(if (p.dark) Color.rgb(58, 58, 64) else Color.WHITE, activity.dp(12).toFloat())
                        if (RemoteControlActivity.validEntry(ownUrl)) {
                            addView(Ui.buttonRow(activity, toggleBtn,
                                Ui.button(activity, p, "打开本机页面", primary = enabled) { sheet.dismiss(); open(ownUrl) }))
                        } else addView(toggleBtn)
                    }
                    sheet.add(Ui.card(activity, p, hero), topMarginDp = 2)

                    // 其他设备：在线优先，离线置灰；同名设备补 ID 尾号区分
                    val selfId = status.optString("device_id")
                    selfDeviceId = selfId
                    val others = mutableListOf<JSONObject>()
                    if (devices != null) for (i in 0 until devices.length()) {
                        val d = devices.optJSONObject(i) ?: continue
                        val id = d.optString("device_id")
                        if (id.isBlank() || id == selfId) continue
                        others += d
                    }
                    others.sortBy { if (it.optString("status") == "online") 0 else 1 }
                    val nameCount = others.groupingBy { it.optString("alias").ifBlank { "Kimi 设备" } }.eachCount()
                    val onlineCount = others.count { it.optString("status") == "online" }

                    if (origin == null) {
                        sheet.group("其他设备", Ui.row(activity, p, "!", "暂时无法确定账号地区",
                            "请检查 Kimi 登录与网络后刷新", inCard = true) { sheet.dismiss(); show() })
                    } else if (others.isEmpty()) {
                        sheet.group("其他设备", Ui.row(activity, p, "▣", "还没有其他设备",
                            "在电脑上运行 kimi rc 后，这里会出现", inCard = true, trailing = null) {})
                    } else {
                        val rows = others.map { device ->
                            val id = device.optString("device_id")
                            val name = device.optString("alias").ifBlank { "Kimi 设备" }
                            val online = device.optString("status") == "online"
                            val title = if ((nameCount[name] ?: 0) > 1) "$name · ${id.takeLast(4)}" else name
                            Ui.row(activity, p, "▣", title,
                                if (online) "点击打开控制页" else "需先在该设备运行 kimi rc",
                                inCard = true, dim = !online,
                                trailingView = Ui.pill(activity, p, if (online) "在线" else "离线", if (online) p.ok else p.sub)) {
                                if (online) { sheet.dismiss(); open("$origin/devices/${Uri.encode(id)}/?rc=1&from=kimi_code_cli") }
                                else toast("请先在目标设备开启远程控制")
                            }
                        }
                        sheet.group("其他设备 · $onlineCount/${others.size} 在线", *rows.toTypedArray())
                    }

                    val refresh = Ui.button(activity, p, "↻ 刷新", primary = false) { sheet.dismiss(); show() }
                    if (origin != null) {
                        sheet.addFooter(Ui.buttonRow(activity, refresh,
                            Ui.button(activity, p, "全部设备页", primary = false) { sheet.dismiss(); open("$origin/") }))
                    } else sheet.addFooter(refresh)
                    sheet.add(Ui.footnote(activity, p, "设备页在内置页面打开，登录授权会跳转外部浏览器"), topMarginDp = 12)
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
                .putExtra("rename_device_id", selfDeviceId)
                .putExtra("device_alias", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
        try { activity.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { toast("没有可用的浏览器") }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
