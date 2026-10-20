package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.LightPlatformTestCase
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel

class PipelineVisualLayoutTest : LightPlatformTestCase() {
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

    fun testStructuredActionShowsEveryRoiComponentAndLabelsDynamicSources() {
        val source = myFixtureSource()
        val details = PipelineVisualDetails(project, source.virtualFile, { null }, {})
        val node = PipelineNodeLocation("Click", TextRange(0, 1), TextRange(0, 1), sourceFile = source.virtualFile,
            rawJson = """{"recognition":"OCR","roi":[10,20,30,40],"action":"Click","target":[1,2,3,4]}""")
        val panel = details.operation(PipelineVisualModel.operation(node, true))
        val labels = panel.components.filterIsInstance<PipelineWrappingLabel>()
        assertTrue(labels.any { it.text.contains("[1, 2, 3, 4]") })
        val dynamic = PipelineVisualModel.region(PipelineVisualModel.operation(node.copy(rawJson = """{"recognition":"OCR","roi":[10,20,30,40],"action":"Click"}"""), true))
        assertNull(dynamic.coordinates)
        assertEquals(listOf(10, 20, 30, 40), dynamic.searchCoordinates)
    }

    fun testPreviewLeavesExplanationsInDetailsAndKeepsTheThirdDynamicTargetSelectable() {
        val source = myFixtureSource()
        val details = PipelineVisualDetails(project, source.virtualFile, { null }, {})
        val node = PipelineNodeLocation("Confirm", TextRange(0, 1), TextRange(0, 1), sourceFile = source.virtualFile,
            rawJson = """{"recognition":"And","box_index":1,"all_of":[
                {"recognition":"OCR","expected":"退出确认文字","roi":[20,20,60,50]},
                {"recognition":"TemplateMatch","template":"Confirm.png","roi":[20,40,60,30]}
            ],"action":"Click"}""")
        val operations = PipelineVisualAssets.leaves(PipelineVisualModel.operation(node)) + PipelineVisualModel.operation(node, true)
        val annotations = PipelinePreviewModel.annotations(operations)
        fun descendants(component: java.awt.Component): List<java.awt.Component> = listOf(component) +
            (component as? java.awt.Container)?.components.orEmpty().flatMap(::descendants)
        val labels = descendants(details.operation(PipelineVisualModel.operation(node))).filterIsInstance<PipelineWrappingLabel>()
        assertTrue(labels.any { it.text.contains("退出确认文字") })
        assertTrue(labels.any { it.toolTipText?.contains("归一化相关系数") == true })
        assertTrue(annotations[0].imageLines().contains("roi=[20, 20, 60, 50]"))
        assertTrue(annotations[0].imageLines().any { it.contains("退出确认文字") })
        assertTrue(annotations[1].imageLines().any { it.contains("Confirm.png") })
        assertTrue(annotations[2].imageLines().any { it.contains("← 2 结果") })
        val original = java.awt.image.BufferedImage(120, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val content = details.previewContent(original, PipelineVisualAssets.overlay(original, operations), operations)
        val chooser = descendants(content).filterIsInstance<javax.swing.JComboBox<*>>().single()
        assertEquals(4, chooser.itemCount)
        chooser.selectedIndex = 3
        assertTrue(chooser.selectedItem.toString().contains("3. Click"))
        assertTrue(descendants(content).filterIsInstance<PipelineWrappingLabel>().isEmpty())
        assertTrue(descendants(content).filterIsInstance<com.intellij.ui.OnePixelSplitter>().isEmpty())
        assertEquals(1, descendants(content).filterIsInstance<com.intellij.ui.components.JBScrollPane>().size)
    }

    private fun myFixtureSource(): com.intellij.psi.PsiFile = com.intellij.psi.PsiManager.getInstance(project)
        .findFile(com.intellij.testFramework.LightVirtualFile("test.json", com.intellij.json.JsonFileType.INSTANCE, "{}"))!!
}
