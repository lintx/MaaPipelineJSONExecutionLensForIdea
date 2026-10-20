package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.intellij.images.editor.impl.ImageEditorManagerImpl
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.border.AbstractBorder
import kotlin.math.abs
import kotlin.math.roundToInt

internal class PipelineImagePreview(
    private val project: Project,
    private val original: BufferedImage,
    operations: List<PipelineOperation>,
    private val resolve: (String) -> PipelineNodeLocation? = { null },
    private val templates: Map<Int, List<PipelineVisualAssets.TemplatePreview>> = emptyMap(),
    private val roiSession: PipelineRoiEditSession? = null,
    private val onApplied: () -> Unit = {},
) : JPanel(BorderLayout()), Disposable {
    val viewer = ImageEditorManagerImpl.createImageEditorUI(original)
    private val picture = ImageEditorManagerImpl.getImageComponent(viewer)
    private var displayOperations = operations
    private var annotations = PipelinePreviewModel.annotations(displayOperations, resolve)
    private val editReasons = annotations.map { roiSession?.unavailable(it) }
    private val chooser = JComboBox((listOf("全部条件与动作") + annotations.map { it.title }).toTypedArray())
    private val apply = iconButton(AllIcons.Actions.Checked, "应用 ROI") { applyRegion() }
    private val cancel = iconButton(AllIcons.Actions.Cancel, "取消调整") { stopEditing() }
    private val numeric = iconButton(AllIcons.Actions.Edit, "输入 ROI 坐标") { editCoordinates() }
    private val editButtons = annotations.map { annotation ->
        iconButton(AllIcons.Actions.Edit, "调整 ${annotation.title}") {
            startEditing(annotation.number)
        }
    }
    private val status = JBLabel()
    // 显示筛选与编辑目标独立；切换可见区域时保留尚未应用的编辑框。
    private var editingNumber: Int? = null
    private val editing: Boolean get() = editingNumber != null
    private var changed = false
    internal var region: Rectangle? = null
        private set
    private var gesture: PipelineRoiGesture? = null
    private var panStart: Point? = null
    private var panPosition: Point? = null
    private val scroll: JScrollPane get() = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, picture) as JScrollPane

    init {
        name = "pipeline-image-preview"
        preferredSize = JBUI.size(1000, 760)
        Disposer.register(this, viewer)
        val top = JPanel(BorderLayout()).apply {
            if (annotations.isNotEmpty()) add(chooser, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)
        add(viewer, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        chooser.name = "pipeline-preview-annotations"
        chooser.addActionListener { updateImage(); updateControls() }
        picture.layout = null
        (editButtons + listOf(apply, cancel, numeric)).forEach(picture::add)
        picture.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) { positionButtons() }
        })
        scroll.viewport.addChangeListener { positionButtons() }
        picture.border = object : AbstractBorder() {
            override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
                if (!editingVisible()) return
                val rectangle = region ?: return
                val bounds = screenRectangle(rectangle)
                graphics.color = JBColor.BLUE
                graphics.drawRect(bounds.x, bounds.y, bounds.width, bounds.height)
                PipelineRoiGesture.handles(bounds).forEach { point -> graphics.fillRect(point.x - 3, point.y - 3, 7, 7) }
            }
        }
        val listener = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.button !in setOf(MouseEvent.BUTTON1, MouseEvent.BUTTON2)) return
                if (editingVisible() && event.button == MouseEvent.BUTTON1) {
                    val point = imagePoint(event.point)
                    gesture = PipelineRoiGesture(point, region, original.width, original.height,
                        (JBUI.scale(7) / picture.zoomFactor).roundToInt().coerceAtLeast(1))
                } else {
                    panStart = SwingUtilities.convertPoint(picture, event.point, scroll.viewport)
                    panPosition = scroll.viewport.viewPosition
                    picture.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }

            override fun mouseDragged(event: MouseEvent) {
                gesture?.let {
                    region = it.move(imagePoint(event.point)); changed = true
                    status.text = "${editingAnnotation()?.key} = ${region!!.let { box -> listOf(box.x, box.y, box.width, box.height) }}"
                    apply.isEnabled = true; positionButtons(); picture.repaint(); return
                }
                val start = panStart ?: return
                val position = panPosition ?: return
                val current = SwingUtilities.convertPoint(picture, event.point, scroll.viewport)
                val viewport = scroll.viewport
                viewport.viewPosition = Point((position.x + start.x - current.x).coerceIn(0, (viewport.viewSize.width - viewport.width).coerceAtLeast(0)),
                    (position.y + start.y - current.y).coerceIn(0, (viewport.viewSize.height - viewport.height).coerceAtLeast(0)))
            }

            override fun mouseReleased(event: MouseEvent) {
                gesture = null; panStart = null; panPosition = null
                picture.cursor = Cursor.getPredefinedCursor(if (editingVisible()) Cursor.CROSSHAIR_CURSOR else Cursor.HAND_CURSOR)
            }
        }
        picture.addMouseListener(listener)
        picture.addMouseMotionListener(listener)
        // 原生查看器的滚轮缩放受全局设置控制，此处始终提供滚轮缩放。
        scroll.mouseWheelListeners.forEach(scroll::removeMouseWheelListener)
        picture.mouseWheelListeners.forEach(picture::removeMouseWheelListener)
        val wheel = java.awt.event.MouseWheelListener { event ->
            val factor = viewer.zoomModel.zoomFactor * Math.pow(1.15, -event.preciseWheelRotation)
            viewer.zoomModel.setZoomLevelChanged(true)
            viewer.zoomModel.zoomFactor = factor.coerceIn(0.01, 64.0)
            event.consume()
        }
        scroll.addMouseWheelListener(wheel)
        picture.addMouseWheelListener(wheel)
        updateImage()
        updateControls()
    }

    private fun selected(): PipelinePreviewAnnotation? = annotations.getOrNull(chooser.selectedIndex - 1)

    private fun editingAnnotation(): PipelinePreviewAnnotation? = annotations.firstOrNull { it.number == editingNumber }

    private fun editingVisible(): Boolean = editing && (selected() == null || selected()?.number == editingNumber)

    private fun updateImage() {
        val zoom = viewer.zoomModel.zoomFactor
        picture.document.setValue(if (displayOperations.isEmpty()) original else
            PipelineVisualAssets.overlay(original, displayOperations, resolve, selected()?.number, templates,
                excludedAnnotation = editingNumber))
        viewer.zoomModel.zoomFactor = zoom
        positionButtons()
        picture.repaint()
    }

    internal fun startEditing(number: Int) {
        if (editing) return
        val annotation = annotations.firstOrNull { it.number == number } ?: return
        if (roiSession?.unavailable(annotation) != null || roiSession == null) return
        region = annotation.region.rectangle(original.width, original.height) ?: return
        editingNumber = annotation.number; changed = false
        updateImage(); updateControls()
    }

    internal fun stopEditing() {
        editingNumber = null; changed = false; region = null; gesture = null
        updateImage(); updateControls()
    }

    private fun updateControls() {
        val annotation = selected()
        val reason = annotation?.let { roiSession?.unavailable(it) }
        apply.isVisible = editingVisible(); apply.isEnabled = changed
        cancel.isVisible = editingVisible()
        numeric.isVisible = editingVisible()
        status.text = if (editing) "拖动框或控制点；框外拖动重画" else reason.orEmpty()
        picture.cursor = Cursor.getPredefinedCursor(if (editingVisible()) Cursor.CROSSHAIR_CURSOR else Cursor.HAND_CURSOR)
        positionButtons()
    }

    private fun iconButton(icon: javax.swing.Icon, tooltip: String, action: () -> Unit): JButton = JButton(icon).apply {
        toolTipText = tooltip
        isFocusable = true
        margin = JBUI.insets(2)
        addActionListener { action() }
    }

    private fun positionButtons() {
        val visible = picture.visibleRect
        val occupied = mutableListOf<Rectangle>()
        val size = JBUI.scale(24)
        fun place(button: JButton, rectangle: Rectangle, column: Int = 0) {
            val frame = screenRectangle(rectangle)
            button.isVisible = frame.intersects(visible)
            if (!button.isVisible) return
            val left = (frame.x + frame.width - (column + 1) * size).coerceIn(visible.x, maxOf(visible.x, visible.x + visible.width - size))
            var top = (frame.y - size).coerceIn(visible.y, maxOf(visible.y, visible.y + visible.height - size))
            var bounds = Rectangle(left, top, size, size)
            while (occupied.any { it.intersects(bounds) } && top + 2 * size <= visible.y + visible.height) {
                top += size; bounds = Rectangle(left, top, size, size)
            }
            button.bounds = bounds
            occupied += bounds
        }
        editButtons.forEachIndexed { index, button ->
            val annotation = annotations[index]
            val rectangle = annotation.region.rectangle(original.width, original.height)
            button.isVisible = !editing && roiSession != null && rectangle != null && (selected() == null || selected()?.number == annotation.number)
            if (button.isVisible) {
                val reason = editReasons[index]
                button.isEnabled = reason == null
                button.toolTipText = reason ?: "调整 ${annotation.title}"
                place(button, rectangle!!)
            }
        }
        apply.isVisible = editingVisible(); cancel.isVisible = editingVisible(); numeric.isVisible = editingVisible()
        if (editingVisible()) region?.let {
            place(apply, it, 2); place(cancel, it, 1); place(numeric, it)
        }
    }

    private fun editCoordinates() {
        val current = region ?: return
        val input = com.intellij.openapi.ui.Messages.showInputDialog(project,
            "x, y, width, height", "ROI 坐标", null,
            listOf(current.x, current.y, current.width, current.height).joinToString(", "),
            object : com.intellij.openapi.ui.InputValidator {
                override fun checkInput(inputString: String): Boolean {
                    val values = inputString.trim().removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toIntOrNull() }
                    return values.size == 4 && values[0] >= 0 && values[1] >= 0 && values[2] > 0 && values[3] > 0 &&
                        values[0].toLong() + values[2] <= original.width && values[1].toLong() + values[3] <= original.height
                }
                override fun canClose(inputString: String) = checkInput(inputString)
            }) ?: return
        val values = input.trim().removeSurrounding("[", "]").split(',').map { it.trim().toInt() }
        region = Rectangle(values[0], values[1], values[2], values[3]); changed = true
        apply.isEnabled = true; positionButtons(); picture.repaint()
    }

    private fun applyRegion() {
        val annotation = editingAnnotation() ?: return
        val rectangle = region ?: return
        if (!changed || roiSession?.apply(annotation, rectangle) != true) return
        displayOperations = displayOperations.map { roiSession.currentOperation(it) }
        annotations = PipelinePreviewModel.annotations(displayOperations, resolve)
        stopEditing(); onApplied()
    }

    internal fun imagePoint(point: Point): Point {
        val canvas = picture.canvasSize
        return Point(((point.x - 2).toDouble() * original.width / canvas.width.coerceAtLeast(1)).roundToInt().coerceIn(0, original.width),
            ((point.y - 2).toDouble() * original.height / canvas.height.coerceAtLeast(1)).roundToInt().coerceIn(0, original.height))
    }

    private fun screenRectangle(rectangle: Rectangle): Rectangle {
        val canvas = picture.canvasSize
        fun horizontal(value: Int) = (value.toDouble() * canvas.width / original.width).roundToInt()
        fun vertical(value: Int) = (value.toDouble() * canvas.height / original.height).roundToInt()
        return Rectangle(horizontal(rectangle.x) + 2, vertical(rectangle.y) + 2, horizontal(rectangle.width), vertical(rectangle.height))
    }

    fun fitToWindow() { viewer.zoomModel.fitZoomToWindow() }
    fun hasChanges(): Boolean = changed
    override fun dispose() {}

    companion object {
        fun show(project: Project, file: VirtualFile, original: BufferedImage, operations: List<PipelineOperation> = emptyList(),
            resolve: (String) -> PipelineNodeLocation? = { null },
            templates: Map<Int, List<PipelineVisualAssets.TemplatePreview>> = emptyMap(),
            blocked: (VirtualFile) -> Boolean = { false }) {
            object : DialogWrapper(project, false) {
                private val preview = PipelineImagePreview(project, original, operations, resolve, templates,
                    if (operations.isEmpty()) null else PipelineRoiEditSession(project, operations, blocked))
                init { title = "图片预览 · ${file.name}"; setOKButtonText("关闭"); init(); Disposer.register(disposable, preview) }
                override fun createActions() = arrayOf(okAction)
                override fun createCenterPanel(): JComponent = preview
                override fun doOKAction() { if (confirmDiscard()) super.doOKAction() }
                override fun doCancelAction() { if (confirmDiscard()) super.doCancelAction() }
                private fun confirmDiscard(): Boolean = !preview.hasChanges() ||
                    com.intellij.openapi.ui.Messages.showYesNoDialog(project, "放弃尚未应用的 ROI 调整？", "关闭图片", null) == com.intellij.openapi.ui.Messages.YES
                override fun show() {
                    SwingUtilities.invokeLater { if (window?.isShowing == true) preview.fitToWindow() }
                    super.show()
                }
            }.show()
        }
    }
}

internal class PipelineRoiGesture(private val start: Point, initial: Rectangle?, private val width: Int, private val height: Int,
    tolerance: Int) {
    private val original = initial?.let(::Rectangle)
    private val handle = initial?.let { handles(it).indexOfFirst { point -> abs(point.x - start.x) <= tolerance && abs(point.y - start.y) <= tolerance } } ?: -1
    private val moving = handle < 0 && initial?.contains(start) == true

    fun move(point: Point): Rectangle {
        val previous = original
        if (moving && previous != null) return Rectangle((previous.x + point.x - start.x).coerceIn(0, (width - previous.width).coerceAtLeast(0)),
            (previous.y + point.y - start.y).coerceIn(0, (height - previous.height).coerceAtLeast(0)), previous.width, previous.height)
        var left = previous?.x ?: start.x
        var top = previous?.y ?: start.y
        var right = previous?.let { it.x + it.width } ?: start.x
        var bottom = previous?.let { it.y + it.height } ?: start.y
        if (handle < 0) { left = start.x; top = start.y; right = point.x; bottom = point.y }
        else {
            if (handle in setOf(0, 6, 7)) left = point.x
            if (handle in setOf(0, 1, 2)) top = point.y
            if (handle in setOf(2, 3, 4)) right = point.x
            if (handle in setOf(4, 5, 6)) bottom = point.y
        }
        val x = minOf(left, right).coerceIn(0, width - 1)
        val y = minOf(top, bottom).coerceIn(0, height - 1)
        return Rectangle(x, y, (maxOf(left, right).coerceAtMost(width) - x).coerceAtLeast(1),
            (maxOf(top, bottom).coerceAtMost(height) - y).coerceAtLeast(1))
    }

    companion object {
        fun handles(rectangle: Rectangle): List<Point> = rectangle.let {
            val right = it.x + it.width; val bottom = it.y + it.height
            listOf(Point(it.x, it.y), Point(it.x + it.width / 2, it.y), Point(right, it.y), Point(right, it.y + it.height / 2),
                Point(right, bottom), Point(it.x + it.width / 2, bottom), Point(it.x, bottom), Point(it.x, it.y + it.height / 2))
        }
    }
}
