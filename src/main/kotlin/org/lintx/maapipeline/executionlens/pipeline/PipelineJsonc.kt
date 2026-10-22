package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

internal object PipelineJsonc {
    fun parse(text: String): JsonElement {
        val normalized = text.toCharArray()
        var index = 0
        var comma = -1
        while (index < text.length) {
            when {
                text[index] == '"' -> {
                    comma = -1
                    index++
                    while (index < text.length) {
                        if (text[index] == '\\') { index += 2; continue }
                        if (text[index++] == '"') break
                    }
                }
                text.startsWith("//", index) -> {
                    while (index < text.length && text[index] != '\n') normalized[index++] = ' '
                }
                text.startsWith("/*", index) -> {
                    val end = text.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2)
                        ?: throw JsonSyntaxException("未闭合的 JSON 注释")
                    while (index < end) { if (normalized[index] != '\n' && normalized[index] != '\r') normalized[index] = ' '; index++ }
                }
                text[index].isWhitespace() -> index++
                else -> {
                    // 只消去字符串外、位于容器末尾的逗号；不能替换注释或字符串里的相同片段。
                    if (text[index] in setOf('}', ']') && comma >= 0) normalized[comma] = ' '
                    comma = if (text[index] == ',') index else -1
                    index++
                }
            }
        }
        return JsonParser.parseString(String(normalized))
    }
}
