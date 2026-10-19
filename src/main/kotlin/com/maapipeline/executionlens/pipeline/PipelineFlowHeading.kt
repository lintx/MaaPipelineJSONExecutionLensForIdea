package com.maapipeline.executionlens.pipeline

import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

internal class PipelineFlowHeading(
    private val condition: JComponent,
    private val target: JComponent,
    private val ending: JComponent,
) : JPanel(null) {
    init {
        isOpaque = false
        add(condition)
        add(target)
        add(ending)
    }

    override fun getPreferredSize(): Dimension = Dimension(
        condition.preferredSize.width + target.preferredSize.width + ending.preferredSize.width,
        maxOf(condition.preferredSize.height, target.preferredSize.height, ending.preferredSize.height),
    )

    override fun doLayout() {
        val endWidth = ending.preferredSize.width.coerceAtMost(width.coerceAtLeast(0))
        val available = (width - endWidth).coerceAtLeast(0)
        // 条件先收缩，为节点名和块边界保留空间；完整条件始终可以在详情与悬浮提示查看。
        val targetWidth = target.preferredSize.width.coerceAtMost(
            maxOf(available - condition.preferredSize.width, available * 2 / 3),
        )
        val conditionWidth = condition.preferredSize.width.coerceAtMost(available - targetWidth)
        condition.setBounds(0, 0, conditionWidth, height)
        target.setBounds(conditionWidth, 0, targetWidth, height)
        ending.setBounds(conditionWidth + targetWidth, 0, endWidth, height)
    }
}
