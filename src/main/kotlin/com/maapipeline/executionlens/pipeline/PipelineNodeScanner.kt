package com.maapipeline.executionlens.pipeline

/**
 * 只读取 Pipeline 文档顶层对象键，避免重写 JSON/JSONC 或丢失注释。
 */
internal object PipelineNodeScanner {
    fun scanNodeNames(text: String): List<String> {
        val scanner = Scanner(text)
        return scanner.scanTopLevelObjectKeys()
    }

    private class Scanner(private val text: String) {
        private var index = 0

        fun scanTopLevelObjectKeys(): List<String> {
            skipTrivia()
            if (readChar() != '{') return emptyList()

            val names = mutableListOf<String>()
            while (index < text.length) {
                skipTrivia()
                if (peekChar() == '}') {
                    index++
                    break
                }

                val name = readString() ?: return names
                skipTrivia()
                if (readChar() != ':') return names
                names += name
                skipValue()
                skipTrivia()

                when (readChar()) {
                    ',' -> Unit
                    '}' -> break
                    else -> return names
                }
            }
            return names
        }

        private fun skipValue() {
            var depth = 0
            var stringDelimiter = false
            while (index < text.length) {
                val character = text[index]
                if (stringDelimiter) {
                    index++
                    when {
                        character == '\\' -> if (index < text.length) index++
                        character == '"' -> stringDelimiter = false
                    }
                    continue
                }

                when (character) {
                    '"' -> {
                        stringDelimiter = true
                        index++
                    }
                    '/' -> {
                        if (peekChar(1) == '/') {
                            skipLineComment()
                        } else if (peekChar(1) == '*') {
                            skipBlockComment()
                        } else {
                            index++
                        }
                    }
                    '{', '[' -> {
                        depth++
                        index++
                    }
                    '}', ']' -> {
                        if (depth == 0) return
                        depth--
                        index++
                    }
                    ',' -> if (depth == 0) return else index++
                    else -> index++
                }
            }
        }

        private fun readString(): String? {
            if (readChar() != '"') return null
            val value = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when (character) {
                    '"' -> return value.toString()
                    '\\' -> if (index < text.length) value.append(text[index++])
                    else -> value.append(character)
                }
            }
            return null
        }

        private fun skipTrivia() {
            while (index < text.length) {
                when {
                    text[index].isWhitespace() -> index++
                    text[index] == '/' && peekChar(1) == '/' -> skipLineComment()
                    text[index] == '/' && peekChar(1) == '*' -> skipBlockComment()
                    else -> return
                }
            }
        }

        private fun skipLineComment() {
            index += 2
            while (index < text.length && text[index] != '\n') index++
        }

        private fun skipBlockComment() {
            index += 2
            while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) index++
            if (index + 1 < text.length) index += 2
        }

        private fun readChar(): Char? = if (index < text.length) text[index++] else null

        private fun peekChar(offset: Int = 0): Char? =
            if (index + offset < text.length) text[index + offset] else null
    }
}
