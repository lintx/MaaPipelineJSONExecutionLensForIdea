package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange

internal object PipelineTokenColors {
    private val keywords = setOf("if", "else", "subtask", "return", "on_error", "retry", "skip", "and", "or", "And", "Or", "JumpBack", "Anchor")
    private val recognitions = setOf("DirectHit", "OCR", "TemplateMatch", "FeatureMatch", "ColorMatch", "NeuralNetworkClassify", "NeuralNetworkDetect")
    private val tokens = Regex("\\b(?:else|if|subtask|return|on_error|retry|skip|and|or|JumpBack|Anchor)\\b|\\b(?:DirectHit|DoNothing|Click|LongPress|Swipe|MultiSwipe|TouchDown|TouchMove|TouchUp|Scroll|ClickKey|LongPressKey|KeyDown|KeyUp|InputText|StartApp|StopApp|Command|Shell|Screencap|Custom|OCR|TemplateMatch|FeatureMatch|ColorMatch|And|Or|NeuralNetworkClassify|NeuralNetworkDetect)\\b|\\b(?:timeout|max_hit|rate_limit|roi|target|begin|end|box_index)\\b|\\b-?\\d+(?:\\.\\d+)?\\b")
    fun ranges(text: String): List<Pair<TextRange, TextAttributesKey>> = tokens.findAll(text).map { match ->
        TextRange(match.range.first, match.range.last + 1) to when {
            match.value.first().isDigit() || match.value.startsWith('-') -> Colors.NUMBER
            match.value in keywords -> Colors.KEYWORD
            match.value in recognitions -> Colors.CLASS_REFERENCE
            match.value.first().isUpperCase() -> Colors.FUNCTION_CALL
            else -> Colors.INSTANCE_FIELD
        }
    }.toList() + Regex("→\\s*([^\\s{}·，,；;]+)").findAll(text).map { match ->
        val name = match.groups[1]!!
        TextRange(name.range.first, name.range.last + 1) to Colors.FUNCTION_DECLARATION
    }.toList()
}
