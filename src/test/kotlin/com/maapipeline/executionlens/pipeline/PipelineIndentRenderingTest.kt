package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JPanel

class PipelineIndentRenderingTest : LightPlatformTestCase() {
    fun testContinuousGuidesUseTheCurrentEditorSchemeAndOnlyHighlightTheActiveBlock() {
        val manager = EditorColorsManager.getInstance()
        val original = manager.globalScheme
        val scheme = original.clone() as EditorColorsScheme
        try {
            manager.setGlobalScheme(scheme)
            val panel = PipelineVirtualRowsPanel()
            val depths = listOf(0, 1, 2, 1, 0)
            panel.setRows(depths.mapIndexed { index, depth ->
                PipelineVirtualRow("row-$index", 20, "content", depth) {
                    JPanel().apply { isOpaque = false; preferredSize = Dimension(300, 20) }
                }
            })
            panel.setSize(300, 100)
            for (background in listOf(Color.WHITE, Color(30, 31, 34))) {
                val regular = if (background == Color.WHITE) Color(170, 180, 190) else Color(70, 80, 90)
                val active = if (background == Color.WHITE) Color(40, 110, 170) else Color(100, 170, 230)
                scheme.setColor(EditorColors.INDENT_GUIDE_COLOR, regular)
                scheme.setColor(EditorColors.SELECTED_INDENT_GUIDE_COLOR, active)
                panel.activateRow("row-2")
                fun render(): BufferedImage = BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB).also { image ->
                    image.createGraphics().also { graphics ->
                        try {
                            graphics.color = background
                            graphics.fillRect(0, 0, 300, 100)
                            panel.paint(graphics)
                        } finally { graphics.dispose() }
                    }
                }
                val image = render()
                val outerX = JBUI.scale(24)
                val innerX = JBUI.scale(36)
                for (pixelY in 21..89) assertEquals("外层线不应在行间断开：$pixelY", regular.rgb, image.getRGB(outerX, pixelY))
                for (pixelY in 41..69) assertEquals(active.rgb, image.getRGB(innerX, pixelY))
                assertEquals(background.rgb, image.getRGB(innerX, 80))
                panel.activateRow("row-0")
                val changed = render()
                assertEquals(active.rgb, changed.getRGB(outerX, 50))
                assertEquals(regular.rgb, changed.getRGB(innerX, 50))
            }
        } finally { manager.setGlobalScheme(original) }
    }
}
