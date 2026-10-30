package com.kimbox

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.kimbox.Ui.dp
import java.io.File

/**
 * 「供应商与模型」原生编辑器：直接读写引擎的 config.toml（引擎自己会热重载）。
 * 网页端的供应商管理没有思考/识图开关、上下文只能手填数字，这里补齐，并支持从上游拉模型列表。
 */
class ProviderSheets(
    private val a: Activity,
    private val palette: () -> Ui.Palette,
    home: File,
) {
    private val file = Providers.configFile(home)
    private val handler = Handler(Looper.getMainLooper())

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(a, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun readConfig(): ConfigToml? = try {
        ConfigToml.parse(if (file.isFile) file.readText() else "")
    } catch (e: Exception) {
        toast("读取配置失败：${e.message}", long = true); null
    }

    /** 写回前先留一份上一版（config.toml.kimbox-bak），配置被改坏时可以手动恢复 */
    private fun writeConfig(cfg: ConfigToml): Boolean = try {
        if (file.isFile) file.copyTo(File(file.parentFile, "config.toml.kimbox-bak"), overwrite = true)
        file.parentFile?.mkdirs()
        file.writeText(cfg.toText())
        true
    } catch (e: Exception) {
        toast("保存失败：${e.message}", long = true); false
    }

    // ── 列表 ─────────────────────────────────────────────

    fun showList() {
        val cfg = readConfig() ?: return
        val p = palette()
        val providers = Providers.load(cfg)
        val sheet = Ui.Sheet(a, p, "供应商与模型", "保存后引擎自动生效，在聊天页的模型列表里选用")
        if (providers.isEmpty()) {
            sheet.add(TextView(a).apply {
                text = "还没有供应商"
                setTextColor(p.sub)
                setPadding(a.dp(4), a.dp(8), 0, a.dp(8))
            })
        }
        for (pr in providers) {
            val label = Providers.TYPE_LABELS[pr.type] ?: pr.type
            val sub = buildString {
                append(label).append(" · ").append(pr.models.size).append(" 个模型")
                if (pr.managed) append(" · 内置订阅")
                else pr.baseUrl?.let { append(" · ").append(it.removePrefix("https://").removePrefix("http://")) }
            }
            sheet.add(Ui.row(a, p, pr.id.first().uppercase(), pr.id, sub) {
                sheet.dismiss(); showProvider(pr)
            })
        }
        sheet.maxBodyHeight(0.6f)
        sheet.addFooter(Ui.buttonRow(a,
            Ui.button(a, p, "关闭", primary = false) { sheet.dismiss() },
            Ui.button(a, p, "添加供应商", primary = true) {
                sheet.dismiss()
                showProvider(Providers.Provider("", "openai", null, false, null, false, mutableListOf(), isNew = true))
            }))
        sheet.show()
    }

    // ── 供应商 ───────────────────────────────────────────

    private fun showProvider(pr: Providers.Provider) {
        val p = palette()
        val label = Providers.TYPE_LABELS[pr.type] ?: pr.type
        val sheet = Ui.Sheet(a, p,
            if (pr.isNew) "添加供应商" else pr.id,
            when {
                pr.isNew -> "填好接口信息，再从上游拉模型或手动添加"
                pr.managed -> "内置订阅：接口与登录由引擎管理，这里只能改模型设置"
                else -> "$label · ${pr.models.size} 个模型"
            })

        var idEdit: android.widget.EditText? = null
        var urlEdit: android.widget.EditText? = null
        var keyEdit: android.widget.EditText? = null
        if (!pr.managed) {
            if (pr.isNew) {
                val (v, e) = Ui.field(a, p, "名称", pr.id, "例如 newapi（字母、数字、- 和 _）")
                sheet.add(v, topMarginDp = 4); idEdit = e
            }
            sheet.sectionLabel("接口协议", topMarginDp = 12)
            val types = Providers.TYPES.toMutableList().also { if (pr.type !in it) it.add(pr.type) }
            sheet.add(Ui.segmented(a, p, types.map { Providers.TYPE_LABELS[it] ?: it }, types.indexOf(pr.type)) {
                pr.type = types[it]
            })
            val (uv, ue) = Ui.field(a, p, "Base URL", pr.baseUrl, "https://api.example.com/v1")
            sheet.add(uv, topMarginDp = 12); urlEdit = ue
            val (kv, ke) = Ui.field(a, p, "API Key", pr.newApiKey,
                if (pr.hasApiKey) "已设置，留空保持不变" else "sk-…", password = true)
            sheet.add(kv, topMarginDp = 12); keyEdit = ke
        }

        val title = TextView(a)
        val models = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun renderModels() {
            title.text = "模型（${pr.models.size}）"
            models.removeAllViews()
            if (pr.models.isEmpty()) {
                models.addView(TextView(a).apply {
                    text = "还没有模型"
                    setTextColor(p.sub)
                    setPadding(a.dp(4), a.dp(6), 0, a.dp(6))
                })
            }
            for (m in pr.models) {
                models.addView(Ui.row(a, p, "◇", m.displayName?.ifEmpty { null } ?: m.model, m.summary()) {
                    showModel(m, canDelete = true) { deleted ->
                        if (deleted) {
                            pr.models.remove(m)
                            m.alias?.let { pr.removedAliases.add(it) }
                        }
                        renderModels()
                    }
                })
            }
        }
        title.apply { textSize = 12f; setTextColor(p.sub); setPadding(a.dp(4), 0, 0, a.dp(6)) }
        sheet.add(title, topMarginDp = 16)
        sheet.add(models)
        renderModels()

        val addManual = Ui.button(a, p, "手动添加", primary = false) {
            val m = Providers.Model.newDefault("")
            showModel(m, canDelete = false) { _ ->
                if (m.model.isNotBlank()) { pr.models.add(m); renderModels() }
            }
        }
        if (pr.managed) {
            sheet.add(addManual, topMarginDp = 8)
        } else {
            sheet.add(Ui.buttonRow(a,
                Ui.button(a, p, "从上游获取", primary = false) {
                    fetchUpstream(pr, urlEdit!!.text.toString(), keyEdit!!.text.toString()) { renderModels() }
                },
                addManual), topMarginDp = 8)
        }
        sheet.maxBodyHeight(0.62f)

        val save = Ui.button(a, p, "保存", primary = true) {
            val id = if (pr.isNew) idEdit!!.text.toString().trim() else pr.id
            if (!pr.managed) {
                pr.baseUrl = urlEdit!!.text.toString().trim()
                pr.newApiKey = keyEdit!!.text.toString().trim().ifEmpty { null }
            }
            if (saveProvider(pr, id)) {
                sheet.dismiss(); showList()
            }
        }
        if (!pr.isNew && !pr.managed) {
            sheet.addFooter(Ui.buttonRow(a,
                Ui.button(a, p, "删除", primary = false) { confirmDelete(pr) { sheet.dismiss(); showList() } },
                save))
        } else {
            sheet.addFooter(Ui.buttonRow(a,
                Ui.button(a, p, "取消", primary = false) { sheet.dismiss(); showList() },
                save))
        }
        sheet.show()
    }

    private fun saveProvider(pr: Providers.Provider, id: String): Boolean {
        if (pr.isNew) {
            if (!Regex("^[A-Za-z0-9_-]+$").matches(id)) { toast("名称只能用字母、数字、- 和 _"); return false }
            if (id == "kimi-code" || id.startsWith("managed")) { toast("这个名称是内置保留的，换一个"); return false }
        }
        if (!pr.managed) {
            val url = pr.baseUrl.orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                toast("Base URL 要以 https:// 开头"); return false
            }
        }
        if (pr.models.any { it.model.isBlank() }) { toast("有模型没填 ID"); return false }
        val cfg = readConfig() ?: return false
        if (pr.isNew && cfg.hasTable(listOf("providers", id))) { toast("已经有叫 $id 的供应商了"); return false }
        val inUse = Providers.referencedAliases(cfg).intersect(pr.removedAliases.toSet())
        if (inUse.isNotEmpty()) {
            toast("${inUse.first()} 正被设为默认模型，先在聊天页换个模型再删", long = true); return false
        }
        val target = if (pr.isNew) Providers.Provider(id, pr.type, pr.baseUrl, false, pr.newApiKey, false,
            pr.models, isNew = true) else pr
        Providers.save(cfg, target)
        if (!writeConfig(cfg)) return false
        toast("已保存，引擎会自动重新加载")
        return true
    }

    private fun confirmDelete(pr: Providers.Provider, after: () -> Unit) {
        val cfg = readConfig() ?: return
        val inUse = Providers.referencedAliases(cfg).intersect(pr.models.mapNotNull { it.alias }.toSet())
        if (inUse.isNotEmpty()) {
            toast("${inUse.first()} 正被设为默认模型，先在聊天页换个模型再删", long = true); return
        }
        Ui.confirm(a, palette(), "删除 ${pr.id}？", "会连同它的 ${pr.models.size} 个模型一起从配置里删掉。", "删除") {
            val fresh = readConfig() ?: return@confirm
            Providers.delete(fresh, pr)
            if (writeConfig(fresh)) { toast("已删除"); after() }
        }
    }

    // ── 模型 ─────────────────────────────────────────────

    /** 编辑一个模型（改的是传入对象本身）；done(true) 表示用户点了删除 */
    private fun showModel(m: Providers.Model, canDelete: Boolean, done: (Boolean) -> Unit) {
        val p = palette()
        val isNew = m.model.isBlank()
        val sheet = Ui.Sheet(a, p, if (isNew) "添加模型" else (m.displayName?.ifEmpty { null } ?: m.model),
            if (isNew) "填上游的模型 ID，其余按需要改" else m.alias)

        val (iv, idEdit) = Ui.field(a, p, "模型 ID", m.model, "例如 glm-5.3 / gpt-6")
        sheet.add(iv, topMarginDp = 4)
        val (nv, nameEdit) = Ui.field(a, p, "显示名称（可不填）", m.displayName, "在模型列表里显示的名字")
        sheet.add(nv, topMarginDp = 12)

        sheet.sectionLabel("上下文长度")
        val presets = Providers.CONTEXT_PRESETS
        var context = m.maxContext
        val (cv, customEdit) = Ui.field(a, p, "自定义（如 128k、200000、2m）",
            Providers.contextLabel(m.maxContext), "128k")
        val presetIdx = presets.indexOf(m.maxContext)
        cv.visibility = if (presetIdx < 0) View.VISIBLE else View.GONE
        sheet.add(Ui.segmented(a, p, listOf("256K", "512K", "1M", "自定义"), if (presetIdx < 0) 3 else presetIdx) { i ->
            if (i < 3) { context = presets[i]; cv.visibility = View.GONE }
            else cv.visibility = View.VISIBLE
        })
        sheet.add(cv, topMarginDp = 8)

        sheet.sectionLabel("思考")
        var thinking = m.thinking
        sheet.add(Ui.segmented(a, p, listOf("不思考", "可开关", "始终思考"), thinking.ordinal) {
            thinking = Providers.Thinking.values()[it]
        })
        var image = m.imageIn
        sheet.add(Ui.switchRow(a, p, "识图", "能接收图片输入（模型本身要支持）", image) { image = it }, topMarginDp = 10)
        sheet.maxBodyHeight(0.62f)

        val ok = Ui.button(a, p, "完成", primary = true) {
            val id = idEdit.text.toString().trim()
            if (id.isEmpty()) { toast("模型 ID 不能空"); return@button }
            if (cv.visibility == View.VISIBLE) {
                context = Providers.parseContext(customEdit.text.toString())
                    ?: run { toast("上下文长度看不懂，写成 128k、200000 或 2m 这样"); return@button }
            }
            m.model = id
            m.displayName = nameEdit.text.toString().trim().ifEmpty { null }
            m.maxContext = context
            m.thinking = thinking
            m.imageIn = image
            sheet.dismiss(); done(false)
        }
        sheet.addFooter(if (canDelete) Ui.buttonRow(a,
            Ui.button(a, p, "删除", primary = false) { sheet.dismiss(); done(true) }, ok)
            else Ui.buttonRow(a, Ui.button(a, p, "取消", primary = false) { sheet.dismiss() }, ok))
        sheet.show()
    }

    // ── 从上游获取 ───────────────────────────────────────

    private fun fetchUpstream(pr: Providers.Provider, urlText: String, keyText: String, changed: () -> Unit) {
        val url = urlText.trim()
        if (!url.startsWith("https://")) {
            toast(if (url.startsWith("http://")) "App 只允许从 https 地址拉取；http 地址请手动添加模型" else "先填 Base URL")
            return
        }
        // 没填新 key 就用配置里已有的
        val key = keyText.trim().ifEmpty {
            if (pr.isNew) "" else (readConfig()?.get(listOf("providers", pr.id), "api_key") as? String).orEmpty()
        }
        toast("正在从上游获取模型…")
        Thread {
            val result = runCatching { Providers.fetchRemote(pr.type, url, key) }
            handler.post {
                result.onSuccess { list ->
                    if (list.isEmpty()) toast("上游没有返回模型") else pickRemote(pr, list, changed)
                }.onFailure { toast("获取失败：${it.message}", long = true) }
            }
        }.start()
    }

    private fun pickRemote(pr: Providers.Provider, list: List<Providers.Remote>, changed: () -> Unit) {
        val p = palette()
        val existing = pr.models.map { it.model }.toSet()
        val chosen = mutableSetOf<String>()
        val sheet = Ui.Sheet(a, p, "从上游获取", "共 ${list.size} 个模型，勾选要添加的；添加后还能逐个改设置")
        val (fv, filter) = Ui.field(a, p, "筛选", null, "输入关键词，如 glm、flash")
        sheet.add(fv, topMarginDp = 4)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        sheet.add(box, topMarginDp = 8)
        val addBtn = Ui.button(a, p, "添加", primary = true) {
            for (r in list) if (r.id in chosen) {
                pr.models.add(Providers.Model.newDefault(r.id).apply {
                    r.context?.let { maxContext = it }
                    if (r.image == true) imageIn = true
                    if (r.reasoning == true) thinking = Providers.Thinking.TOGGLE
                })
            }
            sheet.dismiss(); changed()
            if (chosen.isNotEmpty()) toast("已加入 ${chosen.size} 个，记得点「保存」")
        }
        fun refreshCount() { addBtn.text = if (chosen.isEmpty()) "添加" else "添加 ${chosen.size} 个" }
        fun render() {
            box.removeAllViews()
            val q = filter.text.toString().trim().lowercase()
            val shown = list.filter { q.isEmpty() || it.id.lowercase().contains(q) }.take(200)
            for (r in shown) {
                val info = listOfNotNull(
                    r.context?.let { Providers.contextLabel(it) },
                    if (r.image == true) "识图" else null,
                    if (r.reasoning == true) "思考" else null,
                    if (r.id in existing) "已添加" else null,
                ).joinToString(" · ").ifEmpty { null }
                box.addView(Ui.checkRow(a, p, r.id, info, r.id in chosen, enabled = r.id !in existing) { c ->
                    if (c) chosen.add(r.id) else chosen.remove(r.id)
                    refreshCount()
                })
            }
            if (shown.isEmpty()) box.addView(TextView(a).apply {
                text = "没有匹配的模型"; setTextColor(p.sub); setPadding(a.dp(4), a.dp(8), 0, a.dp(8))
            })
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        render()
        sheet.maxBodyHeight(0.62f)
        sheet.addFooter(Ui.buttonRow(a, Ui.button(a, p, "取消", primary = false) { sheet.dismiss() }, addBtn))
        sheet.show()
    }
}
