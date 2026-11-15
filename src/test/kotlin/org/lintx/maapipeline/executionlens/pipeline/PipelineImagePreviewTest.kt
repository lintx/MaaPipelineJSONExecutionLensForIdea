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
    fun testAdjacentConnectionsStaySeparateAcrossZoomAndPlacementOrder() {
        for (scale in listOf(0.75, 1.0, 1.5)) for (reverse in listOf(false, true)) {
            fun transformed(rectangle: Rectangle) = Rectangle(43 + (rectangle.x * scale).toInt(), 19 + (rectangle.y * scale).toInt(),
                (rectangle.width * scale).toInt(), (rectangle.height * scale).toInt())
            val left = transformed(Rectangle(583, 128, 25, 25))
            val right = transformed(Rectangle(679, 128, 25, 25))
            val leftCard = transformed(Rectangle(583, 159, 382, 226))
            val rightCard = transformed(Rectangle(203, 128, 375, 264))
            val view = transformed(Rectangle(0, 0, 1280, 720))
            val anchors = listOf(left to leftCard, right to rightCard).let { if (reverse) it.reversed() else it }
            val paths = PipelineAnnotationConnections.routes(anchors, listOf(left, right), listOf(leftCard, rightCard), view, 5)
            assertEquals("截图场景应同时保留两条安全连线：scale=$scale reverse=$reverse", 2, paths.filterNotNull().size)
            assertConnectionPixelsDisjoint(paths.filterNotNull(), view)
        }
    }

    fun testExistingConnectionBlocksOverlapsCrossingsAndEndpoints() {
        val roi = Rectangle(400, 40, 25, 25)
        val card = Rectangle(100, 40, 200, 150)
        val view = Rectangle(0, 0, 1000, 600)
        for (occupied in listOf(listOf(Point(320, 52), Point(390, 52)),
            listOf(Point(355, 30), Point(355, 90)), listOf(Point(320, 30), Point(390, 74)))) {
            val path = PipelineAnnotationConnections.route(roi, card, listOf(roi), listOf(card), view, 5, listOf(occupied))
            assertNotNull("应能避开已占用的横线、竖线或斜线", path)
            assertConnectionPixelsDisjoint(listOf(occupied, path!!), view)
        }
        val enclosed = listOf(Point(398, 38), Point(427, 38), Point(427, 67), Point(398, 67), Point(398, 38))
        assertNull("连接端点也不能穿过已有连线；无路时省略", PipelineAnnotationConnections.route(roi, card,
            listOf(roi), listOf(card), view, 5, listOf(enclosed)))
    }

    private fun assertConnectionPixelsDisjoint(paths: List<List<Point>>, view: Rectangle) {
        val occupied = java.util.BitSet()
        paths.forEach { path ->
            val mask = BufferedImage(view.x + view.width, view.y + view.height, BufferedImage.TYPE_INT_ARGB)
            mask.createGraphics().apply {
                color = java.awt.Color.WHITE
                // 比实际 1px 连线更宽的掩码，连线之间必须留白，不能仅仅不共用中心像素。
                stroke = java.awt.BasicStroke(3f)
                path.zipWithNext().forEach { (a, b) -> drawLine(a.x, a.y, b.x, b.y) }
                dispose()
            }
            val pixels = mask.getRGB(0, 0, mask.width, mask.height, null, 0, mask.width)
            val current = java.util.BitSet()
            pixels.forEachIndexed { index, pixel -> if (pixel ushr 24 != 0) current.set(index) }
            assertFalse("不同颜色的连接线不得重叠、交叉或贴在一起", occupied.intersects(current))
            occupied.or(current)
        }
    }

    fun testConnectionsRouteAboveAdjacentRoiAndNeverCrossItsFrame() {
        val left = Rectangle(583, 128, 25, 25)
        val right = Rectangle(679, 128, 25, 25)
        val rightCard = Rectangle(203, 128, 375, 194)
        val leftCard = Rectangle(583, 159, 382, 134)
        val view = Rectangle(0, 0, 1280, 720)
        val path = PipelineAnnotationConnections.route(right, rightCard, listOf(left, right), listOf(leftCard, rightCard), view, 5)
        assertNotNull("截图场景应存在绕开左侧 ROI 的连接路径", path)
        path!!.zipWithNext().forEach { (a, b) ->
            assertFalse("连线不能覆盖另一 ROI 的上边框", left.intersectsLine(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble()))
            assertFalse("连线不能穿过其他说明", leftCard.intersectsLine(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble()))
        }
        assertNull("没有安全路径时省略连线，不能牺牲 ROI 边框", PipelineAnnotationConnections.route(right, rightCard,
            listOf(left, right, view), listOf(leftCard, rightCard), view, 5))
    }

    fun testAnnotationButtonsFollowTheirNumberedTitlesWhileScrolling() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"And","all_of":[
            {"recognition":"TemplateMatch","roi":[50,50,25,25],"template":"long.png"},
            {"recognition":"OCR","roi":[50,50,25,25],"expected":"第二组按钮必须与编号 2 同步滚动"}
        ]}}""") as JsonFile
        val picture = javax.swing.JPanel(null).apply { setSize(1000, 800) }
        val templates = mapOf(1 to listOf(PipelineVisualAssets.TemplatePreview("long.png", BufferedImage(100, 450, BufferedImage.TYPE_INT_RGB))))
        val overlay = PipelinePreviewOverlay(picture, templates) { Rectangle(it) }
        overlay.update(PipelineVisualAssets.overlayRegions(1000, 800, PipelineVisualAssets.leaves(PipelineVisualModel.operation(node(source)))))
        val actions = (1..2).associateWith { listOf(javax.swing.JButton(), javax.swing.JButton()) }
        overlay.position(actions)
        val content = actions.getValue(2).first().parent as javax.swing.JPanel
        val scroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, content) as javax.swing.JScrollPane
        scroll.doLayout()
        scroll.viewport.viewSize = content.preferredSize
        actions.forEach { (number, buttons) -> assertButtonsAtTitle(buttons, number) }
        val title = content.components.filterIsInstance<javax.swing.JComponent>().single { it.getClientProperty("pipeline.annotation.number") == 2 }
        val before = javax.swing.SwingUtilities.convertPoint(title, Point(), scroll.viewport)
        scroll.viewport.viewPosition = Point(0, 160)
        val after = javax.swing.SwingUtilities.convertPoint(title, Point(), scroll.viewport)
        assertEquals(before.y - 160, after.y)
        actions.getValue(2).forEach { button ->
            assertEquals("滚动后按钮不能留在卡片顶部", after.y, javax.swing.SwingUtilities.convertPoint(button, Point(), scroll.viewport).y)
        }
    }

    fun testNearbyRoisKeepLongAnnotationsAndEveryFrameUnobscured() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"And","all_of":[
            {"recognition":"TemplateMatch","roi":[583,128,25,25],"template":"SceneManager/Depot/Main/RegionValleyIV.png"},
            {"recognition":"TemplateMatch","roi":[583,128,25,25],"template":"SceneManager/Depot/Main/RegionWuling.png"},
            {"recognition":"TemplateMatch","roi":[679,128,25,25],"template":"SceneManager/Depot/Main/SwitchButton.png"},
            {"recognition":"ColorMatch","roi":[679,128,25,25],"lower":[128],"upper":[255],"count":100}
        ]}}""") as JsonFile
        val operations = PipelineVisualAssets.leaves(PipelineVisualModel.operation(node(source)))
        val regions = PipelineVisualAssets.overlayRegions(1280, 720, operations)
        for (scale in listOf(1.0, 0.75, 1.5)) {
            val picture = javax.swing.JPanel(null).apply { setSize(1280, 720); background = java.awt.Color.WHITE }
            fun transform(rectangle: Rectangle) = Rectangle((rectangle.x * scale).toInt(), (rectangle.y * scale).toInt(),
                (rectangle.width * scale).toInt(), (rectangle.height * scale).toInt())
            val templates = (1..3).associateWith { number -> listOf(PipelineVisualAssets.TemplatePreview("Template$number.png",
                BufferedImage(18, 18, BufferedImage.TYPE_INT_RGB))) }
            val overlay = PipelinePreviewOverlay(picture, templates, ::transform)
            overlay.update(regions)
            val actions = (1..4).associateWith { listOf(javax.swing.JButton(com.intellij.icons.AllIcons.Actions.Edit),
                javax.swing.JButton(com.intellij.icons.AllIcons.Actions.Edit)) }
            overlay.position(actions)
            assertFalse("相邻小 ROI 应能在画布内找到无碰撞位置", overlay.overflow.isVisible)
            val cards = picture.components.map { it.bounds }
            assertEquals(2, cards.size)
            cards.forEachIndexed { index, card ->
                assertTrue(picture.visibleRect.contains(card))
                cards.drop(index + 1).forEach { assertFalse("说明不得互相遮挡", card.intersects(it)) }
                regions.forEach { assertFalse("说明不得遮挡相邻小 ROI", card.intersects(transform(it.rectangle))) }
            }
            actions.forEach { (number, buttons) ->
                assertTrue(javax.swing.SwingUtilities.isDescendingFrom(buttons.first(), picture))
                assertButtonsAtTitle(buttons, number)
            }
            picture.border = object : javax.swing.border.AbstractBorder() {
                override fun paintBorder(c: Component, g: java.awt.Graphics, x: Int, y: Int, width: Int, height: Int) {
                    overlay.paint(g as java.awt.Graphics2D)
                }
            }
            fun layout(component: Component) { (component as? java.awt.Container)?.let { it.doLayout(); it.components.forEach(::layout) } }
            layout(picture)
            // 检查最终像素，而不是只检查说明矩形：连接线不能改变任何 ROI 边框。
            val expectedFrames = BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB)
            expectedFrames.createGraphics().apply {
                regions.forEach { region -> PipelineVisualAssets.drawRoiFrame(this, transform(region.rectangle),
                    region.annotations.first().number - 1, region.originalOnly, region.dashed, PipelinePreviewSettings.lineWidth()) }
                dispose()
            }
            val image = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply { picture.paint(this); dispose() }
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val expected = expectedFrames.getRGB(x, y)
                if (expected ushr 24 == 255) assertEquals("ROI 边框不能被连接线改色：$x,$y", expected, image.getRGB(x, y))
            }
            val report = java.nio.file.Path.of("build", "reports", "roi-avoidance-$scale.png")
            java.nio.file.Files.createDirectories(report.parent)
            javax.imageio.ImageIO.write(image, "png", report.toFile())
        }
    }

    fun testCrowdedAnnotationsMoveOutsideImageAndKeepTheirActions() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"OCR","roi":[0,0,140,140],"expected":"拥挤时仍可选择完整说明"}}""") as JsonFile
        val picture = javax.swing.JPanel(null).apply { setSize(140, 140) }
        val overlay = PipelinePreviewOverlay(picture, emptyMap()) { Rectangle(it) }
        overlay.update(PipelineVisualAssets.overlayRegions(140, 140, listOf(PipelineVisualModel.operation(node(source)))))
        var edits = 0
        val edit = javax.swing.JButton().apply { addActionListener { edits++ } }
        overlay.position(mapOf(1 to listOf(edit)))
        assertTrue(overlay.overflow.isVisible)
        assertEquals(0, picture.componentCount)
        assertTrue(javax.swing.SwingUtilities.isDescendingFrom(edit, overlay.overflow))
        edit.doClick()
        assertEquals(1, edits)
        val text = descendants(overlay.overflow).filterIsInstance<javax.swing.text.JTextComponent>().first { it.text.contains("拥挤时") }
        text.selectAll()
        assertTrue(text.selectedText.contains("完整说明"))
        // 滚动区出现后缩放仍保持同一说明实例，避免视口宽度变化导致布局振荡。
        picture.setSize(1000, 800)
        overlay.position(mapOf(1 to listOf(edit)))
        assertTrue(overlay.overflow.isVisible)
        assertSame(text, descendants(overlay.overflow).filterIsInstance<javax.swing.text.JTextComponent>().first { it.text.contains("拥挤时") })
    }

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
        descendants(picture).filterIsInstance<javax.swing.JButton>().single {
            it.toolTipText?.startsWith("调整 1.") == true
        }.doClick()
        assertNotNull(preview.region)
        assertEquals(Rectangle(10,20,100,120), preview.region)
        assertEquals(0, chooser.selectedIndex)
        assertTrue(chooser.isEnabled)
        val editingButtons = descendants(picture).filterIsInstance<javax.swing.JButton>().filter {
            it.toolTipText in setOf("应用原始 ROI（保留 offset）", "取消调整", "输入原始 ROI 坐标")
        }
        assertEquals(3, editingButtons.size)
        assertButtonsAtTitle(editingButtons, 1)
        assertEquals("底图保持原始像素，ROI 和说明通过独立显示层展示", java.awt.Color.BLACK.rgb, picture.document.value!!.getRGB(400,450))
        assertTrue(descendants(picture).filterIsInstance<javax.swing.text.JTextComponent>().any { it.text.contains("roi=") })
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

    fun testWideAndTallViewportsKeepImageAspectAndUnscaledSelectableAnnotations() {
        val source = myFixture.configureByText("ROI.json", """{"A":{"recognition":"OCR","roi":[100,100,1000,500],"expected":"测试文字"}}""") as JsonFile
        val image = BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB)
        val marker = java.awt.Color(40,210,70)
        image.createGraphics().apply { color = marker; fillRect(900,400,100,100); dispose() }
        val operations = listOf(PipelineVisualModel.operation(node(source)))
        val preview = PipelineImagePreview(project, image, operations, roiSession = PipelineRoiEditSession(project, operations))
        Disposer.register(testRootDisposable, preview)
        fun layout(component: Component) { (component as? java.awt.Container)?.let { it.doLayout(); it.components.forEach(::layout) } }
        listOf(java.awt.Dimension(1450,600), java.awt.Dimension(600,1100)).forEach { size ->
            preview.size = size
            repeat(3) { layout(preview) }
            preview.fitToWindow()
            repeat(3) { layout(preview) }
            val picture = preview.viewer.picture
            val canvas = picture.canvasSize
            assertEquals(canvas.width + 4, picture.width)
            assertEquals(canvas.height + 4, picture.height)
            assertEquals(1280.0 / 720, canvas.width.toDouble() / canvas.height, 0.005)
            val center = preview.imagePoint(Point(2 + canvas.width / 2, 2 + canvas.height / 2))
            assertTrue(kotlin.math.abs(center.x - 640) <= 2 && kotlin.math.abs(center.y - 360) <= 2)
            assertSame(image, picture.document.value)
            val edit = descendants(picture).filterIsInstance<javax.swing.JButton>().single { it.toolTipText?.startsWith("调整 1.") == true }
            assertButtonsAtTitle(listOf(edit), 1)
            val rendered = BufferedImage(picture.width, picture.height, BufferedImage.TYPE_INT_RGB)
            rendered.createGraphics().apply { picture.paint(this); dispose() }
            var markerBounds: Rectangle? = null
            for (vertical in 0 until rendered.height) for (horizontal in 0 until rendered.width) {
                if (rendered.getRGB(horizontal, vertical) == marker.rgb) {
                    val point = Rectangle(horizontal, vertical, 1, 1)
                    markerBounds = markerBounds?.union(point) ?: point
                }
            }
            assertNotNull("实际绘制应包含原图中的定位方块", markerBounds)
            assertTrue("实际图像中的正方形不能随窗口横纵拉伸", kotlin.math.abs(markerBounds!!.width - markerBounds.height) <= 2)
            val scale = preview.viewer.zoomModel.zoomFactor
            assertEquals(2 + 950 * scale, markerBounds.centerX, 2.0)
            assertEquals(2 + 450 * scale, markerBounds.centerY, 2.0)
            val report = java.nio.file.Path.of("build", "reports", if (size.width > size.height) "preview-wide.png" else "preview-tall.png")
            java.nio.file.Files.createDirectories(report.parent)
            javax.imageio.ImageIO.write(rendered, "png", report.toFile())
        }
        val label = descendants(preview.viewer.picture).filterIsInstance<javax.swing.text.JTextComponent>().first { it.text.contains("测试文字") }
        val fontSize = label.font.size
        preview.viewer.zoomModel.zoomFactor = 2.0
        repeat(3) { layout(preview) }
        assertEquals(fontSize, label.font.size)
        assertTrue(label.isVisible)
        label.selectAll()
        assertTrue(label.selectedText.contains("测试文字"))
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
        descendants(picture).filterIsInstance<javax.swing.JButton>().single { it.toolTipText?.startsWith("修改 roi_offset") == true }.doClick()
        assertEquals(Rectangle(30,50,100,120), preview.region)
        assertEquals(0, chooser.selectedIndex)
        val editingButtons = descendants(picture).filterIsInstance<javax.swing.JButton>().filter {
            it.toolTipText in setOf("应用 offset（保留原始 ROI）", "取消调整", "输入 offset 数值")
        }
        assertEquals(3, editingButtons.size)
        assertEquals("offset 编辑操作共享说明卡片容器", 1, editingButtons.map { it.parent }.distinct().size)
        assertButtonsAtTitle(editingButtons, 1)
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_PRESSED, 0, 0, 62,82,1,false,MouseEvent.BUTTON1))
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, 82,102,0,false,MouseEvent.NOBUTTON))
        picture.dispatchEvent(MouseEvent(picture, MouseEvent.MOUSE_RELEASED, 0, 0, 82,102,1,false,MouseEvent.BUTTON1))
        assertEquals(Rectangle(50,70,100,120), preview.region)
        descendants(preview).filterIsInstance<javax.swing.JButton>().single { it.toolTipText == "应用 offset（保留原始 ROI）" }.doClick()
        assertEquals(original.replace("[20,30,0,0]", "[40, 50, 0, 0]"), myFixture.editor.document.text)
        preview.editOffset(1)
        assertEquals("再次编辑应使用更新后的 offset", Rectangle(50,70,100,120), preview.region)
        val previousInput = com.intellij.openapi.ui.TestDialogManager.setTestInputDialog { "-10, -20, 5, -6" }
        try {
            descendants(preview).filterIsInstance<javax.swing.JButton>().single { it.toolTipText == "输入 offset 数值" }.doClick()
        } finally { com.intellij.openapi.ui.TestDialogManager.setTestInputDialog(previousInput) }
        assertEquals("数值输入直接修改 offset，画布显示计算后的最终 ROI", Rectangle(0,0,105,114), preview.region)
        assertEquals(0, chooser.selectedIndex)
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

    private fun assertButtonsAtTitle(buttons: List<javax.swing.JButton>, number: Int) {
        val content = buttons.first().parent
        val title = content.components.filterIsInstance<javax.swing.JComponent>()
            .single { it.getClientProperty("pipeline.annotation.number") == number }
        buttons.forEach { button ->
            assertSame("按钮必须随对应说明滚动", content, button.parent)
            assertEquals("操作行应对齐自己的编号标题", title.y, button.y)
            assertTrue("按钮应位于标题右侧", button.x >= title.x + title.width)
        }
    }

    private fun node(file: JsonFile, name: String = "A"): PipelineNodeLocation {
        val property = (file.topLevelValue as JsonObject).findProperty(name)!!
        return PipelineNodeLocation(name, property.nameElement.textRange, property.textRange, sourceFile=file.virtualFile, rawJson=property.value!!.text)
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        (component as? java.awt.Container)?.components.orEmpty().flatMap(::descendants)
}
