package com.kimbox

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 引擎 config.toml 里的供应商与模型：读出来给原生编辑界面用，改完再写回。
 * 引擎会监听 config.toml，写回后自动重新加载，不用重启。
 *
 * 对应引擎的配置结构：
 *   [providers.<id>]  type / base_url / api_key（内置订阅带 [providers.<id>.oauth] 子表）
 *   [models.<别名>]   provider / model / max_context_size / capabilities / display_name
 */
object Providers {

    /** 引擎接受的接口协议（与 web 端「API Protocol」一致） */
    val TYPES = listOf("openai", "openai_responses", "anthropic", "kimi")
    val TYPE_LABELS = mapOf(
        "openai" to "OpenAI 兼容",
        "openai_responses" to "Responses",
        "anthropic" to "Anthropic",
        "kimi" to "Kimi",
        "google-genai" to "Google",
        "vertexai" to "Vertex",
    )

    const val K256 = 262144
    const val K512 = 524288
    const val M1 = 1048576
    val CONTEXT_PRESETS = listOf(K256, K512, M1)

    enum class Thinking { OFF, TOGGLE, ALWAYS }

    class Model(
        val alias: String?,          // 已有模型的表名；新加的为 null，保存时生成
        var model: String,
        var displayName: String?,
        var maxContext: Int,
        var thinking: Thinking,
        var imageIn: Boolean,
        val otherCaps: List<String>,  // video_in、tool_use 等原样保留
    ) {
        fun capabilities(): List<String> {
            val out = mutableListOf<String>()
            if (thinking != Thinking.OFF) out.add("thinking")
            if (thinking == Thinking.ALWAYS) out.add("always_thinking")
            if (imageIn) out.add("image_in")
            out.addAll(otherCaps)
            return out
        }

        fun summary(): String {
            val parts = mutableListOf(model, contextLabel(maxContext))
            when (thinking) {
                Thinking.TOGGLE -> parts.add("思考")
                Thinking.ALWAYS -> parts.add("始终思考")
                Thinking.OFF -> {}
            }
            if (imageIn) parts.add("识图")
            return parts.joinToString(" · ")
        }

        companion object {
            fun newDefault(id: String) = Model(null, id, null, K256, Thinking.OFF, false, listOf("tool_use"))
        }
    }

    class Provider(
        val id: String,
        var type: String,
        var baseUrl: String?,
        val hasApiKey: Boolean,
        var newApiKey: String?,       // 非空才写入；留空 = 保持原值
        val managed: Boolean,         // 内置订阅（OAuth），只读
        val models: MutableList<Model>,
        val isNew: Boolean = false,
    ) {
        val removedAliases = mutableListOf<String>()
    }

    fun contextLabel(n: Int): String = when {
        n % M1 == 0 -> "${n / M1}M"
        n % 1024 == 0 -> "${n / 1024}K"
        n % 1000 == 0 -> "${n / 1000}k" // 上游按十进制给的长度（如 1000000）
        else -> n.toString()
    }

    /** 填进自定义输入框的写法：保证 parseContext(contextInput(n)) == n（十进制长度直接写整数） */
    fun contextInput(n: Int): String = when {
        n % M1 == 0 -> "${n / M1}m"
        n % 1024 == 0 -> "${n / 1024}k"
        else -> n.toString()
    }

    /** 「128k」「200000」「2m」「1.5m」→ token 数；k/m 按 1024 进位（与引擎内置配置一致） */
    fun parseContext(s: String): Int? {
        val t = s.trim().lowercase().replace(",", "").replace("_", "")
        val m = Regex("""^(\d+(?:\.\d+)?)\s*([km]?)$""").matchEntire(t) ?: return null
        val n = m.groupValues[1].toDouble()
        val v = when (m.groupValues[2]) { "k" -> n * 1024; "m" -> n * M1; else -> n }
        return v.toLong().takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    }

    fun configFile(home: File) = File(home, ".kimi-code/config.toml")

    @Suppress("UNCHECKED_CAST")
    fun load(cfg: ConfigToml): List<Provider> {
        val paths = cfg.tablePaths()
        val modelsByProvider = mutableMapOf<String, MutableList<Model>>()
        for (p in paths) {
            if (p.size != 2 || p[0] != "models") continue
            val provider = cfg.get(p, "provider") as? String ?: continue
            val caps = (cfg.get(p, "capabilities") as? List<Any>)?.map { it.toString() } ?: emptyList()
            val m = Model(
                alias = p[1],
                model = cfg.get(p, "model") as? String ?: p[1],
                displayName = cfg.get(p, "display_name") as? String,
                maxContext = (cfg.get(p, "max_context_size") as? Int) ?: K256,
                thinking = when {
                    "always_thinking" in caps -> Thinking.ALWAYS
                    "thinking" in caps -> Thinking.TOGGLE
                    else -> Thinking.OFF
                },
                imageIn = "image_in" in caps,
                otherCaps = caps.filter { it !in setOf("thinking", "always_thinking", "image_in") },
            )
            modelsByProvider.getOrPut(provider) { mutableListOf() }.add(m)
        }
        return paths.filter { it.size == 2 && it[0] == "providers" }.map { p ->
            val id = p[1]
            Provider(
                id = id,
                type = cfg.get(p, "type") as? String ?: "openai",
                baseUrl = cfg.get(p, "base_url") as? String,
                hasApiKey = !(cfg.get(p, "api_key") as? String).isNullOrEmpty(),
                newApiKey = null,
                managed = id.startsWith("managed:") || cfg.hasTable(p + "oauth"),
                models = modelsByProvider[id] ?: mutableListOf(),
            )
        }
    }

    /** 当前默认模型 / 副模型引用到的别名：这些模型不能删，删了引擎会加载失败 */
    fun referencedAliases(cfg: ConfigToml): Set<String> = setOfNotNull(
        cfg.get(emptyList(), "default_model") as? String,
        cfg.get(listOf("secondary_model"), "default_model") as? String,
        cfg.get(listOf("secondary_model"), "model") as? String,
    )

    fun save(cfg: ConfigToml, p: Provider) {
        val pp = listOf("providers", p.id)
        if (!p.managed) { // 内置订阅的接口与登录由引擎管理，只动它的模型
            cfg.set(pp, "type", p.type)
            cfg.set(pp, "base_url", p.baseUrl?.trim()?.trimEnd('/')?.ifEmpty { null })
            val key = p.newApiKey?.trim()
            if (!key.isNullOrEmpty()) cfg.set(pp, "api_key", key)
        }

        for (alias in p.removedAliases) cfg.removeTable(listOf("models", alias))
        val taken = cfg.tablePaths().filter { it.size == 2 && it[0] == "models" }.map { it[1] }.toMutableSet()
        for (m in p.models) {
            val alias = m.alias ?: uniqueAlias("${p.id}/${m.model}", taken).also { taken.add(it) }
            val mp = listOf("models", alias)
            cfg.set(mp, "provider", p.id)
            cfg.set(mp, "model", m.model)
            cfg.set(mp, "max_context_size", m.maxContext)
            cfg.set(mp, "capabilities", m.capabilities())
            cfg.set(mp, "display_name", m.displayName?.trim()?.ifEmpty { null })
        }
    }

    fun delete(cfg: ConfigToml, p: Provider) {
        for (m in p.models) m.alias?.let { cfg.removeTable(listOf("models", it)) }
        for (a in p.removedAliases) cfg.removeTable(listOf("models", a))
        cfg.removeTable(listOf("providers", p.id))
    }

    private fun uniqueAlias(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    /** 从上游拉到的模型信息；拿不到的字段为 null，由调用方用默认值 */
    class Remote(val id: String, val context: Int?, val image: Boolean?, val reasoning: Boolean?)

    /**
     * 拉上游模型列表（阻塞，别在主线程调）。OpenAI 系 GET {base}/models（Bearer），
     * Anthropic GET {base}/v1/models（x-api-key）。兼容 OpenRouter / New API 等常见扩展字段。
     */
    fun fetchRemote(type: String, baseUrl: String, apiKey: String): List<Remote> {
        val base = baseUrl.trim().trimEnd('/')
        val url = if (type == "anthropic" && !base.endsWith("/v1")) "$base/v1/models" else "$base/models"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (type == "anthropic") {
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
        } else if (apiKey.isNotEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw java.io.IOException("HTTP $code ${body.take(160)}")
        val trimmed = body.trimStart()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).let { it.optJSONArray("data") ?: it.optJSONArray("models") ?: JSONArray() }
        val out = mutableListOf<Remote>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifEmpty { o.optString("name").removePrefix("models/") }
            if (id.isEmpty()) continue
            val ctx = listOf("context_length", "context_window", "max_context_length", "max_input_tokens", "inputTokenLimit")
                .firstNotNullOfOrNull { k -> o.optLong(k, 0).takeIf { it > 0 } }
                ?: o.optJSONObject("top_provider")?.optLong("context_length", 0)?.takeIf { it > 0 }
            val inputs = o.optJSONObject("architecture")?.optJSONArray("input_modalities")
                ?: o.optJSONArray("input_modalities") ?: o.optJSONArray("modalities")
            val image = inputs?.let { a -> (0 until a.length()).any { a.optString(it) == "image" } }
            val params = o.optJSONArray("supported_parameters")
            val reasoning = params?.let { a -> (0 until a.length()).any { a.optString(it).contains("reasoning") } }
                ?: if (o.has("thinking")) o.optBoolean("thinking") else null
            out.add(Remote(id, ctx?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(), image, reasoning))
        }
        return out.sortedBy { it.id }
    }
}
