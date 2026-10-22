package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JComboBox

class PipelineImagePreviewTest : BasePlatformTestCase() {
    fun testRoiSessionAppliesOnlySelectedRoiAndSupportsUndoAndConflicts() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"And","all_of":[{"recognition":"OCR","roi":[10,20,30,40]}, {"recognition":"OCR","roi":[50,60,70,80]}],"action":"Click"}}""") as JsonFile
        val document = myFixture.editor.document
        val node = node(source)
        val operations = PipelineVisualAssets.leaves(PipelineVisualModel.operation(node))
        val annotation = PipelinePreviewModel.annotations(operations)[1]
        val session = PipelineRoiEditSession(project, operations)
        val original = document.text
        assertNull(session.unavailable(annotation))
        assertTrue(session.apply(annotation, Rectangle(100,110,120,130)))
        assertTrue(document.text.contains("\"roi\":[10,20,30,40]"))
        assertTrue(document.text.contains("[100, 110, 120, 130]"))
        val hosted = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        assertTrue(UndoManager.getInstance(project).isUndoAvailable(hosted))
        UndoManager.getInstance(project).undo(hosted)
        assertEquals(original, document.text)
        WriteCommandAction.runWriteCommandAction(project, Runnable { document.insertString(0, " ") })
        val changed = document.text
        val previous = com.intellij.openapi.ui.TestDialogManager.setTestDialog { com.intellij.openapi.ui.Messages.OK }
        try { assertFalse(session.apply(annotation, Rectangle(1,2,3,4))) }
        finally { com.intellij.openapi.ui.TestDialogManager.setTestDialog(previous) }
        assertEquals(changed, document.text)
    }

    fun testNativeViewerZoomPanCoordinatesAndCancelNeverModifyDocument() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"OCR","roi":[10,20,100,120],"roi_offset":[20,30,0,0],"action":"Click"},"B":{"recognition":"OCR","roi":[400,400,100,80]}}""") as JsonFile
        val operations = listOf(PipelineVisualModel.operation(node(source)), PipelineVisualModel.operation(node(source, "B")), PipelineVisualModel.operation(node(source), true))
        val original = myFixture.editor.document.text
        val preview = PipelineImagePreview(project, BufferedImage(1000,800,BufferedImage.TYPE_INT_RGB), operations,
            roiSession = PipelineRoiEditSession(project, operations))
        Disposer.register(testRootDisposable, preview)
        fun layout(component: Component) { (component as? java.awt.Container)?.let { it.doLayout(); it.components.forEach(::layout) } }
        preview.setSize(700,600); repeat(3) { layout(preview) }
        val chooser = descendants(preview).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-preview-annotations" }
        val picture = descendants(preview).filterIsInstance<org.intellij.images.ui.ImageComponent>().single()
        assertNotNull(picture.document.value)
        val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(org.intellij.images.ui.ImageComponentDecorator.DATA_KEY, preview.viewer).build()
        val zoomIn = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("Images.Editor.ZoomIn")
        val nativeEvent = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(zoomIn, null,
            org.intellij.images.editor.actionSystem.ImageEditorActions.ACTION_PLACE, context)
        zoomIn.update(nativeEvent)
        assertTrue("原生放大按钮应可用", nativeEvent.presentation.isEnabled)
        val nativeZoom = preview.viewer.zoomModel.zoomFactor
        zoomIn.actionPerformed(nativeEvent)
        assertTrue(preview.viewer.zoomModel.zoomFactor > nativeZoom)
        preview.viewer.zoomModel.zoomFactor = 2.0
        repeat(3) { layout(preview) }
        assertEquals(Point(100,100), preview.imagePoint(Point(202,202)))
        val zoom = preview.viewer.zoomModel.zoomFactor
        val event = MouseWheelEvent(picture, MouseEvent.MOUSE_WHEEL, 0, 0, 200,200,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-1)
        picture.dispatchEvent(event)
        assertTrue(preview.viewer.zoomModel.zoomFactor > zoom)
        preview.viewer.zoomModel.zoomFactor = 1.0
        repeat(3) { layout(preview) }
        assertEquals(0, chooser.selectedIndex)
        picture.components.filterIsInstance<javax.swing.JButton>().single {
            it.toolTipText?.startsWith("调整 1.") == true
        }.doClick()
        assertNotNull(preview.region)
        assertEquals(Rectangle(10,20,100,120), preview.region)
        assertEquals(0, chooser.selectedIndex)
        assertTrue(chooser.isEnabled)
        assertTrue(picture.document.value!!.getRGB(400,450) != java.awt.Color.BLACK.rgb)
        val draft = Rectangle(preview.region!!)
        chooser.selectedIndex = 2
        assertEquals(draft, preview.region)
        chooser.selectedIndex = 0
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_PRESSED, 0, 0, 52,72,1,false,MouseEvent.BUTTON1))
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, 72,92,0,false,MouseEvent.NOBUTTON))
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_RELEASED, 0, 0, 72,92,1,false,MouseEvent.BUTTON1))
        assertTrue(preview.hasChanges()); assertEquals(Rectangle(30,40,100,120), preview.region)
        preview.stopEditing()
        assertFalse(preview.hasChanges()); assertEquals(original, myFixture.editor.document.text)
        assertEquals(0, chooser.selectedIndex)
        chooser.selectedIndex = 3
        preview.startEditing(3)
        assertNull(preview.region)
        val scroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, picture) as javax.swing.JScrollPane
        val before = scroll.viewport.viewPosition
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_PRESSED,0,0,300,300,1,false,MouseEvent.BUTTON1))
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_DRAGGED,0,MouseEvent.BUTTON1_DOWN_MASK,250,250,0,false,MouseEvent.NOBUTTON))
        assertTrue(scroll.viewport.viewPosition.x > before.x)
    }

    fun testDragHandlesResizeMoveClampAndReverseDrawing() {
        val rectangle = Rectangle(10,20,100,120)
        assertEquals(Rectangle(10,20,150,180), PipelineRoiGesture(Point(110,140), rectangle,300,300,2).move(Point(160,200)))
        assertEquals(Rectangle(0,0,100,120), PipelineRoiGesture(Point(40,50), rectangle,300,300,2).move(Point(-100,-100)))
        assertEquals(Rectangle(150,160,50,40), PipelineRoiGesture(Point(200,200), rectangle,300,300,2).move(Point(150,160)))
        assertEquals(Rectangle(10,5,100,135), PipelineRoiGesture(Point(60,20), rectangle,300,300,2).move(Point(60,5)))
    }

    fun testOffsetDragOnlyWritesOffsetAndKeepsOriginalAndSelection() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"OCR","roi":[10,20,100,120],"roi_offset":[20,30,0,0]}}""") as JsonFile
        val operations = listOf(PipelineVisualModel.operation(node(source)))
        val original = myFixture.editor.document.text
        val preview = PipelineImagePreview(project, BufferedImage(1000,800,BufferedImage.TYPE_INT_RGB), operations,
            roiSession = PipelineRoiEditSession(project, operations))
        Disposer.register(testRootDisposable, preview)
        fun layout(component: Component) { (component as? java.awt.Container)?.let { it.doLayout(); it.components.forEach(::layout) } }
        preview.setSize(700,600)
        preview.viewer.zoomModel.zoomFactor = 1.0
        repeat(3) { layout(preview) }
        val picture = descendants(preview).filterIsInstance<org.intellij.images.ui.ImageComponent>().single()
        val chooser = descendants(preview).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-preview-annotations" }
        picture.components.filterIsInstance<javax.swing.JButton>().single { it.toolTipText?.startsWith("修改 roi_offset") == true }.doClick()
        assertEquals(Rectangle(30,50,100,120), preview.region)
        assertEquals(0, chooser.selectedIndex)
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_PRESSED, 0, 0, 62,82,1,false,MouseEvent.BUTTON1))
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, 82,102,0,false,MouseEvent.NOBUTTON))
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_RELEASED, 0, 0, 82,102,1,false,MouseEvent.BUTTON1))
        assertEquals(Rectangle(50,70,100,120), preview.region)
        descendants(preview).filterIsInstance<javax.swing.JButton>().single { it.toolTipText == "应用 offset（保留原始 ROI）" }.doClick()
        assertEquals(original.replace("[20,30,0,0]", "[40, 50, 0, 0]"), myFixture.editor.document.text)
        preview.editOffset(1)
        assertEquals("再次编辑应使用更新后的 offset", Rectangle(50,70,100,120), preview.region)
        preview.stopEditing()
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testUnrelatedEffectiveOverridesDoNotDisableRoiEditing() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"OCR","roi":[10,20,30,40],"desc":"原说明"}}""") as JsonFile
        val base = node(source)
        val effective = base.copy(rawJson = base.rawJson.replace("原说明", "任务覆盖说明"),
            previewSources = listOf(PipelinePreviewSource(base.rawJson, base.sourceFile)))
        val operations = listOf(PipelineVisualModel.operation(effective))
        val session = PipelineRoiEditSession(project, operations)
        val annotation = PipelinePreviewModel.annotations(operations).single()
        assertNull(session.unavailable(annotation))
        assertTrue(session.apply(annotation, listOf(50,60,70,80)))
        assertTrue(myFixture.editor.document.text.contains("\"desc\":\"原说明\""))
        assertTrue(myFixture.editor.document.text.contains("[50, 60, 70, 80]"))
    }

    fun testOriginalRoiAndOffsetWriteIndependentlyAndUndoSeparately() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":{"type":"OCR","param":{
            "roi":[/* @roi [10,20,30,40] */1,2,3,4],"roi_offset":[100,0,0,0]}}}}""") as JsonFile
        val operations = listOf(PipelineVisualModel.operation(node(source)))
        val session = PipelineRoiEditSession(project, operations)
        val original = myFixture.editor.document.text
        assertTrue(session.apply(PipelinePreviewModel.annotations(operations).single(), Rectangle(50,60,70,80)))
        val baseEdited = myFixture.editor.document.text
        assertTrue(baseEdited.contains("@roi [50, 60, 70, 80]"))
        assertTrue(baseEdited.contains("*/1,2,3,4]"))
        assertTrue(baseEdited.contains("\"roi_offset\":[100,0,0,0]"))
        val current = session.currentOperation(operations.single())
        assertTrue(session.applyOffset(PipelinePreviewModel.annotations(listOf(current)).single(), listOf(-5,6,7,8)))
        assertEquals(baseEdited.replace("[100,0,0,0]", "[-5, 6, 7, 8]"), myFixture.editor.document.text)
        val hosted = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(hosted)
        assertEquals(baseEdited, myFixture.editor.document.text)
        UndoManager.getInstance(project).undo(hosted)
        assertEquals(original, myFixture.editor.document.text)

        val noOffset = current.copy(parameters = current.parameters - "roi_offset", parameterPaths = current.parameterPaths - "roi_offset")
        val owner = PipelineOperationEdits.objectValue(project, original.replace(",\"roi_offset\":[100,0,0,0]", ""))!!.findProperty("A")!!.value as JsonObject
        val edit = PipelineOperationEdits.offsetEdit(owner, noOffset, "roi", listOf(1,2,3,4))!!
        val changed = edit.applyTo(original.replace(",\"roi_offset\":[100,0,0,0]", ""))
        assertTrue(changed.contains("@roi [10,20,30,40]"))
        val parsed = PipelineOperationEdits.objectValue(project, changed)!!.findProperty("A")!!.value!!
        assertEquals("[1, 2, 3, 4]", PipelineOperationEdits.at(parsed, listOf("recognition","param","roi_offset"))!!.text)
    }

    fun testNumericRoiEditingPreservesNegativeCoordinatesAndSizes() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"OCR","roi":[10,20,30,40],"roi_offset":[1,2,3,4]}}""") as JsonFile
        val operations = listOf(PipelineVisualModel.operation(node(source)))
        val original = myFixture.editor.document.text
        val preview = PipelineImagePreview(project, BufferedImage(1000,800,BufferedImage.TYPE_INT_RGB), operations,
            roiSession = PipelineRoiEditSession(project, operations))
        Disposer.register(testRootDisposable, preview)
        preview.startEditing(1)
        val previous = com.intellij.openapi.ui.TestDialogManager.setTestInputDialog { "-20, -30, -40, -50" }
        try {
            descendants(preview).filterIsInstance<javax.swing.JButton>().single { it.toolTipText == "输入原始 ROI 坐标" }.doClick()
        } finally { com.intellij.openapi.ui.TestDialogManager.setTestInputDialog(previous) }
        assertEquals(Rectangle(940,720,40,50), preview.region)
        descendants(preview).filterIsInstance<javax.swing.JButton>().single { it.toolTipText == "应用原始 ROI（保留 offset）" }.doClick()
        assertEquals(original.replace("[10,20,30,40]", "[-20, -30, -40, -50]"), myFixture.editor.document.text)
        val hosted = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(hosted)
        assertEquals(original, myFixture.editor.document.text)
    }

    private fun node(file: JsonFile, name: String = "A"): PipelineNodeLocation {
        val property = (file.topLevelValue as JsonObject).findProperty(name)!!
        return PipelineNodeLocation(name, property.nameElement.textRange, property.textRange, sourceFile=file.virtualFile, rawJson=property.value!!.text)
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        (component as? java.awt.Container)?.components.orEmpty().flatMap(::descendants)
}
