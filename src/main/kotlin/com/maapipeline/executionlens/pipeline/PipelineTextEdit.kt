package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange

internal data class PipelineTextEdit(val range: TextRange, val replacement: String) {
    fun applyTo(text: String): String = text.replaceRange(range.startOffset, range.endOffset, replacement)
}

internal data class PipelineEditSnapshot(val version: Long, val text: String) {
    fun matches(version: Long, text: String): Boolean = this.version == version && this.text == text
}

internal object PipelineJsonTextEdits {
    fun alignValue(document: String, startOffset: Int, value: String): String {
        if (!value.contains('\n')) return value
        val lineStart = document.lastIndexOf('\n', (startOffset - 1).coerceAtLeast(0)) + 1
        val indent = document.substring(lineStart, startOffset).takeWhile { it == ' ' || it == '\t' }
        val lines = value.replace("\r\n", "\n").trim().split('\n')
        val common = lines.drop(1).filter { it.isNotBlank() }.map { it.takeWhile { character -> character == ' ' || character == '\t' } }
            .reduceOrNull { current, next -> current.commonPrefixWith(next) }.orEmpty()
        return lines.first() + "\n" + lines.drop(1).joinToString("\n") { line ->
            if (line.isBlank()) "" else indent + line.removePrefix(common)
        }
    }

    fun replacementEdit(range: TextRange, original: String, replacement: String): PipelineTextEdit {
        val prefix = original.commonPrefixWith(replacement).length
        val suffix = original.drop(prefix).commonSuffixWith(replacement.drop(prefix)).length
        return PipelineTextEdit(
            TextRange(range.startOffset + prefix, range.endOffset - suffix),
            replacement.substring(prefix, replacement.length - suffix),
        )
    }

    fun appendMember(container: String, member: String, indent: String = "  "): String {
        val tokens = significantOffsets(container)
        require(tokens.size >= 2)
        val opening = tokens.first()
        val closing = tokens.last()
        require((container[opening] == '{' && container[closing] == '}') ||
            (container[opening] == '[' && container[closing] == ']'))
        val lastContent = tokens.getOrNull(tokens.lastIndex - 1)?.takeIf { it != opening }
        val newline = if (container.contains("\r\n")) "\r\n" else "\n"
        val closingLine = container.lastIndexOf('\n', closing).let { if (it < 0) 0 else it + 1 }
        val closingIndent = container.substring(closingLine, closing).takeIf { it.all(Char::isWhitespace) }.orEmpty()
        val insertionOffset = if (closingIndent.isNotEmpty()) closingLine else closing
        val formatted = member.replace("\r\n", "\n").split('\n').joinToString(newline + indent)
        val prefix = if (insertionOffset > 0 && container[insertionOffset - 1] == '\n') "" else newline
        var result = container.substring(0, insertionOffset) + prefix + indent + formatted + newline +
            container.substring(insertionOffset)
        if (lastContent != null && container[lastContent] != ',') {
            result = result.substring(0, lastContent + 1) + "," + result.substring(lastContent + 1)
        }
        return result
    }

    fun deletionEdits(text: String, range: TextRange): List<PipelineTextEdit> {
        require(range.startOffset >= 0 && range.endOffset <= text.length)
        val tokens = significantOffsets(text)
        val after = tokens.firstOrNull { it >= range.endOffset }
        val before = tokens.lastOrNull { it < range.startOffset }
        val comma = after?.takeIf { text[it] == ',' } ?: before?.takeIf { text[it] == ',' }
        return (listOf(PipelineTextEdit(range, "")) + listOfNotNull(comma?.let {
            PipelineTextEdit(TextRange(it, it + 1), "")
        })).sortedByDescending { it.range.startOffset }
    }

    fun applyEdits(text: String, edits: List<PipelineTextEdit>): String =
        edits.sortedByDescending { it.range.startOffset }.fold(text) { current, edit -> edit.applyTo(current) }

    private fun significantOffsets(text: String): List<Int> {
        val offsets = mutableListOf<Int>()
        var index = 0
        while (index < text.length) {
            when {
                text[index].isWhitespace() -> index++
                text.startsWith("//", index) -> {
                    index = text.indexOf('\n', index + 2).takeIf { it >= 0 } ?: text.length
                }
                text.startsWith("/*", index) -> {
                    val end = text.indexOf("*/", index + 2)
                    index = if (end < 0) text.length else end + 2
                }
                text[index] == '"' -> {
                    // 字符串内部的注释标记和括号不是分隔符，转义引号也不能提前结束字符串。
                    offsets += index++
                    while (index < text.length) {
                        if (text[index] == '\\') {
                            index += 2
                        } else if (text[index++] == '"') {
                            offsets += index - 1
                            break
                        }
                    }
                }
                else -> offsets += index++
            }
        }
        return offsets
    }
}
