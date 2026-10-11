package com.maapipeline.executionlens.pipeline

import java.awt.Dimension
import java.awt.Rectangle
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
        val currentY = viewport?.viewPosition?.y ?: 0
        val anchorIndex = geometry.rowAt(currentY)
        val anchorKey = rows.getOrNull(anchorIndex)?.key
        val anchorOffset = if (anchorIndex >= 0) currentY - geometry.top(anchorIndex) else 0
        val previousHeights = rows.mapIndexed { index, row -> row.key to heights[index] }.toMap()
        removeAll()
        visibleComponents.clear()
        rows = newRows
        heights = rows.map { previousHeights[it.key] ?: it.estimatedHeight.coerceAtLeast(1) }.toMutableList()
        geometry = PipelineRowGeometry(heights)
        val newAnchor = rows.indexOfFirst { it.key == anchorKey }
        if (newAnchor >= 0) {
            viewport?.viewPosition = java.awt.Point(0, geometry.top(newAnchor) + anchorOffset)
        }
        revalidate()
        repaint()
        queueUpdate()
    }

    override fun addNotify() {
        super.addNotify()
        viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
        viewport?.addChangeListener(viewportListener)
        queueUpdate()
    }

    override fun removeNotify() {
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
            val component = visibleComponents.getOrPut(index) { rows[index].createComponent().also { add(it) } }
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
