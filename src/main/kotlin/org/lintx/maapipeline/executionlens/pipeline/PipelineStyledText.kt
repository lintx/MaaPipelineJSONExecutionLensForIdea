package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import com.intellij.openapi.util.TextRange
import javax.swing.JTextPane
import javax.swing.text.*

internal class PipelineSelectionCaret : DefaultCaret() {
    init {
        updatePolicy = NEVER_UPDATE
    }

    override fun adjustVisibility(location: Rectangle) {
        // 只读文本的初始化、样式与图片更新不能抢走父面板滚动；用户选中文本时仍使用原生滚动。
        if (component?.isFocusOwner == true) super.adjustVisibility(location)
    }
}

internal data class PipelineTextLink(val range: TextRange, val tooltip: String, val navigate: () -> Unit)

internal class PipelineStyledText(value: String) : JTextPane() {
    private var flowSelected = false
    private var flowHovered = false
    var navigationLink: PipelineTextLink? = null
    init {
        editorKit = object : StyledEditorKit() {
            override fun getViewFactory(): ViewFactory = ViewFactory { element ->
                when (element.name) {
                    AbstractDocument.ContentElementName -> object : LabelView(element) {
                        override fun getMinimumSpan(axis: Int): Float = if (axis == View.X_AXIS) 0f else super.getMinimumSpan(axis)
                    }
                    AbstractDocument.ParagraphElementName -> ParagraphView(element)
                    AbstractDocument.SectionElementName -> BoxView(element, View.Y_AXIS)
                    else -> LabelView(element)
                }
            }
        }
        caret = PipelineSelectionCaret()
        text = value
        isEditable = false
        isOpaque = false
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getLabelForeground()
        margin = JBUI.insets(2, 4)
        alignmentX = LEFT_ALIGNMENT
        updateTextColors()
    }

    fun setFlowSelected(selected: Boolean) {
        if (flowSelected == selected) return
        flowSelected = selected
        updateTextColors()
    }

    fun setFlowHovered(hovered: Boolean) {
        if (flowHovered == hovered) return
        flowHovered = hovered
        updateTextColors()
    }

    fun navigateLink(event: MouseEvent): Boolean {
        val link = navigationLink ?: return false
        val offset = textOffsetAt(event.point) ?: return false
        if (offset !in link.range.startOffset until link.range.endOffset) return false
        link.navigate()
        return true
    }

    fun textOffsetAt(point: Point): Int? {
        if (!contains(point)) return null
        val nearest = viewToModel2D(point)
        // Swing 返回最近的插入位置；必须检查字符矩形，避免空白被当成链接。
        return listOf(nearest, nearest - 1).firstOrNull { offset ->
            if (offset !in text.indices || text[offset].isWhitespace()) return@firstOrNull false
            val start = modelToView2D(offset) ?: return@firstOrNull false
            val end = modelToView2D(offset + 1) ?: return@firstOrNull false
            val characterWidth = if (start.y == end.y) end.x - start.x
                else getFontMetrics(font).charWidth(text[offset]).toDouble()
            point.x >= start.x && point.x < start.x + characterWidth &&
                point.y >= start.y && point.y < start.y + start.height
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val offset = textOffsetAt(event.point) ?: return null
        return navigationLink?.takeIf { offset in it.range.startOffset until it.range.endOffset }?.tooltip
            ?: super.getToolTipText(event)
    }

    private fun updateTextColors() {
        val highlighted = flowSelected || flowHovered
        val normal = UIUtil.getLabelForeground()
        val base = if (highlighted) JBColor.namedColor("Link.hoverForeground", normal) else normal
        val plain = SimpleAttributeSet().apply { StyleConstants.setForeground(this, base) }
        styledDocument.setCharacterAttributes(0, document.length, plain, false)
        val scheme = EditorColorsManager.getInstance().globalScheme
        PipelineTokenColors.ranges(text).forEach { (range, key) ->
            val attributes = SimpleAttributeSet()
            if (!highlighted || key == com.intellij.openapi.editor.DefaultLanguageHighlighterColors.METADATA) {
                scheme.getAttributes(key)?.foregroundColor?.let { StyleConstants.setForeground(attributes, it) }
            }
            StyleConstants.setBold(attributes, key != com.intellij.openapi.editor.DefaultLanguageHighlighterColors.NUMBER)
            styledDocument.setCharacterAttributes(range.startOffset, range.length, attributes, false)
        }
        Regex("[→{}]").findAll(text).forEach { match ->
            val attributes = SimpleAttributeSet().apply { StyleConstants.setForeground(this, normal) }
            styledDocument.setCharacterAttributes(match.range.first, match.value.length, attributes, false)
        }
        Regex("（默认）").findAll(text).forEach { match ->
            val attributes = SimpleAttributeSet().apply { StyleConstants.setForeground(this, UIUtil.getContextHelpForeground()) }
            styledDocument.setCharacterAttributes(match.range.first, match.value.length, attributes, false)
        }
    }
    override fun getPreferredSize(): Dimension {
        val available = (width.takeIf { it > 0 } ?: parent?.width?.takeIf { it > 0 } ?: JBUI.scale(420))
        return preferredSizeForWidth(available)
    }
    fun preferredSizeForWidth(available: Int): Dimension {
        val view = getUI().getRootView(this)
        view.setSize((available - insets.left - insets.right).coerceAtLeast(1).toFloat(), Float.MAX_VALUE)
        return Dimension(available, kotlin.math.ceil(view.getPreferredSpan(View.Y_AXIS).toDouble()).toInt() + insets.top + insets.bottom)
    }
    override fun getBaseline(width: Int, height: Int): Int = insets.top + getFontMetrics(font).ascent
    override fun getMinimumSize() = Dimension(0, preferredSize.height)
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
}
