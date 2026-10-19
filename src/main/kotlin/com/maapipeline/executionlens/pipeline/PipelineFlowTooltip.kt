package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.JBUI
import java.awt.FontMetrics
import java.awt.Font

internal object PipelineFlowTooltip {
    fun render(
        conditions: List<PipelineConditionDetail>,
        metrics: FontMetrics,
        jumpBack: Boolean = false,
        parentName: String? = null,
        fieldName: String = "next",
        anchorHint: String? = null,
        sourcePath: String? = null,
        operationHint: String? = null,
    ): String = buildString {
        append("<html><body>")
        fun line(text: String, depth: Int = 0, bold: Boolean = false) {
            val indent = depth.coerceIn(0, 4) * JBUI.scale(14)
            val normalized = text.replace(Regex("\\s+"), " ").trim()
            val bounded = if (normalized.length > 200) normalized.take(100) + "…" + normalized.takeLast(80) else normalized
            val width = (JBUI.scale(420) - indent).coerceAtLeast(JBUI.scale(200))
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var measured = 0
            val measuredFont = if (bold) metrics.font.deriveFont(Font.BOLD) else metrics.font
            // 路径、节点名和识别文本可能没有空格，按实际字体宽度换行，避免撑满整个屏幕。
            bounded.codePoints().forEach { codePoint ->
                val character = String(Character.toChars(codePoint))
                val characterWidth = if (bold) measuredFont
                    .getStringBounds(character, metrics.fontRenderContext).width.toInt() + 1 else metrics.stringWidth(character)
                if (measured + characterWidth > width && current.isNotEmpty()) {
                    parts += current.toString()
                    current.clear()
                    measured = 0
                }
                current.append(character)
                measured += characterWidth
            }
            if (current.isNotEmpty()) parts += current.toString()
            append("<div style='margin-left:${indent}px'>")
            if (bold) append("<b>")
            append(parts.joinToString("<br>") { StringUtil.escapeXmlEntities(it) })
            if (bold) append("</b>")
            append("</div>")
        }
        conditions.take(12).forEach { line(it.text, it.depth, it.depth == 0) }
        if (jumpBack) {
            append("<br>")
            line("JumpBack", bold = true)
            if (fieldName == "on_error") line("on_error 错误处理链不触发回跳。", 1)
            else {
                line("子链正常结束后，从父 next 第一项重试。", 1)
                parentName?.let { line("父节点：$it", 1) }
                line("不重新执行父节点动作。", 1)
            }
        }
        anchorHint?.takeIf { it.isNotBlank() }?.let { hint ->
            append("<br>")
            hint.split('；').take(4).forEachIndexed { index, text -> line(text, if (index == 0) 0 else 1, index == 0) }
        }
        sourcePath?.let {
            append("<br>")
            line("文件：$it")
        }
        operationHint?.let { line(it) }
        append("</body></html>")
    }
}
