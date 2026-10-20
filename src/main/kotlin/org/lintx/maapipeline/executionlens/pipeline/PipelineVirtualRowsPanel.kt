package org.lintx.maapipeline.executionlens.pipeline

import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.Component
import java.awt.Container
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.paint.LinePainter2D
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener

internal data class PipelineVirtualRow(
    val key: String,
    val estimatedHeight: Int,
    val revision: Any? = null,
    val depth: Int = 0,
    val createComponent: () -> JComponent,
)

internal class PipelineRowGeometry(heights: List<Int>) {
    private val offsets = IntArray(heights.size + 1).apply {
        heights.forEachIndexed { index, height -> this[index + 1] = this[index] + height.coerceAtLeast(1) }
    }

    val totalHeight: Int get() = offsets.last()
    val size: Int get() = offsets.size - 1
    fun top(index: Int): Int = offsets[index]
    fun height(index: Int): Int = offsets[index + 1] - offsets[index]

    fun rowAt(y: Int): Int {
        if (size == 0) return -1
        val offset = y.coerceIn(0, totalHeight - 1)
        val result = offsets.binarySearch(offset)
        return if (result >= 0) result else -result - 2
    }

    fun visibleRows(y: Int, height: Int, overscan: Int = 0): IntRange {
        if (size == 0 || height <= 0) return IntRange.EMPTY
        return rowAt((y - overscan).coerceAtLeast(0))..rowAt(y + height + overscan - 1)
    }
}

internal class PipelineVirtualRowsPanel : JPanel(null), Scrollable {
    private var rows = emptyList<PipelineVirtualRow>()
    private var heights = mutableListOf<Int>()
    private var geometry = PipelineRowGeometry(emptyList())
    private var indentation = PipelineIndentScopes(emptyList())
    private var activeRowKey: String? = null
    private var playbackRowKey: String? = null
    private var playbackScrollRequest = 0L
    private val visibleComponents = mutableMapOf<Int, JComponent>()
    private var viewport: JViewport? = null
    private var updateQueued = false
    private val viewportListener = ChangeListener { queueUpdate() }

    init {
        isOpaque = false
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = queueUpdate()
        })
    }

    fun setRows(newRows: List<PipelineVirtualRow>) {
        fun unchanged(previous: PipelineVirtualRow, next: PipelineVirtualRow): Boolean =
            previous.key == next.key && previous.depth == next.depth && next.revision != null && previous.revision == next.revision
        if (rows.size == newRows.size && rows.indices.all { unchanged(rows[it], newRows[it]) }) {
            rows = newRows
            return
        }
        val currentY = viewport?.viewPosition?.y ?: 0
        val anchorIndex = geometry.rowAt(currentY)
        val anchorKey = rows.getOrNull(anchorIndex)?.key
        val anchorOffset = if (anchorIndex >= 0) currentY - geometry.top(anchorIndex) else 0
        val previousIndices = rows.withIndex().associate { it.value.key to it.index }
        val retained = mutableMapOf<Int, JComponent>()
        val newHeights = newRows.mapIndexed { index, row ->
            val previousIndex = previousIndices[row.key]
            if (previousIndex != null && unchanged(rows[previousIndex], row)) {
                visibleComponents.remove(previousIndex)?.let { retained[index] = it }
                heights[previousIndex]
            } else row.estimatedHeight.coerceAtLeast(1)
        }.toMutableList()
        // 版本包含展示内容和导航范围，只有未变化的行才能保留组件及其事件回调。
        visibleComponents.values.forEach(::remove)
        visibleComponents.clear()
        visibleComponents.putAll(retained)
        rows = newRows
        indentation = PipelineIndentScopes(rows.map { it.depth })
        heights = newHeights
        geometry = PipelineRowGeometry(heights)
        val newAnchor = rows.indexOfFirst { it.key == anchorKey }
        if (newAnchor >= 0) {
            viewport?.viewPosition = java.awt.Point(0, geometry.top(newAnchor) + anchorOffset)
        }
        revalidate()
        repaint()
        queueUpdate()
    }

    fun scrollAnchor(): Pair<String, Int>? {
        val position = viewport?.viewPosition?.y ?: return null
        val index = geometry.rowAt(position)
        return rows.getOrNull(index)?.key?.let { it to position - geometry.top(index) }
    }

    fun restoreAnchor(key: String, offset: Int = 0) {
        val index = rows.indexOfFirst { it.key == key }
        if (index < 0) return
        SwingUtilities.invokeLater {
            val currentIndex = rows.indexOfFirst { it.key == key }
            if (currentIndex >= 0) viewport?.viewPosition = java.awt.Point(0, geometry.top(currentIndex) + offset.coerceAtLeast(0))
        }
    }

    fun followPlaybackRow(key: String?) {
        val request = ++playbackScrollRequest
        if (key == null) return
        SwingUtilities.invokeLater {
            if (request != playbackScrollRequest) return@invokeLater
            val currentViewport = viewport ?: return@invokeLater
            val index = rows.indexOfFirst { it.key == key }
            if (index < 0) return@invokeLater
            val viewportHeight = currentViewport.extentSize.height
            // 使用现有内容的滚动范围，不为靠近开头或结尾的节点制造额外空白。
            val target = (geometry.top(index) - viewportHeight * 2 / 5)
                .coerceIn(0, (geometry.totalHeight - viewportHeight).coerceAtLeast(0))
            currentViewport.viewPosition = java.awt.Point(0, target)
        }
    }

    override fun addNotify() {
        super.addNotify()
        viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
        viewport?.addChangeListener(viewportListener)
        queueUpdate()
    }

    fun activateRow(key: String) {
        if (key == activeRowKey) return
        activeRowKey = key
        repaint()
    }

    fun setPlaybackRow(key: String?) {
        if (playbackRowKey == key) return
        playbackRowKey = key
        repaint()
    }

    fun refreshVisibleContent() = queueUpdate()

    private fun trackActiveRow(component: Component, key: String) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = activateRow(key)
        })
        component.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = activateRow(key)
        })
        if (component is Container) component.components.forEach { trackActiveRow(it, key) }
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (rows.isEmpty()) return
        val scheme = EditorColorsManager.getInstance().globalScheme
        val regular = scheme.getColor(EditorColors.INDENT_GUIDE_COLOR) ?: JBColor.border()
        val selected = scheme.getColor(EditorColors.SELECTED_INDENT_GUIDE_COLOR) ?: regular
        val active = indentation.at(rows.indexOfFirst { it.key == activeRowKey })
        val clip = graphics.clipBounds ?: Rectangle(0, 0, width, height)
        val playback = rows.indexOfFirst { it.key == playbackRowKey }
        if (playback >= 0) {
            graphics.color = scheme.getColor(EditorColors.CARET_ROW_COLOR) ?: UIUtil.getListSelectionBackground(false)
            graphics.fillRect(0, geometry.top(playback), width, geometry.height(playback))
        }
        // 整块绘制跨越行间距与虚拟化边界，复用原生编辑器的主题色和 HiDPI 线条绘制。
        for (scope in indentation.scopes) {
            val top = geometry.top(scope.firstRow) + geometry.height(scope.firstRow)
            val bottom = geometry.top(scope.lastRow) + geometry.height(scope.lastRow) / 2
            if (bottom < clip.y || top > clip.y + clip.height) continue
            val guideX = JBUI.scale(16 + (scope.depth - 1) * 12 + 8)
            graphics.color = if (scope == active) selected else regular
            LinePainter2D.paint(graphics as Graphics2D, guideX.toDouble(), top.toDouble(), guideX.toDouble(), bottom.toDouble())
        }
    }

    override fun removeNotify() {
        playbackScrollRequest++
        viewport?.removeChangeListener(viewportListener)
        viewport = null
        super.removeNotify()
    }

    private fun queueUpdate() {
        if (updateQueued) return
        updateQueued = true
        SwingUtilities.invokeLater {
            updateQueued = false
            updateVisibleRows()
        }
    }

    private fun updateVisibleRows() {
        val currentViewport = viewport ?: return
        val viewRect = currentViewport.viewRect
        if (viewRect.height <= 0 || rows.isEmpty()) return
        val visibleRange = geometry.visibleRows(viewRect.y, viewRect.height, viewRect.height)
        val anchor = geometry.rowAt(viewRect.y)
        val anchorOffset = viewRect.y - geometry.top(anchor)
        visibleComponents.keys.filter { it !in visibleRange }.forEach { index ->
            remove(visibleComponents.remove(index))
        }
        var resized = false
        for (index in visibleRange) {
            val component = visibleComponents.getOrPut(index) {
                rows[index].createComponent().also { trackActiveRow(it, rows[index].key); add(it) }
            }
            val measuredHeight = component.preferredSize.height.coerceAtLeast(1)
            if (heights[index] != measuredHeight) {
                heights[index] = measuredHeight
                resized = true
            }
        }
        if (resized) {
            // 行高首次测量后保持首个可见语义行的位置，避免滚动中新增组件使内容跳动。
            geometry = PipelineRowGeometry(heights)
            revalidate()
            currentViewport.viewPosition = java.awt.Point(0, geometry.top(anchor) + anchorOffset)
            queueUpdate()
        }
        visibleComponents.forEach { (index, component) ->
            component.setBounds(0, geometry.top(index), currentViewport.extentSize.width, geometry.height(index))
        }
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(viewport?.extentSize?.width ?: 600, geometry.totalHeight)
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 24
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        (visibleRect.height - 24).coerceAtLeast(24)
}
