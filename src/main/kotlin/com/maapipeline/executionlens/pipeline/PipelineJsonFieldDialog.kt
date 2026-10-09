package com.maapipeline.executionlens.pipeline

import com.intellij.json.JsonFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal class PipelineJsonFieldDialog(
    private val project: Project,
    fieldName: String,
    initialText: String,
) : DialogWrapper(project, true) {
    private val textArea = JBTextArea(initialText).apply {
        lineWrap = false
        wrapStyleWord = false
        tabSize = 2
    }

    init {
        title = "编辑 Pipeline 字段：$fieldName"
        setOKButtonText("应用")
        init()
    }

    val value: String
        get() = textArea.text.trim()

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        border = JBUI.Borders.empty(8)
        add(JLabel("编辑 JSON 值；应用前会校验语法，取消不会修改源码。"), BorderLayout.NORTH)
        add(JBScrollPane(textArea).apply {
            preferredSize = JBUI.size(720, 420)
        }, BorderLayout.CENTER)
    }

    override fun doValidate(): ValidationInfo? {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
            "PipelineField.json",
            JsonFileType.INSTANCE,
            value,
        )
        if (PsiTreeUtil.hasErrorElements(psiFile)) {
            return ValidationInfo("JSON 语法错误，请修正后再应用。", textArea)
        }
        return null
    }
}
