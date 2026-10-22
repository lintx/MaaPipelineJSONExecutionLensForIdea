package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.util.ui.JBUI
import javax.swing.Icon
import javax.swing.JButton

internal fun pipelineIconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton = JButton(icon).apply {
    toolTipText = tooltip
    isFocusable = false
    margin = JBUI.emptyInsets()
    preferredSize = JBUI.size(24, 24)
    minimumSize = preferredSize
    addActionListener { action() }
}
