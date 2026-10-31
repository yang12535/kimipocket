package com.kimbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTomlTest {

    private val seed: String =
        javaClass.classLoader!!.getResource("seed-config.toml")!!.readText()

    @Test
    fun roundTripKeepsFileByteForByte() {
        assertEquals(seed, ConfigToml.parse(seed).toText())
    }

    @Test
    fun readsSeedProvidersAndModels() {
        val list = Providers.load(ConfigToml.parse(seed))
        assertEquals(1, list.size)
        val kimi = list[0]
        assertEquals("managed:kimi-code", kimi.id)
        assertTrue(kimi.managed)
        assertEquals(5, kimi.models.size)
        val k3 = kimi.models.first { it.alias == "kimi-code/k3" }
        assertEquals(1048576, k3.maxContext)
        assertEquals(Providers.Thinking.ALWAYS, k3.thinking)
        assertTrue(k3.imageIn)
        assertEquals(listOf("video_in", "tool_use"), k3.otherCaps)
    }

    @Test
    fun addProviderThenReadBack() {
        val cfg = ConfigToml.parse(seed)
        val p = Providers.Provider("newapi", "openai", "https://api.example.com/v1/", false, "sk-test", false,
            mutableListOf(
                Providers.Model.newDefault("glm-5.3").apply { thinking = Providers.Thinking.TOGGLE },
                Providers.Model.newDefault("cn:deepseek-v4.1-flash").apply { maxContext = Providers.M1; imageIn = true },
            ), isNew = true)
        Providers.save(cfg, p)
        val text = cfg.toText()
        assertTrue(text.contains("[providers.newapi]"))
        assertTrue(text.contains("base_url = \"https://api.example.com/v1\""))
        assertTrue(text.contains("[models.\"newapi/cn:deepseek-v4.1-flash\"]"))
        // 原有内容不受影响
        assertTrue(text.startsWith(seed.trimEnd('\n')))

        val back = Providers.load(ConfigToml.parse(text)).first { it.id == "newapi" }
        assertTrue(back.hasApiKey)
        assertFalse(back.managed)
        val glm = back.models.first { it.model == "glm-5.3" }
        assertEquals(Providers.Thinking.TOGGLE, glm.thinking)
        assertEquals(Providers.K256, glm.maxContext)
        val ds = back.models.first { it.model == "cn:deepseek-v4.1-flash" }
        assertEquals(Providers.M1, ds.maxContext)
        assertTrue(ds.imageIn)
        assertEquals(listOf("image_in", "tool_use"), ds.capabilities())
    }

    @Test
    fun editKeepsChildTablesAndUnknownKeys() {
        val cfg = ConfigToml.parse(seed)
        val kimi = Providers.load(cfg)[0]
        val max = kimi.models.first { it.alias == "k3-256k-max" }
        max.thinking = Providers.Thinking.OFF
        max.maxContext = Providers.K512
        Providers.save(cfg, kimi)
        val text = cfg.toText()
        assertTrue(text.contains("[models.k3-256k-max.overrides]\ndefault_effort = \"max\""))
        assertTrue(text.contains("support_efforts = [ \"low\", \"high\", \"max\" ]"))
        val again = Providers.load(ConfigToml.parse(text))[0].models.first { it.alias == "k3-256k-max" }
        assertEquals(Providers.Thinking.OFF, again.thinking)
        assertEquals(Providers.K512, again.maxContext)
        // oauth 子表、未知键都还在
        assertTrue(text.contains("[providers.\"managed:kimi-code\".oauth]"))
        assertTrue(text.contains("[experimental]\nsecondary-model = true"))
    }

    @Test
    fun deleteProviderRemovesModelsAndChildTables() {
        val cfg = ConfigToml.parse(seed)
        val p = Providers.Provider("x", "openai", "https://x/v1", false, "k", false,
            mutableListOf(Providers.Model.newDefault("m1")), isNew = true)
        Providers.save(cfg, p)
        cfg.set(listOf("models", "x/m1", "overrides"), "default_effort", "high")
        val loaded = Providers.load(cfg).first { it.id == "x" }
        Providers.delete(cfg, loaded)
        val text = cfg.toText()
        assertFalse(text.contains("x/m1"))
        assertFalse(text.contains("[providers.x]"))
        assertEquals(seed, text)
    }

    @Test
    fun multiLineArrayIsReadAndReplacedAsOneValue() {
        val src = """
            [models.a]
            provider = "p"
            capabilities = [
              "thinking", # 注释里的 ] 不算
              "image_in",
            ]
            max_context_size = 131_072

            [providers.p]
            type = "openai"
        """.trimIndent() + "\n"
        val cfg = ConfigToml.parse(src)
        assertEquals(src, cfg.toText())
        assertEquals(listOf("thinking", "image_in"), cfg.get(listOf("models", "a"), "capabilities"))
        assertEquals(131072, cfg.get(listOf("models", "a"), "max_context_size"))
        cfg.set(listOf("models", "a"), "capabilities", listOf("tool_use"))
        val out = cfg.toText()
        assertTrue(out.contains("capabilities = [ \"tool_use\" ]\nmax_context_size"))
        assertFalse(out.contains("image_in"))
        assertTrue(cfg.hasTable(listOf("providers", "p")))
    }

    @Test
    fun arrayTablesAreIsolatedFromPrecedingTable() {
        val src = """
            [providers.p]
            type = "openai"

            [[providers.p.headers]]
            name = "x"

            [[extra]]
            type = "keep-me"
            # 注释

            [[extra]]
            type = "keep-me-too"

            [models."p/m"]
            provider = "p"
            model = "m"
        """.trimIndent() + "\n"
        val cfg = ConfigToml.parse(src)
        assertEquals(src, cfg.toText())
        assertEquals("openai", cfg.get(listOf("providers", "p"), "type"))
        assertFalse(cfg.hasTable(listOf("extra")))
        assertFalse(cfg.tablePaths().contains(listOf("extra")))
        assertFalse(cfg.tablePaths().contains(listOf("providers", "p", "headers")))

        // 改供应商只动它自己那段，数组表里的同名键不受影响
        cfg.set(listOf("providers", "p"), "type", "anthropic")
        cfg.set(listOf("providers", "p"), "base_url", "https://x/v1")
        val edited = cfg.toText()
        assertTrue(edited.contains("[providers.p]\ntype = \"anthropic\"\nbase_url = \"https://x/v1\"\n\n[[providers.p.headers]]"))
        assertTrue(edited.contains("[[extra]]\ntype = \"keep-me\"\n# 注释\n\n[[extra]]\ntype = \"keep-me-too\""))
        assertEquals("anthropic", Providers.load(ConfigToml.parse(edited)).single().type)

        // 删供应商：连同它名下的数组表一起删，无关数组表原样保留
        val again = ConfigToml.parse(edited)
        Providers.delete(again, Providers.load(again).single())
        assertEquals("""
            [[extra]]
            type = "keep-me"
            # 注释

            [[extra]]
            type = "keep-me-too"
        """.trimIndent() + "\n", again.toText())
    }

    @Test
    fun keyPathsWithQuotesAndDots() {
        assertEquals(listOf("models", "kimi-code/k3"), ConfigToml.parseKeyPath("models.\"kimi-code/k3\""))
        assertEquals(listOf("a", "b.c", "d"), ConfigToml.parseKeyPath("a.'b.c' . d"))
        assertNull(ConfigToml.parseKeyPath("a..b"))
        assertEquals("models.\"x/y:z\"", ConfigToml.encodePath(listOf("models", "x/y:z")))
        assertEquals("\"a\\\"b\\\\c\"", ConfigToml.quote("a\"b\\c"))
    }

    @Test
    fun removingKeyAndSettingNullOnMissingTableIsNoop() {
        val cfg = ConfigToml.parse(seed)
        cfg.set(listOf("nope"), "k", null)
        assertEquals(seed, cfg.toText())
        cfg.set(listOf("thinking"), "effort", null)
        assertFalse(cfg.toText().contains("effort = \"max\"\n\n[services"))
        assertNull(cfg.get(listOf("thinking"), "effort"))
    }

    @Test
    fun parseContextAcceptsCommonWritings() {
        assertEquals(262144, Providers.parseContext("256k"))
        assertEquals(1048576, Providers.parseContext("1M"))
        assertEquals(1572864, Providers.parseContext("1.5m"))
        assertEquals(200000, Providers.parseContext("200,000"))
        assertNull(Providers.parseContext("abc"))
        assertNull(Providers.parseContext("0"))
        assertEquals("256K", Providers.contextLabel(262144))
        assertEquals("1M", Providers.contextLabel(1048576))
        assertEquals("200k", Providers.contextLabel(200000))
        assertEquals("1000k", Providers.contextLabel(1000000))
        assertEquals(1000000, Providers.parseContext("1000000"))
        assertEquals("131073", Providers.contextLabel(131073))
        // 输入框写法必须能原样换算回来（十进制长度不能被 k=1024 放大）
        for (n in listOf(200000, 1000000, 204800, 262144, 1048576, 131073, 128000)) {
            assertEquals(n, Providers.parseContext(Providers.contextInput(n)))
        }
    }
}
