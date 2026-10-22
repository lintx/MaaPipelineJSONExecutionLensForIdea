package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.paint.LinePainter2D
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingUtilities

internal data class PipelineRow(
    val key: String,
    val revision: Any? = null,
    val depth: Int = 0,
    val createComponent: () -> JComponent,
)

internal class PipelineRowsPanel : JPanel(GridBagLayout()), Scrollable {
    private var rows = emptyList<PipelineRow>()
    private val rowComponents = linkedMapOf<String, JComponent>()
    private var indentation = PipelineIndentScopes(emptyList())
    private var activeRowKey: String? = null
    private var playbackRowKey: String? = null
    private var playbackScrollRequest = 0L
    private var searchQuery = ""
    private val searchHighlights = mutableMapOf<javax.swing.text.JTextComponent, MutableList<Any>>()
    private val viewport: JViewport? get() = parent as? JViewport

    init {
        isOpaque = false
    }

    fun setRows(newRows: List<PipelineRow>) {
        fun unchanged(previous: PipelineRow, next: PipelineRow): Boolean =
            previous.key == next.key && previous.depth == next.depth && next.revision != null && previous.revision == next.revision
        if (rows.size == newRows.size && rows.indices.all { unchanged(rows[it], newRows[it]) }) {
            rows = newRows
            return
        }
        val anchor = scrollAnchor()
        val previous = rows.associateBy { it.key }
        val nextComponents = newRows.associate { row ->
            val retained = previous[row.key]?.takeIf { unchanged(it, row) }?.let { rowComponents[row.key] }
            row.key to (retained ?: row.createComponent().also {
                it.putClientProperty("pipeline.row.key", row.key)
                trackActiveRow(it, row.key)
            })
        }
        val retained = nextComponents.values.toSet()
        rowComponents.values.filterNot { it in retained }.forEach(::remove)
        nextComponents.values.forEachIndexed { index, component ->
            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = index
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                this.anchor = GridBagConstraints.NORTHWEST
            }
            if (component.parent !== this) add(component, constraints, index)
            else {
                (layout as GridBagLayout).setConstraints(component, constraints)
                if (getComponent(index) !== component) setComponentZOrder(component, index)
            }
        }
        rowComponents.clear()
        rowComponents.putAll(nextComponents)
        rows = newRows
        indentation = PipelineIndentScopes(rows.map { it.depth })
        findRows(searchQuery)
        revalidate()
        repaint()
        if (anchor != null) restorePosition(anchor.first, anchor.second)
    }

    fun scrollAnchor(): Pair<String, Int>? {
        val position = viewport?.viewPosition?.y ?: return null
        val component = getComponentAt(0, position) as? JComponent ?: return null
        val key = component.getClientProperty("pipeline.row.key") as? String ?: return null
        return key to position - component.y
    }

    fun restoreAnchor(key: String, offset: Int = 0) {
        if (key !in rowComponents) return
        SwingUtilities.invokeLater { restorePosition(key, offset) }
    }

    private fun restorePosition(key: String, offset: Int) {
        val currentViewport = viewport ?: return
        currentViewport.doLayout()
        doLayout()
        val component = rowComponents[key] ?: return
        val target = component.y + offset.coerceIn(0, (component.height - 1).coerceAtLeast(0))
        currentViewport.viewPosition = Point(0, target.coerceIn(0, (height - currentViewport.extentSize.height).coerceAtLeast(0)))
    }

    fun followPlaybackRow(key: String?) {
        val request = ++playbackScrollRequest
        if (key == null) return
        SwingUtilities.invokeLater {
            if (request != playbackScrollRequest) return@invokeLater
            val currentViewport = viewport ?: return@invokeLater
            currentViewport.doLayout()
            doLayout()
            val component = rowComponents[key] ?: return@invokeLater
            val viewportHeight = currentViewport.extentSize.height
            val target = (component.y - viewportHeight * 2 / 5)
                .coerceIn(0, (height - viewportHeight).coerceAtLeast(0))
            currentViewport.viewPosition = Point(0, target)
        }
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

    fun findRows(query: String): List<String> {
        searchHighlights.forEach { (text, highlights) -> highlights.forEach(text.highlighter::removeHighlight) }
        searchHighlights.clear()
        searchQuery = query
        if (query.isBlank()) return emptyList()
        val scheme = EditorColorsManager.getInstance().globalScheme
        val background = scheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
            ?: UIUtil.getListSelectionBackground(false)
        val painter = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(background)
        fun matches(component: Component): Boolean {
            if (!component.isVisible) return false
            if (component is javax.swing.text.JTextComponent) {
                val text = component.text.orEmpty()
                var index = text.indexOf(query, ignoreCase = true)
                val found = index >= 0
                while (index >= 0) {
                    searchHighlights.getOrPut(component, ::mutableListOf) += component.highlighter.addHighlight(index, index + query.length, painter)
                    index = text.indexOf(query, index + query.length, ignoreCase = true)
                }
                return found
            }
            if (component is javax.swing.JLabel) return component.text.orEmpty().contains(query, ignoreCase = true)
            if (component !is Container) return false
            var found = false
            component.components.forEach { if (matches(it)) found = true }
            return found
        }
        return rowComponents.filterValues(::matches).keys.toList()
    }

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
        rowComponents[playbackRowKey]?.let { component ->
            graphics.color = scheme.getColor(EditorColors.CARET_ROW_COLOR) ?: UIUtil.getListSelectionBackground(false)
            graphics.fillRect(0, component.y, width, component.height)
        }
        for (scope in indentation.scopes) {
            val first = getComponent(scope.firstRow)
            val last = getComponent(scope.lastRow)
            val top = first.y + first.height
            val bottom = last.y + last.height / 2
            if (bottom < clip.y || top > clip.y + clip.height) continue
            val guideX = JBUI.scale(16 + (scope.depth - 1) * 12 + 8)
            graphics.color = if (scope == active) selected else regular
            LinePainter2D.paint(graphics as Graphics2D, guideX.toDouble(), top.toDouble(), guideX.toDouble(), bottom.toDouble())
        }
    }

    override fun removeNotify() {
        playbackScrollRequest++
        super.removeNotify()
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        (visibleRect.height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))
}
