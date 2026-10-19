package com.maapipeline.executionlens.pipeline

import com.intellij.util.ui.JBUI
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PipelineFlowTooltipTest {
    @Test
    fun conditionsAndControlFlowPrecedeSourceAndOperationHints() {
        SwingUtilities.invokeAndWait {
            val label = JLabel()
            val html = PipelineFlowTooltip.render(
                listOf(PipelineConditionDetail("And（全部满足）"), PipelineConditionDetail("OCR · 文本=退出确认", 1),
                    PipelineConditionDetail("TemplateMatch · 模板=ConfirmButton.png", 1)),
                label.getFontMetrics(label.font), jumpBack = true, parentName = "Parent",
                anchorHint = "Anchor Resume；按当前显示路径推导 → Target；未设置或清除时跳过此候选",
                sourcePath = "assets/resource/pipeline/Example.json", operationHint = "点击查看详情",
            )
            val ordered = listOf("And", "OCR", "TemplateMatch", "JumpBack", "Anchor Resume", "文件：", "点击查看详情")
                .map(html::indexOf)
            assertTrue(ordered.all { it >= 0 })
            assertTrue(ordered.zipWithNext().all { (first, second) -> first < second })
            assertTrue(html.contains("margin-left:${JBUI.scale(14)}px"))
            assertTrue(html.contains("父节点：Parent"))
            assertTrue(html.contains("不重新执行父节点动作"))
        }
    }

    @Test
    fun nativeTooltipWrapsLongUnbrokenValuesAndEscapesJsonContent() {
        SwingUtilities.invokeAndWait {
            val label = JLabel()
            val condition = "OCR · 文本=<script>&" + "无空格的超长识别文字".repeat(30)
            val html = PipelineFlowTooltip.render(listOf(PipelineConditionDetail("And（全部满足）"),
                PipelineConditionDetail(condition, 1), PipelineConditionDetail("TemplateMatch · 模板=" + "LongFileName".repeat(40), 1)),
                label.getFontMetrics(label.font), sourcePath = "assets/" + "LongPath".repeat(70) + "/Target.json")
            assertTrue(html.contains("&lt;script&gt;&amp;"))
            assertFalse(html.contains("<script>"))
            assertTrue(html.contains("<br>"))
            assertTrue(html.contains("Target.json"))
            label.toolTipText = html
            val tooltip = label.createToolTip().apply { tipText = html }
            val size = tooltip.preferredSize
            assertTrue(size.width <= JBUI.scale(500), "提示过宽：$size")
            assertTrue(size.height > JBUI.scale(50), "没有产生多行：$size")
            assertTrue(size.height < JBUI.scale(600), "提示过高：$size")
            tooltip.setSize(size)
            val canvas = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = canvas.createGraphics()
            try { tooltip.paint(graphics) } finally { graphics.dispose() }
        }
    }

    @Test
    fun errorChainDoesNotClaimToRetryTheParent() {
        SwingUtilities.invokeAndWait {
            val label = JLabel()
            val html = PipelineFlowTooltip.render(emptyList(), label.getFontMetrics(label.font),
                jumpBack = true, parentName = "Parent", fieldName = "on_error")
            assertTrue(html.contains("不触发回跳"))
            assertFalse(html.contains("第一项重试"))
        }
    }
}
