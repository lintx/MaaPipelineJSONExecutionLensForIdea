package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

internal class PipelineChangeDialog(project: Project, caption: String, old: String, value: String, note: String) : DialogWrapper(project) {
    private val content = JPanel(VerticalLayout(6)).apply {
        if (note.isNotBlank()) add(PipelineWrappingLabel(note))
        if (old.isNotBlank()) {
            add(PipelineWrappingLabel("原值"))
            add(PipelineWrappingLabel(old))
        }
        add(PipelineWrappingLabel(if (old.isBlank()) "新增内容" else "新值"))
        add(PipelineWrappingLabel(value))
    }
    init { title = caption; setOKButtonText("应用"); init() }
    override fun createCenterPanel(): JComponent = if (content.preferredSize.height > JBUI.scale(350))
        JBScrollPane(content).apply { preferredSize = JBUI.size(620, 350) }
    else content.apply { preferredSize = java.awt.Dimension(JBUI.scale(560), preferredSize.height) }
}
