package com.kimbox

/**
 * config.toml 的最小分段读写：按 [表头] 切段，只改动被点名的键和表，其余内容逐行原样保留
 * （注释、空行、没见过的键、内联表都不碰）。
 *
 * 不是完整的 TOML 解析器：能读写的值只有 字符串 / 整数 / 布尔 / 字符串数组（数组可以跨行）；
 * 其他写法读出来是 null，但原文照样保留。[[数组表]] 自成一段、不可读写（原样保留），
 * 免得它的键被当成上一张表的。
 */
class ConfigToml private constructor(private val sections: MutableList<Section>) {

    /** path 为空表示文件开头、第一个表头之前的部分；array 为 [[数组表]] 的一个条目 */
    private class Section(val path: List<String>, val lines: MutableList<String>, val array: Boolean = false)

    /** 一个键值在 Section.lines 里占的行区间 [start, end) */
    private class Entry(val key: String, val start: Int, val end: Int, val rawValue: String)

    companion object {
        private val BARE_KEY = Regex("^[A-Za-z0-9_-]+$")

        fun parse(text: String): ConfigToml {
            val sections = mutableListOf(Section(emptyList(), mutableListOf()))
            val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
            var depth = 0          // 跨行数组的未闭合括号层数
            var inMultiString: String? = null
            for (line in lines) {
                if (inMultiString != null) {
                    sections.last().lines.add(line)
                    if (line.contains(inMultiString)) inMultiString = null
                    continue
                }
                if (depth > 0) {
                    sections.last().lines.add(line)
                    depth += bracketDelta(line)
                    continue
                }
                val header = headerPath(line)
                if (header != null) {
                    sections.add(Section(header.first, mutableListOf(line), array = header.second))
                    continue
                }
                sections.last().lines.add(line)
                val eq = assignmentValue(line) ?: continue
                val v = eq.trimStart()
                when {
                    v.startsWith("\"\"\"") || v.startsWith("'''") -> {
                        val q = v.substring(0, 3)
                        if (!v.substring(3).contains(q)) inMultiString = q
                    }
                    v.startsWith("[") -> depth = bracketDelta(v).coerceAtLeast(0)
                }
            }
            return ConfigToml(sections)
        }

        /** 表头行 → (路径, 是否数组表 [[x]])；非表头行返回 null */
        private fun headerPath(line: String): Pair<List<String>, Boolean>? {
            val t = stripComment(line).trim()
            if (!t.startsWith("[") || !t.endsWith("]")) return null
            val array = t.startsWith("[[") && t.endsWith("]]")
            val inner = if (array) t.substring(2, t.length - 2) else t.substring(1, t.length - 1)
            return parseKeyPath(inner.trim())?.let { it to array }
        }

        /** 解析 a."b.c".'d' 这样的点分键 */
        fun parseKeyPath(s: String): List<String>? {
            val out = mutableListOf<String>()
            var i = 0
            while (i < s.length) {
                while (i < s.length && s[i] == ' ') i++
                if (i >= s.length) return null
                when (s[i]) {
                    '"' -> {
                        val sb = StringBuilder()
                        i++
                        while (i < s.length && s[i] != '"') {
                            if (s[i] == '\\' && i + 1 < s.length) { sb.append(unescape(s[i + 1])); i += 2 } else sb.append(s[i++])
                        }
                        if (i >= s.length) return null
                        i++
                        out.add(sb.toString())
                    }
                    '\'' -> {
                        val end = s.indexOf('\'', i + 1)
                        if (end < 0) return null
                        out.add(s.substring(i + 1, end))
                        i = end + 1
                    }
                    else -> {
                        val start = i
                        while (i < s.length && s[i] != '.' && s[i] != ' ') i++
                        val k = s.substring(start, i)
                        if (!BARE_KEY.matches(k)) return null
                        out.add(k)
                    }
                }
                while (i < s.length && s[i] == ' ') i++
                if (i < s.length) {
                    if (s[i] != '.') return null
                    i++
                }
            }
            return if (out.isEmpty()) null else out
        }

        fun encodeKey(k: String): String = if (BARE_KEY.matches(k)) k else quote(k)

        fun encodePath(path: List<String>): String = path.joinToString(".") { encodeKey(it) }

        fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            return sb.append('"').toString()
        }

        fun encodeValue(v: Any): String = when (v) {
            is String -> quote(v)
            is Int, is Long -> v.toString()
            is Boolean -> v.toString()
            is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(", ", "[ ", " ]") { encodeValue(it!!) }
            else -> throw IllegalArgumentException("不支持的值类型: ${v::class}")
        }

        private fun unescape(c: Char): Char = when (c) {
            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> c
        }

        /** 去掉行尾注释（引号里的 # 不算） */
        private fun stripComment(line: String): String {
            var q: Char? = null
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (q != null) {
                    if (c == '\\' && q == '"') i++ else if (c == q) q = null
                } else if (c == '"' || c == '\'') q = c
                else if (c == '#') return line.substring(0, i)
                i++
            }
            return line
        }

        /** key = value 行里等号右边的部分；不是赋值行返回 null */
        private fun assignmentValue(line: String): String? {
            val t = line.trimStart()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) return null
            val eq = keyEnd(t) ?: return null
            return t.substring(eq + 1)
        }

        /** 赋值行里键的结束位置（等号下标），会跳过引号键里的等号 */
        private fun keyEnd(t: String): Int? {
            var q: Char? = null
            for (i in t.indices) {
                val c = t[i]
                if (q != null) { if (c == q) q = null; continue }
                if (c == '"' || c == '\'') q = c else if (c == '=') return i
            }
            return null
        }

        /** 一行里方括号的净开合数（忽略字符串与注释里的括号） */
        private fun bracketDelta(line: String): Int {
            val s = stripComment(line)
            var q: Char? = null
            var d = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (q != null) {
                    if (c == '\\' && q == '"') i++ else if (c == q) q = null
                } else when (c) {
                    '"', '\'' -> q = c
                    '[' -> d++
                    ']' -> d--
                }
                i++
            }
            return d
        }

        /** 解析值文本；不认识的写法返回 null */
        fun parseValue(raw: String): Any? {
            val v = stripComment(raw).trim()
            if (v.isEmpty()) return null
            return when {
                v.startsWith("\"\"\"") || v.startsWith("'''") -> null
                v.startsWith("\"") -> parseBasicString(v)?.takeIf { it.second == v.length }?.first
                v.startsWith("'") -> if (v.length >= 2 && v.endsWith("'")) v.substring(1, v.length - 1) else null
                v == "true" -> true
                v == "false" -> false
                v.startsWith("[") -> parseArray(v)
                else -> v.replace("_", "").toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            }
        }

        private fun parseBasicString(v: String, from: Int = 0): Pair<String, Int>? {
            if (v.getOrNull(from) != '"') return null
            val sb = StringBuilder()
            var i = from + 1
            while (i < v.length) {
                val c = v[i]
                when {
                    c == '"' -> return sb.toString() to i + 1
                    c == '\\' && i + 1 < v.length -> {
                        val n = v[i + 1]
                        if (n == 'u' && i + 5 < v.length) {
                            sb.append(v.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
                        } else { sb.append(unescape(n)); i += 2 }
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return null
        }

        /** 只认字符串数组（数字/布尔数组也接受），嵌套数组或内联表返回 null */
        private fun parseArray(v: String): List<Any>? {
            if (!v.endsWith("]")) return null
            val out = mutableListOf<Any>()
            var i = 1
            while (i < v.length - 1) {
                val c = v[i]
                when {
                    c.isWhitespace() || c == ',' -> i++
                    c == '"' -> {
                        val (s, next) = parseBasicString(v, i) ?: return null
                        out.add(s); i = next
                    }
                    c == '\'' -> {
                        val end = v.indexOf('\'', i + 1).takeIf { it > 0 } ?: return null
                        out.add(v.substring(i + 1, end)); i = end + 1
                    }
                    c == '[' || c == '{' -> return null
                    else -> {
                        val start = i
                        while (i < v.length - 1 && v[i] != ',' && !v[i].isWhitespace()) i++
                        out.add(parseValue(v.substring(start, i)) ?: return null)
                    }
                }
            }
            return out
        }
    }

    private fun entries(sec: Section): List<Entry> {
        val out = mutableListOf<Entry>()
        val body = if (sec.path.isEmpty()) 0 else 1
        var i = body
        while (i < sec.lines.size) {
            val line = sec.lines[i]
            val t = line.trimStart()
            val eq = if (t.isEmpty() || t.startsWith("#")) null else keyEnd(t)
            if (eq == null) { i++; continue }
            val keyPath = parseKeyPath(t.substring(0, eq).trim())
            // 每行先去掉行尾注释再拼：跨行数组里的注释拼到一行后会把后面的元素一起「注释」掉
            var raw = stripComment(t.substring(eq + 1))
            var end = i + 1
            val v = raw.trimStart()
            if (v.startsWith("[")) {
                var depth = bracketDelta(v)
                while (depth > 0 && end < sec.lines.size) {
                    raw += "\n" + stripComment(sec.lines[end])
                    depth += bracketDelta(sec.lines[end])
                    end++
                }
            } else if (v.startsWith("\"\"\"") || v.startsWith("'''")) {
                val q = v.substring(0, 3)
                if (!v.substring(3).contains(q)) {
                    while (end < sec.lines.size && !sec.lines[end].contains(q)) end++
                    end = (end + 1).coerceAtMost(sec.lines.size)
                }
            }
            // 点分键（a.b = 1）不参与编辑，只记单段键
            if (keyPath != null && keyPath.size == 1) out.add(Entry(keyPath[0], i, end, raw.replace("\n", " ")))
            i = end
        }
        return out
    }

    private fun section(path: List<String>): Section? = sections.firstOrNull { !it.array && it.path == path }

    /** 所有普通表的路径（不含开头的无表头部分和数组表） */
    fun tablePaths(): List<List<String>> = sections.mapNotNull { s -> s.path.takeIf { it.isNotEmpty() && !s.array } }

    fun hasTable(path: List<String>): Boolean = section(path) != null

    fun get(path: List<String>, key: String): Any? {
        val sec = section(path) ?: return null
        return entries(sec).lastOrNull { it.key == key }?.let { parseValue(it.rawValue) }
    }

    /** 写入一个键；value 为 null 表示删掉该键。表不存在时在文件末尾新建 */
    fun set(path: List<String>, key: String, value: Any?) {
        var sec = section(path)
        if (sec == null) {
            if (value == null) return
            val last = sections.last()
            if (last.lines.isNotEmpty() && last.lines.last().isNotBlank()) last.lines.add("")
            sec = Section(path, mutableListOf("[" + encodePath(path) + "]"))
            sections.add(sec)
        }
        val existing = entries(sec).filter { it.key == key }
        val newLine = if (value == null) null else encodeKey(key) + " = " + encodeValue(value)
        if (existing.isEmpty()) {
            if (newLine == null) return
            // 插在本段最后一个非空行之后，保持段尾空行
            var at = sec.lines.size
            while (at > (if (sec.path.isEmpty()) 0 else 1) && sec.lines[at - 1].isBlank()) at--
            sec.lines.add(at, newLine)
            return
        }
        // 重复键只保留第一处（TOML 本来就不允许重复）
        for (e in existing.drop(1).reversed()) repeat(e.end - e.start) { sec.lines.removeAt(e.start) }
        val first = existing.first()
        repeat(first.end - first.start) { sec.lines.removeAt(first.start) }
        if (newLine != null) sec.lines.add(first.start, newLine)
    }

    /** 删掉一张表；withChildren 时连同 path.* 的子表（含 [[path.x]] 数组表）一起删 */
    fun removeTable(path: List<String>, withChildren: Boolean = true) {
        sections.removeAll { s ->
            s.path.isNotEmpty() && (s.path == path ||
                (withChildren && s.path.size > path.size && s.path.subList(0, path.size) == path))
        }
    }

    fun toText(): String {
        val all = sections.flatMap { it.lines }.toMutableList()
        while (all.isNotEmpty() && all.last().isBlank()) all.removeAt(all.size - 1)
        return all.joinToString("\n", postfix = "\n")
    }
}
