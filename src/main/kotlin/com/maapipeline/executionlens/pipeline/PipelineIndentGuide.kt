package com.maapipeline.executionlens.pipeline

import com.intellij.ui.JBColor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JComponent

internal class PipelineIndentGuide(private val depth: Int) : JComponent() {
    private val step = JBUI.scale(12)

    init {
        isOpaque = false
        val width = (depth * step).coerceAtLeast(step)
        preferredSize = Dimension(width, 1)
        minimumSize = preferredSize
        maximumSize = Dimension(width, Int.MAX_VALUE)
    }

    override fun paintComponent(graphics: Graphics) {
        val color = EditorColorsManager.getInstance().globalScheme
            .getColor(EditorColors.INDENT_GUIDE_COLOR)
            ?: JBColor.border()
        graphics.color = color
        for (level in 0 until depth) {
            val x = level * step + step / 2
            graphics.drawLine(x, 0, x, height)
        }
    }
}
