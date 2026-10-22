package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange

internal object PipelineTokenColors {
    private val tokens = Regex("\\b(?:else|if|subtask|return|on_error|retry|skip)\\b|\\b(?:DirectHit|DoNothing|Click|LongPress|Swipe|MultiSwipe|TouchDown|TouchMove|TouchUp|Scroll|ClickKey|LongPressKey|KeyDown|KeyUp|InputText|StartApp|StopApp|Command|Shell|Screencap|Custom|OCR|TemplateMatch|FeatureMatch|ColorMatch|And|Or|NeuralNetworkClassify|NeuralNetworkDetect)\\b|\\b(?:timeout|max_hit|rate_limit|roi|target|begin|end|box_index)\\b|\\b-?\\d+(?:\\.\\d+)?\\b")
    fun ranges(text: String): List<Pair<TextRange, TextAttributesKey>> = tokens.findAll(text).map { match ->
        TextRange(match.range.first, match.range.last + 1) to when {
            match.value.first().isDigit() || match.value.startsWith('-') -> Colors.NUMBER
            match.value in setOf("if", "else", "subtask", "return", "on_error", "retry", "skip") -> Colors.KEYWORD
            match.value.first().isUpperCase() -> Colors.FUNCTION_CALL
            else -> Colors.INSTANCE_FIELD
        }
    }.toList()
}
