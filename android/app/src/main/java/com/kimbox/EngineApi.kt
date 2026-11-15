package com.kimbox

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Only the engine whose process/banner was verified by KimiService receives this token. */
class EngineApi(private val context: Context, val port: Int) {
    fun token(): String = File(context.filesDir, "home/.kimi-code/server.token").readText().trim()

    fun request(path: String, body: JSONObject? = null): JSONObject {
        check(KimiState.running && KimiState.port == port) { "引擎未就绪，请稍后重试" }
        require(path.startsWith("/api/v1/"))
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer ${token()}")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (connection.responseCode != 200) throw IOException("引擎请求失败（HTTP ${connection.responseCode}）")
            val bytes = connection.inputStream.use { it.readBytesLimited(4 * 1024 * 1024) }
            val result = JSONObject(bytes.toString(Charsets.UTF_8))
            if (result.optInt("code", -1) != 0) {
                // Upstream errors may contain URLs/tokens. Keep those out of Android UI/logs.
                throw IOException("引擎操作失败（${result.optInt("code")}）；请检查 Kimi 登录、会员资格与网络")
            }
            return result.optJSONObject("data") ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > limit) throw IOException("引擎响应过大")
        out.write(buffer, 0, n)
    }
}
