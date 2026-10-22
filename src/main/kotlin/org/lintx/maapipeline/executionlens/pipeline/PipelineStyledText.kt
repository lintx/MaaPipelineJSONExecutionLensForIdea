package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Rectangle
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

internal class PipelineStyledText(value: String) : JTextPane() {
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
        val scheme = EditorColorsManager.getInstance().globalScheme
        PipelineTokenColors.ranges(value).forEach { (range, key) ->
            val attributes = SimpleAttributeSet()
            scheme.getAttributes(key)?.foregroundColor?.let { StyleConstants.setForeground(attributes, it) }
            StyleConstants.setBold(attributes, key != com.intellij.openapi.editor.DefaultLanguageHighlighterColors.NUMBER)
            styledDocument.setCharacterAttributes(range.startOffset, range.length, attributes, false)
        }
    }
    override fun getPreferredSize(): Dimension {
        val available = (width.takeIf { it > 0 } ?: parent?.width?.takeIf { it > 0 } ?: JBUI.scale(420))
        val view = getUI().getRootView(this)
        view.setSize((available - insets.left - insets.right).coerceAtLeast(1).toFloat(), Float.MAX_VALUE)
        return Dimension(available, kotlin.math.ceil(view.getPreferredSpan(View.Y_AXIS).toDouble()).toInt() + insets.top + insets.bottom)
    }
    override fun getMinimumSize() = Dimension(0, preferredSize.height)
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
}
