package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.testFramework.LightPlatformTestCase
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.Container
import java.awt.event.MouseWheelEvent

class PipelineVisualLayoutTest : LightPlatformTestCase() {
    fun testWheelScrollingKeepsWrappedFlowHeightAndComponentsStable() {
        var created = 0
        val panel = PipelineRowsPanel()
        val scroll = JBScrollPane(panel)
        scroll.setSize(600, 400)
        fun layout(component: Container) {
            component.doLayout()
            component.components.filterIsInstance<Container>().forEach(::layout)
        }
        panel.setRows((0 until 600).map { index ->
            PipelineRow("row-$index", "original") {
                created++
                JPanel(java.awt.BorderLayout()).apply {
                    add(PipelineWrappingLabel("$index 条件、动作与运行覆盖的说明。".repeat(if (index % 4 == 0) 12 else 1)))
                }
            }
        })
        panel.addNotify()
        try {
            layout(scroll)
            UIUtil.dispatchAllInvocationEvents()
            layout(scroll)
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum / 2
            UIUtil.dispatchAllInvocationEvents()
            layout(scroll)
            val height = panel.preferredSize.height
            val count = created
            assertTrue(panel.components.all { it.height > 0 })
            val anchor = panel.scrollAnchor()
            for (direction in listOf(1, 1, -1, -1)) {
                val before = scroll.viewport.viewPosition.y
                val event = MouseWheelEvent(scroll, MouseWheelEvent.MOUSE_WHEEL, 0L, 0,
                    100, 100, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, direction)
                scroll.mouseWheelListeners.forEach { it.mouseWheelMoved(event) }
                UIUtil.dispatchAllInvocationEvents()
                layout(scroll)
                val after = scroll.viewport.viewPosition.y
                assertTrue("单次滚动应小幅移动：$before → $after", (after - before) * direction in 1..200)
                assertEquals("滚动不能改变内容高度", height, panel.preferredSize.height)
                assertEquals("滚动不能重建行组件", count, created)
            }
            panel.restoreAnchor(anchor!!.first, anchor.second)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(anchor, panel.scrollAnchor())
            val label = panel.components.filterIsInstance<Container>().flatMap { it.components.toList() }
                .filterIsInstance<PipelineWrappingLabel>().first()
            label.select(0, 2)
            assertEquals(label.text.take(2), label.selectedText)
        } finally { panel.removeNotify() }
    }

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
        val heading = PipelineWrappingLabel("1. if TemplateMatch → StashBackpackStashQuick · 任务覆盖 {").apply {
            font = font.deriveFont(java.awt.Font.BOLD)
        }
        val header = JPanel(java.awt.BorderLayout()).apply {
            add(javax.swing.JLabel("▾"), java.awt.BorderLayout.WEST)
            add(heading, java.awt.BorderLayout.CENTER)
        }
        panel.removeAll()
        panel.add(header)
        for (available in listOf(260, 600, 180)) {
            panel.size = Dimension(available, 900)
            repeat(3) { panel.doLayout(); header.doLayout() }
            val lastCharacter = heading.modelToView2D(heading.document.length - 1)
            assertNotNull(lastCharacter)
            assertTrue("标题末尾应完整显示，不能裁掉换行内容", lastCharacter.maxY <= heading.height)
            assertTrue("标题末尾不能超出可见宽度", lastCharacter.maxX <= heading.width)
            heading.selectAll()
            assertEquals(heading.text, heading.selectedText)
        }
    }

}
