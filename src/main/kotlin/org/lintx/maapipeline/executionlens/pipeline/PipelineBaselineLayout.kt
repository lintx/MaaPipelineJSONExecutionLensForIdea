package org.lintx.maapipeline.executionlens.pipeline

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension

internal class PipelineBaselineLayout : BorderLayout() {
    private fun components(parent: Container): List<Pair<Component, Dimension>> {
        val label = getLayoutComponent(WEST)
        val value = getLayoutComponent(CENTER)
        val button = getLayoutComponent(EAST)
        val labelSize = label?.preferredSize ?: Dimension()
        val buttonSize = button?.preferredSize ?: Dimension()
        val width = (parent.width.takeIf { it > 0 } ?: 480) - parent.insets.left - parent.insets.right
        val valueWidth = (width - labelSize.width - buttonSize.width).coerceAtLeast(0)
        val valueSize = when (value) {
            is PipelineWrappingLabel -> value.preferredSizeForWidth(valueWidth)
            is PipelineStyledText -> value.preferredSizeForWidth(valueWidth)
            else -> value?.preferredSize ?: Dimension()
        }
        return listOfNotNull(label?.let { it to labelSize }, value?.let { it to Dimension(valueWidth, valueSize.height) },
            button?.let { it to buttonSize })
    }

    private fun baseline(component: Component, size: Dimension): Int =
        component.getBaseline(size.width, size.height).takeIf { it >= 0 } ?: size.height / 2

    override fun preferredLayoutSize(parent: Container): Dimension {
        val parts = components(parent)
        val ascent = parts.maxOfOrNull { (component, size) -> baseline(component, size) } ?: 0
        val descent = parts.maxOfOrNull { (component, size) -> size.height - baseline(component, size) } ?: 0
        return Dimension(parts.sumOf { it.second.width } + parent.insets.left + parent.insets.right,
            ascent + descent + parent.insets.top + parent.insets.bottom)
    }

    override fun minimumLayoutSize(parent: Container) = Dimension(0, preferredLayoutSize(parent).height)

    override fun layoutContainer(parent: Container) {
        val parts = components(parent)
        val ascent = parts.maxOfOrNull { (component, size) -> baseline(component, size) } ?: 0
        var left = parent.insets.left
        parts.forEach { (component, size) ->
            component.setBounds(left, parent.insets.top + ascent - baseline(component, size), size.width, size.height)
            left += size.width
        }
    }
}
