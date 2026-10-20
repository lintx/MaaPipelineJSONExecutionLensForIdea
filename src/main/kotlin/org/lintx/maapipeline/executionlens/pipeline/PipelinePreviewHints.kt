package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonParser
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.util.TextRange

internal data class PipelineRoiHint(val key: String?, val coordinates: List<Int>, val range: TextRange, val path: List<String>)
internal data class PipelineObjectHints(var preview: String? = null, val regions: MutableList<PipelineRoiHint> = mutableListOf())

internal object PipelinePreviewHints {
    private val roi = Regex("@roi\\s+(?:(roi|target|begin|end)\\s+)?(\\[\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*])")
    private val preview = Regex("@preview\\s+([^\\r\\n]+)")

    fun sourceText(property: JsonProperty): String {
        val value = property.value ?: return "{}"
        val text = property.text
        val start = property.nameElement.textRange.endOffset - property.textRange.startOffset
        var index = start
        val end = value.textRange.startOffset - property.textRange.startOffset
        while (index < end) {
            when {
                text.startsWith("//", index) -> {
                    val next = text.indexOf('\n', index).takeIf { it >= 0 } ?: end
                    index = next
                }
                text.startsWith("/*", index) -> {
                    val next = text.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: end
                    index = next
                }
                text[index] == ':' -> return text.substring(start).replaceRange(index - start, index - start + 1, " ").trimStart()
                else -> index++
            }
        }
        return value.text
    }

    fun read(text: String): Map<List<String>, PipelineObjectHints> = Scanner(text).run { value(emptyList(), emptyList()); hints }

    private class Scanner(private val text: String) {
        var index = 0
        val hints = linkedMapOf<List<String>, PipelineObjectHints>()
        private val started = mutableSetOf<List<String>>()

        private fun trivia(owner: List<String>, key: String? = null) {
            while (index < text.length) {
                if (text[index].isWhitespace()) { index++; continue }
                val start = index
                val comment = when {
                    text.startsWith("//", index) -> { index = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length; true }
                    text.startsWith("/*", index) -> { index = text.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: text.length; true }
                    else -> false
                }
                if (!comment) return
                val value = text.substring(start, index)
                val target = hints.getOrPut(owner) { PipelineObjectHints() }
                if (owner.isEmpty() && owner !in started) preview.find(value)?.let {
                    target.preview = it.groupValues[1].removeSuffix("*/").trim().removeSurrounding("\"")
                }
                roi.findAll(value).forEach { match ->
                    val array = match.groups[2]!!
                    val numbers = array.value.removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (numbers.size == 4) target.regions += PipelineRoiHint(match.groups[1]?.value ?: key?.takeIf {
                        it in setOf("roi", "target", "begin", "end") }, numbers,
                        TextRange(start + array.range.first, start + array.range.last + 1), owner)
                }
            }
        }

        private fun string(): String? {
            if (text.getOrNull(index) != '"') return null
            val start = index++
            while (index < text.length) {
                if (text[index] == '\\') { index = (index + 2).coerceAtMost(text.length); continue }
                if (text[index++] == '"') return runCatching { JsonParser.parseString(text.substring(start, index)).asString }.getOrNull()
            }
            return null
        }

        fun value(path: List<String>, owner: List<String>, key: String? = null) {
            trivia(owner, key)
            when (text.getOrNull(index)) {
                '{' -> {
                    index++
                    while (index < text.length) {
                        trivia(path)
                        if (text.getOrNull(index) == '}') { index++; break }
                        val name = string() ?: return
                        started += path
                        trivia(path, name)
                        if (text.getOrNull(index++) != ':') return
                        value(path + name, path, name)
                        trivia(path)
                        if (text.getOrNull(index) == ',') index++ else if (text.getOrNull(index) != '}') return
                    }
                }
                '[' -> {
                    index++
                    var item = 0
                    while (index < text.length) {
                        trivia(owner, key)
                        if (text.getOrNull(index) == ']') { index++; break }
                        value(path + (item++).toString(), owner, key)
                        trivia(owner, key)
                        if (text.getOrNull(index) == ',') index++ else if (text.getOrNull(index) != ']') return
                    }
                }
                '"' -> string()
                else -> while (index < text.length && !text[index].isWhitespace() && text[index] !in ",}]/") index++
            }
        }
    }
}
