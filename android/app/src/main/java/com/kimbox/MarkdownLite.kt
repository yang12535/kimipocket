package com.kimbox

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * Release 简介用的极简 Markdown 渲染：标题、列表、引用、代码块、粗体、行内代码、链接。
 * 只为「检查更新」弹窗服务，不追求完整语法；认不出的行按普通段落原样显示。
 * 只含 [标签] 的行会被丢掉（标签在弹窗顶部单独做成小块展示，避免重复）。
 */
object MarkdownLite {

    private val TAG_ONLY = Regex("""^\s*(\[[^\]]+\]\s*)+$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d+[.)])\s+(.*)$""")
    private val INLINE = Regex("""`([^`]+)`|\*\*(.+?)\*\*|\[([^\]]+)]\((https?://[^)\s]+)\)""")

    fun render(md: String, ui: Ui.Palette, dp: (Int) -> Int): CharSequence {
        val out = SpannableStringBuilder()
        val src = md.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").replace("\r", "")
        var inCode = false
        var lastBlank = true

        fun newline() {
            if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append('\n')
        }

        fun blankLine() {
            if (out.isEmpty() || lastBlank) return
            newline()
            out.append('\n')
            lastBlank = true
        }

        for (raw in src.lines()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inCode = !inCode
                if (!inCode) blankLine()
                continue
            }
            if (inCode) {
                newline()
                val start = out.length
                out.append(line.ifEmpty { " " })
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(ui.surface), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.9f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                lastBlank = false
                continue
            }
            if (line.isBlank()) { blankLine(); continue }
            if (TAG_ONLY.matches(line)) continue
            if (line.trim().matches(Regex("""^([-*_])\1{2,}$"""))) { blankLine(); continue }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                blankLine()
                val level = heading.groupValues[1].length
                val start = out.length
                appendInline(out, heading.groupValues[2], ui)
                val size = when (level) { 1 -> 1.3f; 2 -> 1.18f; 3 -> 1.06f; else -> 1f }
                out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append('\n')
                lastBlank = false
            } else {
                val bullet = BULLET.matchEntire(line)
                val numbered = if (bullet == null) NUMBERED.matchEntire(line) else null
                when {
                    bullet != null || numbered != null -> {
                        val indent = (bullet?.groupValues?.get(1) ?: numbered!!.groupValues[1]).length / 2
                        val marker = if (bullet != null) "•" else numbered!!.groupValues[2]
                        val text = bullet?.groupValues?.get(2) ?: numbered!!.groupValues[3]
                        newline()
                        val start = out.length
                        out.append(marker).append("  ")
                        appendInline(out, text, ui)
                        val first = dp(4 + indent * 16)
                        val rest = first + dp(if (bullet != null) 12 else 18)
                        out.setSpan(LeadingMarginSpan.Standard(first, rest), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.append('\n')
                    }
                    line.startsWith(">") -> {
                        newline()
                        val start = out.length
                        appendInline(out, line.removePrefix(">").trimStart(), ui)
                        out.setSpan(QuoteSpan(ui.divider), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(ui.sub), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.append('\n')
                    }
                    else -> {
                        newline()
                        appendInline(out, line.trim(), ui)
                        out.append('\n')
                    }
                }
                lastBlank = false
            }
        }
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, text: String, ui: Ui.Palette) {
        var pos = 0
        for (m in INLINE.findAll(text)) {
            out.append(text, pos, m.range.first)
            val start = out.length
            when {
                m.groupValues[1].isNotEmpty() -> {
                    out.append(m.groupValues[1])
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(ui.surface), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groupValues[2].isNotEmpty() -> {
                    out.append(m.groupValues[2])
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    out.append(m.groupValues[3])
                    out.setSpan(URLSpan(m.groupValues[4]), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(ui.accent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            pos = m.range.last + 1
        }
        out.append(text, pos, text.length)
    }
}
