package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.testFramework.LightPlatformTestCase
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel

class PipelineVisualLayoutTest : LightPlatformTestCase() {
    fun testMeasuringWrappedTextDoesNotMutateLabelsOrInvalidateBoxLayoutWhileItIsComputing() {
        val label = PipelineWrappingLabel("长文本与路径AccountSwitch/Confirm.png".repeat(30))
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(label) }
        var textChanges = 0
        label.addPropertyChangeListener("text") { textChanges++ }
        repeat(20) {
            panel.setSize(260 + it % 3 * 100, 900)
            panel.preferredSize
            panel.doLayout()
            label.preferredSize
        }
        assertEquals("测量不能触发文本变更和父布局重入", 0, textChanges)
        assertTrue(label.preferredSize.height > label.getFontMetrics(label.font).height)
    }

    fun testNarrowDetailsWrapTextWithoutStretchingRowsToFillThePanel() {
        val label = PipelineWrappingLabel("target：本节点识别结果；搜索区域 [100, 200, 300, 400]。".repeat(5))
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(label) }
        panel.size = Dimension(600, 900)
        panel.doLayout()
        val wideHeight = label.preferredSize.height
        panel.size = Dimension(260, 900)
        repeat(3) { panel.doLayout() }
        val narrowHeight = label.preferredSize.height
        assertTrue("窄面板应增加行数", narrowHeight > wideHeight)
        assertEquals(narrowHeight, label.maximumSize.height)
        assertTrue(label.height < panel.height)
        assertTrue(label.width <= panel.width)
        val path = PipelineWrappingLabel("images/" + "AccountSwitchLogoutConfirmation".repeat(12) + ".png")
        panel.removeAll()
        panel.add(path)
        panel.size = Dimension(260, 900)
        repeat(3) { panel.doLayout() }
        assertTrue("没有空格的长路径也必须换行", path.preferredSize.height > path.getFontMetrics(path.font).height)
    }

}
