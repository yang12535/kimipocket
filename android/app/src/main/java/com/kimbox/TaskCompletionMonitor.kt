package com.kimbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A service-owned connection survives putting the WebView in the background. */
class TaskCompletionMonitor(private val context: Context, private val port: Int) {
    private val tracker = CompletionTracker()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private val connectionLock = Any()
    private var retryPending = false
    private var socket: WebSocket? = null
    private var retrySeconds = 1L

    fun start() {
        ensureChannel(context)
        executor.execute { connect() }
    }

    private fun connect() {
        if (closed || !KimiState.running || KimiState.port != port) return
        val current = synchronized(connectionLock) {
            if (closed) return
            retryPending = false
            socket?.cancel()
            socket = null
            ++generation
        }
        try {
            val token = EngineApi(context, port).token()
            val next = client.newWebSocket(Request.Builder()
                .url("ws://127.0.0.1:$port/api/v1/ws")
                .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closed || current != generation) { webSocket.cancel(); return }
                    synchronized(connectionLock) { retrySeconds = 1 }
                    // Protocol 2 (bundled Kimi Code 2.4.1): global events arrive on every connection.
                    webSocket.send(JSONObject().put("type", "client_hello").put("id", "kimbox-notifications")
                        .put("payload", JSONObject().put("client_id", "kimbox-notifications")
                            .put("subscriptions", JSONArray())).toString())
                    submit {
                        try {
                            val beforeRequest = tracker.revision()
                            val items = EngineApi(context, port).request("/api/v1/sessions?page_size=100").optJSONArray("items")
                            for (i in 0 until (items?.length() ?: 0)) {
                                if (closed || current != generation) return@submit
                                val item = items!!.getJSONObject(i)
                                if (!item.has("main_turn_active") || item.isNull("main_turn_active")) continue
                                val id = item.optString("id")
                                tracker.reconcile(id, item.optBoolean("main_turn_active"), item.optString("last_turn_reason"), beforeRequest)
                                    ?.let { notifyFinished(id, it) }
                            }
                        } catch (_: Exception) { /* Live events still work if snapshot is unavailable. */ }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (closed || current != generation || text.length > 256 * 1024) return
                    try {
                        val event = JSONObject(text)
                        val payload = event.optJSONObject("payload") ?: return
                        if (event.optString("type") == "ping") {
                            webSocket.send(JSONObject().put("type", "pong").put("payload", payload).toString())
                        } else if (event.optString("type") == "event.session.work_changed") {
                            val id = event.optString("session_id")
                            if (!payload.has("main_turn_active") || payload.isNull("main_turn_active")) return
                            tracker.observe(id, payload.optBoolean("main_turn_active"), payload.optString("last_turn_reason"))
                                ?.let { reason -> notifyFinished(id, reason) }
                        }
                    } catch (_: Exception) { /* No raw server frames or credentials in logs. */ }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    retry(current)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retry(current) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry(current) }
            })
            synchronized(connectionLock) {
                if (closed || current != generation) next.cancel() else socket = next
            }
        } catch (_: Exception) { retry(current) }
    }

    private fun submit(task: () -> Unit) {
        if (!closed) try { executor.execute { if (!closed) task() } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun retry(current: Long) = synchronized(connectionLock) {
        if (closed || current != generation || retryPending) return@synchronized
        retryPending = true
        try {
            executor.schedule({ connect() }, retrySeconds, TimeUnit.SECONDS)
            retrySeconds = (retrySeconds * 2).coerceAtMost(30)
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun notifyFinished(id: String, reason: String) {
        if (closed) return
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_SESSION)
            .setData(Uri.parse("kimbox://session/$id"))
            .putExtra(EXTRA_SESSION, id)
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (reason == "completed") "Kimi 任务已完成" else "Kimi 任务未能完成")
            .setContentText("点此返回对应会话查看结果")
            .setContentIntent(pending).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_MESSAGE).build()
        // Stable tag prevents hash collisions between sessions and keeps one result per conversation.
        nm.notify(id, 2, notification)
    }

    fun close() {
        synchronized(connectionLock) {
            closed = true
            ++generation
            socket?.cancel()
            socket = null
        }
        executor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        // Android cannot raise an existing channel's importance. Migrate the old DEFAULT
        // task-results channel once; later user changes to this new channel remain respected.
        const val CHANNEL = "kimi_task_messages"
        const val ACTION_OPEN_SESSION = "com.kimbox.action.OPEN_SESSION"
        const val EXTRA_SESSION = "session_id"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "任务消息", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "任务完成或失败时提醒；声音、振动和悬浮通知可在此单独设置"
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
