package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.JsonLanguage
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class PipelineJsonEditor(private val project: Project, private val initialText: String) : JPanel(BorderLayout()) {
    private var pristineText: String? = null
    private var formatRequested = false
    val field = WriteIntentReadAction.compute<LanguageTextField> {
        LanguageTextField(JsonLanguage.INSTANCE, project, initialText, false).apply {
            addSettingsProvider { editor ->
                editor.settings.isLineNumbersShown = true
                editor.settings.isFoldingOutlineShown = true
                editor.settings.isUseSoftWraps = false
                editor.settings.isAdditionalPageAtBottom = false
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
            }
        }
    }

    init {
        preferredSize = JBUI.size(720, 420)
        add(field, BorderLayout.CENTER)
        add(JButton("格式化 JSON").apply {
            toolTipText = "使用 IDEA 当前 JSON 代码样式，仅格式化此编辑片段；也可使用编辑器的原生格式化快捷键。"
            addActionListener { format() }
        }, BorderLayout.SOUTH)
        format(initial = true)
        pristineText = field.text
    }

    val value: String get() = if (!formatRequested && this.field.text == pristineText) initialText.trim() else this.field.text.trim()

    fun format(initial: Boolean = false) {
        if (!initial) formatRequested = true
        WriteCommandAction.runWriteCommandAction(project, "格式化 Pipeline JSON 片段", null, Runnable {
            val manager = PsiDocumentManager.getInstance(project)
            manager.commitDocument(field.document)
            manager.getPsiFile(field.document)?.let { CodeStyleManager.getInstance(project).reformat(it) }
            manager.doPostponedOperationsAndUnblockDocument(field.document)
        })
    }
}
