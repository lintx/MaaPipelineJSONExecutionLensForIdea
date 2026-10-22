package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonParser
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

internal data class PipelineRoiHint(val key: String?, val coordinates: List<Int>, val range: TextRange, val path: List<String>,
    val controller: String? = null, val sourceFile: VirtualFile? = null)
internal data class PipelinePreviewHint(val path: String, val controller: String? = null, val sourceFile: VirtualFile? = null)
internal data class PipelineObjectHints(val previews: MutableList<PipelinePreviewHint> = mutableListOf(),
    val regions: MutableList<PipelineRoiHint> = mutableListOf())

internal object PipelinePreviewHints {
    private val roi = Regex("@roi\\s+((?:(?:\"[^\"\\r\\n]+\"|[^\\s\\[\\]\\r\\n]+)\\s+){0,2})(\\[\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*])")
    private val preview = Regex("@preview\\s+([^\\r\\n]+)")
    private val selector = Regex("^(\"[^\"]+\"|[^\\s]+)\\s+(.+)$")
    private val regionKeys = setOf("roi", "target", "begin", "end")

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

    fun sources(node: PipelineNodeLocation): List<PipelinePreviewSource> = node.previewSources.ifEmpty {
        listOf(PipelinePreviewSource(node.previewSource ?: node.rawJson, node.sourceFile))
    }

    fun selected(node: PipelineNodeLocation): Map<List<String>, PipelineObjectHints> {
        val result = linkedMapOf<List<String>, PipelineObjectHints>()
        fun rank(controller: String?): Int = when {
            controller == null -> 0
            node.environmentId.isNotBlank() && controller.equals(node.environmentId, true) -> 2
            node.controllerType.isNotBlank() && controller.equals(node.controllerType, true) -> 1
            else -> -1
        }
        // 每层先选择具体控制器、控制器类型或默认值，再由后加载的资源层覆盖已有注释。
        sources(node).forEach { source ->
            read(source.rawJson).forEach { (path, hints) ->
                val target = result.getOrPut(path) { PipelineObjectHints() }
                hints.previews.filter { rank(it.controller) >= 0 }.maxWithOrNull(compareBy<PipelinePreviewHint> { rank(it.controller) }
                    .thenBy { hints.previews.indexOf(it) })?.let {
                    target.previews.clear()
                    target.previews += it.copy(sourceFile = source.sourceFile)
                }
                hints.regions.groupBy { it.key ?: "roi" }.forEach { (key, regions) ->
                    regions.filter { rank(it.controller) >= 0 }.maxWithOrNull(compareBy<PipelineRoiHint> { rank(it.controller) }
                        .thenBy { it.range.startOffset })?.let {
                        target.regions.removeAll { hint -> (hint.key ?: "roi") == key }
                        target.regions += it.copy(sourceFile = source.sourceFile)
                    }
                }
            }
        }
        return result
    }

    fun preview(node: PipelineNodeLocation): PipelinePreviewHint? = selected(node)[emptyList()]?.previews?.lastOrNull()

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
                if (owner.isEmpty() && owner !in started) preview.findAll(value).forEach {
                    val text = it.groupValues[1].removeSuffix("*/").trim()
                    val parts = selector.matchEntire(text)
                    val controller = parts?.groupValues?.get(1)?.removeSurrounding("\"")
                        ?.takeUnless { token -> token.any { character -> character in "/\\.:" } }
                    val path = (if (parts != null && controller != null) parts.groupValues[2] else text).trim().removeSurrounding("\"")
                    if (path.isNotBlank()) target.previews += PipelinePreviewHint(path, controller)
                }
                roi.findAll(value).forEach { match ->
                    val tokens = Regex("\"[^\"]+\"|\\S+").findAll(match.groupValues[1]).map { it.value.removeSurrounding("\"") }.toList()
                    val explicitKey = tokens.firstOrNull { it in regionKeys }
                    val controllers = tokens.filter { it !in regionKeys }
                    if (controllers.size > 1) return@forEach
                    val array = match.groups[2]!!
                    val numbers = array.value.removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (numbers.size == 4) target.regions += PipelineRoiHint(explicitKey ?: key?.takeIf {
                        it in regionKeys }, numbers,
                        TextRange(start + array.range.first, start + array.range.last + 1), owner, controllers.singleOrNull())
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
