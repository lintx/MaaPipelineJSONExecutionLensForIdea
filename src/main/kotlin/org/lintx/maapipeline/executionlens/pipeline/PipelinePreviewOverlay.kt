package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Graphics2D
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane

internal class PipelinePreviewOverlay(
    private val picture: JComponent,
    private val templates: Map<Int, List<PipelineVisualAssets.TemplatePreview>>,
    private val screenRectangle: (Rectangle) -> Rectangle,
) {
    private data class Note(val region: PipelineVisualAssets.OverlayRegion, val content: JPanel, val scroll: JBScrollPane, val frame: JPanel)
    private var regions = emptyList<PipelineVisualAssets.OverlayRegion>()
    private var editedRegion: PipelineVisualAssets.OverlayRegion? = null
    private var notes = emptyList<Note>()
    private var connections = emptyList<Pair<Note, List<Point>>>()
    private val overflowContent = JPanel(null)
    val overflow = JBScrollPane(overflowContent).apply {
        name = "pipeline-preview-annotation-overflow"
        preferredSize = JBUI.size(360, 0)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        isVisible = false
    }
    private var positioning = false
    private var lastActions: Map<Int, List<JButton>> = emptyMap()

    init {
        overflow.viewport.addChangeListener { position(lastActions) }
    }

    fun update(value: List<PipelineVisualAssets.OverlayRegion>, edited: PipelineVisualAssets.OverlayRegion? = null) {
        if (value == regions && edited == editedRegion) return
        regions = value
        editedRegion = edited
        notes.forEach { it.frame.parent?.remove(it.frame) }
        notes = (value + listOfNotNull(edited)).map { region ->
            val content = JPanel(null).apply { background = UIUtil.getPanelBackground() }
            PipelineVisualAssets.previewContent(region, templates).forEach { item ->
                when {
                    item.text != null -> content.add(PipelineWrappingLabel(item.text).apply {
                        putClientProperty("pipeline.annotation.number", item.annotationNumber)
                    })
                    item.image != null -> content.add(JBLabel(ImageIcon(item.image)))
                    item.spacing > 0 -> content.add(JPanel().apply { preferredSize = JBUI.size(1, item.spacing); isOpaque = false })
                }
            }
            val scroll = JBScrollPane(content).apply {
                name = "pipeline-preview-annotation"
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = JBUI.Borders.empty()
            }
            val frame = JPanel(null).apply {
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.customLine(PipelineVisualAssets.overlayColor(region.annotations.first().number - 1, region.originalOnly))
                add(scroll)
            }
            if (overflow.isVisible) overflowContent.add(frame)
            else {
                picture.add(frame)
                picture.setComponentZOrder(frame, picture.componentCount - 1)
            }
            Note(region, content, scroll, frame)
        }
        if (notes.isEmpty() && overflow.isVisible) {
            overflow.isVisible = false
            overflow.parent?.revalidate()
        }
    }

    fun position(actions: Map<Int, List<JButton>> = emptyMap()) {
        lastActions = actions
        if (positioning) return
        positioning = true
        try { positionNotes(actions) } finally { positioning = false }
    }

    private fun positionNotes(actions: Map<Int, List<JButton>>) {
        connections = emptyList()
        val visible = picture.visibleRect
        val docked = overflow.isVisible
        if (!docked && (visible.width <= 8 || visible.height <= 8)) return
        val placed = mutableListOf<Rectangle>()
        val frames = notes.map { screenRectangle(it.region.rectangle) }
        var dockTop = JBUI.scale(4)
        val dockWidth = ((overflow.viewport.width.takeIf { it > 0 } ?: overflow.preferredSize.width) - JBUI.scale(8)).coerceAtLeast(1)
        val actionNotes = actions.keys.associateWith { number ->
            notes.firstOrNull { it.region === editedRegion && it.region.annotations.any { annotation -> annotation.number == number } }
                ?: notes.firstOrNull { !it.region.originalOnly && it.region.annotations.any { annotation -> annotation.number == number } }
                ?: notes.firstOrNull { it.region.annotations.any { annotation -> annotation.number == number } }
        }
        notes.forEach { note ->
            val frame = screenRectangle(note.region.rectangle)
            note.frame.isVisible = docked || frame.intersects(visible)
            val buttons = actions.filter { (number, controls) -> actionNotes[number] === note && controls.isNotEmpty() }
            val buttonSize = JBUI.scale(24)
            val actionWidth = (buttons.values.maxOfOrNull { it.size } ?: 0) * buttonSize
            // 按钮与说明在同一滚动内容中，标题换行、模板图变高或滚动后仍保持编号对应。
            buttons.values.forEach { row -> row.forEach { if (it.parent !== note.content) note.content.add(it) } }
            if (!note.frame.isVisible) return@forEach
            val items = note.content.components.filterIsInstance<JComponent>().filterNot { it is JButton }
            val naturalWidth = items.maxOfOrNull { child ->
                if (child is PipelineWrappingLabel) child.text.lineSequence().maxOfOrNull { child.getFontMetrics(child.font).stringWidth(it) } ?: 0
                else child.preferredSize.width
            } ?: 0
            val width = if (docked) dockWidth else
                minOf(JBUI.scale(360) + actionWidth, naturalWidth + JBUI.scale(36) + actionWidth, visible.width - 8).coerceAtLeast(1)
            val contentWidth = (width - JBUI.scale(20)).coerceAtLeast(1)
            val textWidth = (contentWidth - actionWidth).coerceAtLeast(1)
            var top = JBUI.scale(4)
            items.forEach { child ->
                val number = child.getClientProperty("pipeline.annotation.number") as? Int
                val controls = buttons[number].orEmpty()
                val preferredHeight = if (child is PipelineWrappingLabel) child.preferredSizeForWidth((textWidth - JBUI.scale(8)).coerceAtLeast(1)).height
                    else child.preferredSize.height
                val height = maxOf(preferredHeight, if (controls.isEmpty()) 0 else buttonSize)
                child.setBounds(JBUI.scale(4), top, (textWidth - JBUI.scale(8)).coerceAtLeast(1), height)
                controls.forEachIndexed { column, button ->
                    button.setBounds(textWidth + column * buttonSize, top, buttonSize, buttonSize)
                }
                top += height
            }
            note.content.preferredSize = Dimension(contentWidth, top + JBUI.scale(4))
            val height = minOf(top + JBUI.scale(10),
                if (docked) Int.MAX_VALUE else visible.height - 8, JBUI.scale(380)).coerceAtLeast(1)
            val gap = JBUI.scale(4)
            val bounds = if (docked) Rectangle(gap, dockTop, width, height) else
                PipelineAnnotationLayout.place(visible, frame, Dimension(width, height), placed, frames,
                    gap + kotlin.math.ceil(PipelinePreviewSettings.lineWidth()).toInt())
            if (bounds == null) {
                // 画面放不下时统一移出图片；本预览保持侧栏，避免缩放/拖动引起反复重排。
                notes.forEach { overflowContent.add(it.frame) }
                overflow.isVisible = true
                overflow.parent?.revalidate()
                picture.repaint()
                positionNotes(actions)
                return
            }
            note.frame.bounds = bounds
            note.scroll.setBounds(1, 1, (width - 2).coerceAtLeast(1), (height - 2).coerceAtLeast(1))
            placed += bounds
            dockTop += height + gap
        }
        if (docked) {
            overflowContent.preferredSize = Dimension(dockWidth + JBUI.scale(8), dockTop)
            overflowContent.revalidate()
            overflowContent.repaint()
        } else {
            val shown = notes.filter { it.frame.isVisible }
            val paths = PipelineAnnotationConnections.routes(shown.map { screenRectangle(it.region.rectangle) to it.frame.bounds },
                frames, shown.map { it.frame.bounds }, visible,
                JBUI.scale(4) + kotlin.math.ceil(PipelinePreviewSettings.lineWidth()).toInt())
            connections = shown.zip(paths).mapNotNull { (note, path) ->
                path?.let { note to it }
            }
        }
        picture.repaint()
    }

    fun paint(graphics: Graphics2D) {
        val visible = regions.map { region ->
            IndexedValue(region.annotations.first().number - 1, region.copy(rectangle = screenRectangle(region.rectangle)))
        }
        val lineWidth = PipelinePreviewSettings.lineWidth()
        connections.forEach { (note, points) ->
            graphics.color = PipelineVisualAssets.overlayColor(note.region.annotations.first().number - 1, note.region.originalOnly)
            graphics.stroke = java.awt.BasicStroke(1f)
            points.zipWithNext().forEach { (from, to) -> graphics.drawLine(from.x, from.y, to.x, to.y) }
        }
        // 框线最后绘制，连接点也不能改变 ROI 的颜色和原始/最终线型。
        visible.forEach { (index, region) ->
            PipelineVisualAssets.drawRoiFrame(graphics, region.rectangle, index, region.originalOnly, region.dashed, lineWidth)
        }
        PipelineVisualAssets.drawSharedEdges(graphics, visible, lineWidth)
    }
}

internal object PipelineAnnotationConnections {
    fun routes(anchors: List<Pair<Rectangle, Rectangle>>, rois: List<Rectangle>, cards: List<Rectangle>,
        view: Rectangle, gap: Int): List<List<Point>?> {
        val placed = mutableListOf<List<Point>>()
        return anchors.map { (roi, card) -> route(roi, card, rois, cards, view, gap, placed)?.also(placed::add) }
    }

    fun route(roi: Rectangle, card: Rectangle, rois: List<Rectangle>, cards: List<Rectangle>, view: Rectangle, gap: Int,
        placed: List<List<Point>> = emptyList()): List<Point>? {
        fun expanded(rectangle: Rectangle) = Rectangle(rectangle).apply { grow(gap, gap) }
        val obstacles = (rois + cards).map(::expanded)
        val segments = placed.flatMap { it.zipWithNext() }.map { (a, b) -> java.awt.geom.Line2D.Double(a, b) }
        // 已有线段也保留间距，包括斜线和端点；不能只避开 ROI/卡片或仅检查平行重叠。
        fun avoidsConnections(from: Point, to: Point): Boolean {
            val line = java.awt.geom.Line2D.Double(from, to)
            return segments.none { other -> line.intersectsLine(other) ||
                minOf(line.ptSegDist(other.p1), line.ptSegDist(other.p2), other.ptSegDist(from), other.ptSegDist(to)) <= gap + 1 }
        }
        fun ports(rectangle: Rectangle, other: Rectangle): List<Pair<Point, Point>> {
            val middleX = rectangle.x + rectangle.width / 2
            val middleY = rectangle.y + rectangle.height / 2
            val xs = listOf(middleX, (other.x + other.width / 2).coerceIn(rectangle.x, rectangle.x + rectangle.width - 1)).distinct()
            val ys = listOf(middleY, (other.y + other.height / 2).coerceIn(rectangle.y, rectangle.y + rectangle.height - 1)).distinct()
            return xs.flatMap { x -> listOf(
                Point(x, rectangle.y) to Point(x, rectangle.y - gap - 1),
                Point(x, rectangle.y + rectangle.height - 1) to Point(x, rectangle.y + rectangle.height + gap + 1)) } +
                ys.flatMap { y -> listOf(
                    Point(rectangle.x, y) to Point(rectangle.x - gap - 1, y),
                    Point(rectangle.x + rectangle.width - 1, y) to Point(rectangle.x + rectangle.width + gap + 1, y)) }
        }
        fun clear(from: Point, to: Point, barriers: List<java.awt.geom.Rectangle2D>) = avoidsConnections(from, to) && barriers.none {
            it.intersectsLine(from.x.toDouble(), from.y.toDouble(), to.x.toDouble(), to.y.toDouble())
        }
        fun usablePorts(owner: Rectangle, other: Rectangle) = ports(owner, other).filter { (edge, outer) ->
            view.contains(outer) && clear(edge, outer, (rois + cards).filter { it != owner }.map(::expanded))
        }
        val corridors = obstacles + segments.map { Rectangle(it.bounds).apply { grow(gap + 1, gap + 1) } }
        val xs = (listOf(view.x, view.x + view.width - 1) + corridors.flatMap { listOf(it.x - 1, it.x + it.width + 1) }).distinct()
        val ys = (listOf(view.y, view.y + view.height - 1) + corridors.flatMap { listOf(it.y - 1, it.y + it.height + 1) }).distinct()
        var best: List<Point>? = null
        var bestCost = Double.POSITIVE_INFINITY
        // 紧邻的 ROI 与卡片允许直接跨越两者间隙，不必为了出入端口绕到另一条线的通道。
        val directBarriers = (rois + cards).filter { it != roi && it != card }.map(::expanded) +
            // 绘制边界在 x + width - 1；用半像素内缩排除边框，四侧端点才能一视同仁。
            listOf(roi, card).map { java.awt.geom.Rectangle2D.Double(it.x + 0.5, it.y + 0.5,
                (it.width - 2).coerceAtLeast(0).toDouble(), (it.height - 2).coerceAtLeast(0).toDouble()) }.filter { !it.isEmpty }
        for ((start, _) in ports(roi, card)) for ((end, _) in ports(card, roi)) {
            if (view.contains(start) && view.contains(end) && clear(start, end, directBarriers)) {
                val cost = start.distance(end) + 2 * gap
                if (cost < bestCost) { best = listOf(start, end); bestCost = cost }
            }
        }
        for ((startEdge, start) in usablePorts(roi, card)) for ((endEdge, end) in usablePorts(card, roi)) {
            val paths = sequence {
                yield(listOf(start, end))
                yield(listOf(start, Point(start.x, end.y), end))
                yield(listOf(start, Point(end.x, start.y), end))
                xs.forEach { x -> yield(listOf(start, Point(x, start.y), Point(x, end.y), end)) }
                ys.forEach { y -> yield(listOf(start, Point(start.x, y), Point(end.x, y), end)) }
            }
            for (core in paths) {
                if (core.any { !view.contains(it) } || core.zipWithNext().any { (a, b) -> !clear(a, b, obstacles) }) continue
                val points = (listOf(startEdge) + core + endEdge).fold(mutableListOf<Point>()) { result, point ->
                    result.apply { if (lastOrNull() != point) add(point) }
                }
                val cost = points.zipWithNext().sumOf { (a, b) -> a.distance(b) } + points.size * gap
                if (cost < bestCost) { best = points; bestCost = cost }
            }
        }
        // 不穿越框线来强行建立联系；无法安全连通时仍由编号和颜色表达归属。
        return best
    }
}

internal object PipelineAnnotationLayout {
    fun place(view: Rectangle, anchor: Rectangle, size: Dimension, placed: List<Rectangle>, rois: List<Rectangle>, gap: Int): Rectangle? {
        if (size.width > view.width || size.height > view.height) return null
        // 大 ROI 的内部可以放说明，但所有边线和已有说明都必须完整露出。
        val obstacles = placed.map { Rectangle(it).apply { grow(gap, gap) } } + rois.flatMap { roi ->
            listOf(Rectangle(roi.x, roi.y, roi.width, 1), Rectangle(roi.x, roi.y + roi.height, roi.width, 1),
                Rectangle(roi.x, roi.y, 1, roi.height), Rectangle(roi.x + roi.width, roi.y, 1, roi.height))
                .map { it.apply { grow(gap, gap) } }
        }.filter { it.intersects(view) }
        fun candidate(x: Int, y: Int) = Rectangle(x.coerceIn(view.x, view.x + view.width - size.width),
            y.coerceIn(view.y, view.y + view.height - size.height), size.width, size.height)
        fun fits(rectangle: Rectangle) = obstacles.none { it.intersects(rectangle) }
        val preferred = listOf(candidate(anchor.x, anchor.y - size.height - gap - 1),
            candidate(anchor.x, anchor.y + anchor.height + gap + 1),
            candidate(anchor.x + anchor.width + gap + 1, anchor.y),
            candidate(anchor.x - size.width - gap - 1, anchor.y))
        preferred.firstOrNull(::fits)?.let { return it }
        // 可行矩形可以平移到视口或障碍物边缘；沿这些边缘搜索，不逐像素遍历画面。
        val xs = (listOf(view.x, view.x + view.width - size.width, anchor.x) +
            obstacles.flatMap { listOf(it.x - size.width, it.x + it.width) })
            .map { it.coerceIn(view.x, view.x + view.width - size.width) }.distinct()
        val ys = (listOf(view.y, view.y + view.height - size.height, anchor.y) +
            obstacles.flatMap { listOf(it.y - size.height, it.y + it.height) })
            .map { it.coerceIn(view.y, view.y + view.height - size.height) }.distinct()
        return xs.asSequence().flatMap { x -> ys.asSequence().map { y -> candidate(x, y) } }
            .filter(::fits).minByOrNull { rectangle ->
                preferred.minOf { target ->
                    val dx = rectangle.x.toLong() - target.x
                    val dy = rectangle.y.toLong() - target.y
                    dx * dx + dy * dy
                }
            }
    }
}
