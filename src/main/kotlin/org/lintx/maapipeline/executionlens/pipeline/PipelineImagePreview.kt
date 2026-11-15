package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
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
    private val node: PipelineNodeLocation? = null,
    retainAction: AnAction? = null,
) : JPanel(BorderLayout()), Disposable {
    val viewer = PipelineImageViewer(original, retainAction)
    private val picture = viewer.picture
    private val overlay = PipelinePreviewOverlay(picture, templates, ::screenRectangle)
    private var displayOperations = operations
    private var annotations = PipelinePreviewModel.annotations(displayOperations, resolve)
    private val editReasons = annotations.map { roiSession?.unavailable(it) }
    private val chooser = JComboBox((listOf("全部条件与动作") + annotations.map { it.title }).toTypedArray())
    private val apply = iconButton(AllIcons.Actions.Checked, "应用原始 ROI（保留 offset）") { applyRegion() }
    private val cancel = iconButton(AllIcons.Actions.Cancel, "取消调整") { stopEditing() }
    private val numeric = iconButton(AllIcons.Actions.Edit, "输入原始 ROI 坐标") { editCoordinates() }
    private val editButtons = annotations.map { annotation ->
        iconButton(AllIcons.Actions.Edit, "调整 ${annotation.title} 的原始 ROI") {
            startEditing(annotation.number)
        }
    }
    private val offsetButtons = annotations.map { annotation ->
        iconButton(AllIcons.Actions.Edit, "修改 ${annotation.key}_offset") { editOffset(annotation.number) }
    }
    private val status = JBLabel()
    // 显示筛选与编辑目标独立；切换可见区域时保留尚未应用的编辑框。
    private var editingNumber: Int? = null
    private var editingOffset = false
    private var offsetBase: List<Int>? = null
    private val editing: Boolean get() = editingNumber != null
    private var changed = false
    internal var region: Rectangle? = null
        private set
    private var regionValues: List<Int>? = null
    private var editBasis: List<Int>? = null
    private var gesture: PipelineRoiGesture? = null
    private var panStart: Point? = null
    private var panPosition: Point? = null
    private val scroll: JScrollPane get() = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, picture) as JScrollPane

    init {
        name = "pipeline-image-preview"
        preferredSize = JBUI.size(1000, 760)
        Disposer.register(this, viewer)
        val top = JPanel(BorderLayout()).apply {
            node?.description?.takeIf(String::isNotBlank)?.let { description ->
                val text = PipelineWrappingLabel(description).apply { name = "pipeline-preview-description" }
                add(object : com.intellij.ui.components.JBScrollPane(text) {
                    override fun getPreferredSize(): java.awt.Dimension {
                        val available = (parent?.width?.takeIf { it > 0 } ?: JBUI.scale(1000)) - insets.left - insets.right
                        val height = text.preferredSizeForWidth(available).height + insets.top + insets.bottom
                        return java.awt.Dimension(0, height.coerceAtMost(JBUI.scale(120)))
                    }
                }.apply {
                    border = JBUI.Borders.emptyBottom(8)
                    viewportBorder = null
                    isOpaque = false
                    viewport.isOpaque = false
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                }, BorderLayout.CENTER)
            }
        }
        add(top, BorderLayout.NORTH)
        add(viewer, BorderLayout.CENTER)
        add(overlay.overflow, BorderLayout.EAST)
        add(status, BorderLayout.SOUTH)
        chooser.name = "pipeline-preview-annotations"
        if (annotations.isNotEmpty()) viewer.toolbarPanel.add(chooser, BorderLayout.CENTER)
        com.intellij.ui.ComboboxSpeedSearch.installOn(chooser)
        chooser.addActionListener { updateImage(); updateControls() }
        picture.layout = null
        (editButtons + offsetButtons + listOf(apply, cancel, numeric)).forEach(picture::add)
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(PipelinePreviewSettings.CHANGED, Runnable { updateImage() })
        picture.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) { positionButtons() }
        })
        scroll.viewport.addChangeListener { positionButtons() }
        picture.border = object : AbstractBorder() {
            override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
                val pen = graphics.create() as Graphics2D
                try {
                    overlay.paint(pen)
                    if (!editingVisible()) return
                    val rectangle = region ?: return
                    val bounds = screenRectangle(rectangle)
                    PipelineVisualAssets.drawRoiFrame(pen, bounds, (editingNumber ?: 1) - 1, original = !editingOffset,
                        dashed = true, PipelinePreviewSettings.lineWidth())
                    pen.color = JBColor.BLUE
                    PipelineRoiGesture.handles(bounds).forEach { point -> pen.fillRect(point.x - 3, point.y - 3, 7, 7) }
                } finally { pen.dispose() }
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
                    val moved = it.move(imagePoint(event.point))
                    val basis = editBasis.orEmpty()
                    val reverseX = basis.getOrElse(2) { 1 } < 0
                    val reverseY = basis.getOrElse(3) { 1 } < 0
                    val left = moved.x + if (reverseX) moved.width else 0
                    val top = moved.y + if (reverseY) moved.height else 0
                    // 拖动保留原有的右/下边缘参照和负尺寸方向；数值输入则原样保存。
                    val edgeRelative = !editingOffset || editingAnnotation()?.operation?.isAction != true
                    regionValues = listOf(left - if (edgeRelative && basis.getOrElse(0) { 0 } < 0) original.width else 0,
                        top - if (edgeRelative && basis.getOrElse(1) { 0 } < 0) original.height else 0,
                        if (reverseX) -moved.width else if (basis.getOrElse(2) { 1 } == 0 && moved.x + moved.width == original.width) 0 else moved.width,
                        if (reverseY) -moved.height else if (basis.getOrElse(3) { 1 } == 0 && moved.y + moved.height == original.height) 0 else moved.height)
                    region = moved; changed = true
                    status.text = if (editingOffset) "${editingAnnotation()?.key}_offset = ${offsetValues()}" else "原始 ${editingAnnotation()?.key} = $regionValues"
                    apply.isEnabled = true; updateImage(); return
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
        val edited = if (editingVisible()) editingAnnotation()?.let { annotation -> region?.let { rectangle ->
            val information = annotation.region.copy(coordinates = regionValues,
                originalCoordinates = if (editingOffset) offsetBase else regionValues,
                coordinateOffset = if (editingOffset) offsetValues() else annotation.region.coordinateOffset)
            PipelineVisualAssets.OverlayRegion(Rectangle(rectangle),
                listOf(PipelineVisualAssets.OverlayMark(annotation.copy(region = information), original = !editingOffset)))
        } } else null
        overlay.update(PipelineVisualAssets.previewRegions(original.width, original.height, displayOperations, resolve, selected()?.number,
            excludedAnnotation = editingNumber, originalDraft = regionValues.takeUnless { editingOffset },
            finalDraft = regionValues.takeIf { editingOffset }), edited)
        positionButtons()
        picture.repaint()
    }

    internal fun startEditing(number: Int) {
        if (editing) return
        val annotation = annotations.firstOrNull { it.number == number } ?: return
        if (roiSession?.unavailable(annotation) != null || roiSession == null) return
        editingOffset = false
        regionValues = annotation.region.originalCoordinates ?: annotation.region.coordinates ?: return
        editBasis = regionValues
        region = originalRectangle(annotation)
        editingNumber = annotation.number; changed = false
        updateImage(); updateControls()
    }

    internal fun stopEditing() {
        editingNumber = null; editingOffset = false; offsetBase = null
        changed = false; region = null; regionValues = null; editBasis = null; gesture = null
        updateImage(); updateControls()
    }

    private fun updateControls() {
        val annotation = selected()
        val reason = annotation?.let { roiSession?.unavailable(it) }
        apply.isVisible = editingVisible(); apply.isEnabled = changed
        cancel.isVisible = editingVisible()
        numeric.isVisible = editingVisible()
        status.text = if (editingOffset) "拖动或拉框调整最终 ROI；仅写入 offset，原始 ROI 保持不变"
            else if (editing) "调整原始 ROI；offset 保持不变" else reason.orEmpty()
        apply.toolTipText = if (editingOffset) "应用 offset（保留原始 ROI）" else "应用原始 ROI（保留 offset）"
        numeric.toolTipText = if (editingOffset) "输入 offset 数值" else "输入原始 ROI 坐标"
        picture.cursor = Cursor.getPredefinedCursor(if (editingVisible()) Cursor.CROSSHAIR_CURSOR else Cursor.HAND_CURSOR)
        positionButtons()
    }

    private fun iconButton(icon: javax.swing.Icon, tooltip: String, action: () -> Unit): JButton = JButton(icon).apply {
        toolTipText = tooltip
        isFocusable = true
        margin = JBUI.insets(2)
        addActionListener { action() }
    }

    private fun originalRectangle(annotation: PipelinePreviewAnnotation): Rectangle? =
        (annotation.region.originalCoordinates ?: annotation.region.coordinates)?.let {
            PipelineRegionInfo.rectangle(it, original.width, original.height)
        }

    private fun positionButtons() {
        editButtons.forEachIndexed { index, button ->
            val annotation = annotations[index]
            val rectangle = originalRectangle(annotation) ?: annotation.region.rectangle(original.width, original.height)
            button.isVisible = !editing && roiSession != null && rectangle != null && (selected() == null || selected()?.number == annotation.number)
            if (button.isVisible) {
                val reason = editReasons[index]
                button.isEnabled = reason == null
                button.toolTipText = reason ?: "调整 ${annotation.title} 的原始 ROI（保留 offset）"
            }
        }
        offsetButtons.forEachIndexed { index, button ->
            val annotation = annotations[index]
            val rectangle = annotation.region.rectangle(original.width, original.height) ?: originalRectangle(annotation)
            button.isVisible = !editing && roiSession != null && rectangle != null && (selected() == null || selected()?.number == annotation.number)
            if (button.isVisible) {
                val reason = roiSession?.unavailable(annotation, offset = true)
                button.isEnabled = reason == null
                button.toolTipText = reason ?: "修改 ${annotation.key}_offset（保留原始 ROI）"
            }
        }
        apply.isVisible = editingVisible(); cancel.isVisible = editingVisible(); numeric.isVisible = editingVisible()
        overlay.position(annotations.mapIndexed { index, annotation ->
            annotation.number to if (editingVisible() && annotation.number == editingNumber) listOf(apply, cancel, numeric)
                else listOf(editButtons[index], offsetButtons[index]).filter { it.isVisible }
        }.toMap())
    }

    private fun editCoordinates() {
        val current = (if (editingOffset) offsetValues() else regionValues) ?: return
        val input = com.intellij.openapi.ui.Messages.showInputDialog(project,
            if (editingOffset) "dx, dy, dwidth, dheight（直接修改偏移量，支持负数）"
            else "x, y, width, height（支持负数；宽高为 0 表示延伸至边缘）", if (editingOffset) "offset 数值" else "原始 ROI 坐标", null,
            current.joinToString(", "),
            object : com.intellij.openapi.ui.InputValidator {
                override fun checkInput(inputString: String): Boolean {
                    val parts = inputString.trim().removeSurrounding("[", "]").split(',')
                    return parts.size in (if (editingOffset) setOf(4) else setOf(2, 4)) && parts.all { it.trim().toIntOrNull() != null }
                }
                override fun canClose(inputString: String) = checkInput(inputString)
            }) ?: return
        val values = input.trim().removeSurrounding("[", "]").split(',').map { it.trim().toInt() }
        regionValues = if (editingOffset) offsetBase?.zip(values) { origin, offset -> origin + offset } ?: return else values
        editBasis = regionValues
        region = if (editingOffset && editingAnnotation()?.operation?.isAction == true) {
            editingAnnotation()?.region?.copy(originalCoordinates = offsetBase, coordinateOffset = offsetValues())?.rectangle(original.width, original.height)
        } else PipelineRegionInfo.rectangle(regionValues!!, original.width, original.height)
        changed = true
        status.text = (if (editingOffset) "${editingAnnotation()?.key}_offset = ${offsetValues()}" else "原始 ${editingAnnotation()?.key} = $values") +
            if (region == null) "（当前画布内无可见区域）" else ""
        apply.isEnabled = true; updateImage()
    }

    internal fun editOffset(number: Int) {
        val annotation = annotations.firstOrNull { it.number == number } ?: return
        if (editing || roiSession == null || roiSession.unavailable(annotation, offset = true) != null) return
        val values = annotation.region.originalCoordinates ?: annotation.region.coordinates ?: return
        val base = if (values.size == 2) values + listOf(1, 1) else values
        // 动作先解析原始目标框再应用偏移；识别 ROI 则先逐分量相加再解析负坐标。
        offsetBase = if (annotation.operation.isAction) PipelineRegionInfo.normalized(base, original.width, original.height) else base
        val origin = offsetBase ?: return
        val delta = PipelineVisualModel.integers(annotation.operation.parameters["${annotation.key}_offset"]) ?: listOf(0, 0, 0, 0)
        regionValues = origin.zip(delta) { value, shift -> value + shift }
        editBasis = regionValues
        editingOffset = true
        editingNumber = number
        changed = false
        region = annotation.region.rectangle(original.width, original.height)
        updateImage(); updateControls()
    }

    private fun offsetValues(): List<Int>? {
        val base = offsetBase ?: return null
        return regionValues?.zip(base) { value, origin -> value - origin }
    }

    private fun refreshOperations() {
        displayOperations = displayOperations.map { roiSession!!.currentOperation(it) }
        annotations = PipelinePreviewModel.annotations(displayOperations, resolve)
        stopEditing(); onApplied()
    }

    private fun applyRegion() {
        val annotation = editingAnnotation() ?: return
        val values = regionValues ?: return
        if (!changed) return
        val applied = if (editingOffset) offsetValues()?.let { roiSession?.applyOffset(annotation, it) } else roiSession?.apply(annotation, values)
        if (applied != true) return
        refreshOperations()
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
        fun show(project: Project, node: PipelineNodeLocation, original: BufferedImage, operations: List<PipelineOperation> = emptyList(),
            resolve: (String) -> PipelineNodeLocation? = { null },
            templates: Map<Int, List<PipelineVisualAssets.TemplatePreview>> = emptyMap(),
            blocked: (VirtualFile) -> Boolean = { false }) {
            PipelinePreviewTabs.getInstance(project).open(PipelinePreviewRequest(node, original, operations, resolve, templates, blocked))
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
