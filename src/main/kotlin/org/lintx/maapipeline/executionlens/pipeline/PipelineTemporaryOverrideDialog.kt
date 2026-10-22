package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import javax.swing.JComponent

internal class PipelineTemporaryOverrideDialog(private val project: Project, nodeName: String, initial: String,
    private val properties: com.google.gson.JsonObject) : DialogWrapper(project) {
    private val editor = PipelineJsonEditor(project, initial)
    val value: String get() = editor.value
    init { title = "临时属性 · $nodeName"; setOKButtonText("应用到当前任务视图"); init() }
    override fun createCenterPanel(): JComponent = javax.swing.JPanel(java.awt.BorderLayout(0, 6)).apply {
        add(javax.swing.JPanel(java.awt.BorderLayout()).apply {
            add(com.intellij.ui.components.JBLabel("仅覆盖当前节点的所填属性，不修改源文件。"), java.awt.BorderLayout.NORTH)
            add(javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 4)).apply {
                add(javax.swing.JButton("添加属性…").apply {
                    addActionListener {
                        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(properties.keySet().sorted())
                            .setTitle("选择要覆盖的属性").setNamerForFiltering { it }
                            .setItemChosenCallback { key ->
                                val patch = PipelineVisualModel.parse(editor.value) ?: return@setItemChosenCallback
                                if (!patch.has(key)) {
                                    patch.add(key, properties.get(key).deepCopy())
                                    setPatch(patch.toString())
                                }
                            }.createPopup().showUnderneathOf(this)
                    }
                })
                add(javax.swing.JButton("清除此节点覆盖").apply { addActionListener { setPatch("{}") } })
            }, java.awt.BorderLayout.CENTER)
        }, java.awt.BorderLayout.NORTH)
        add(editor, java.awt.BorderLayout.CENTER)
    }

    private fun setPatch(text: String) {
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, Runnable { editor.field.document.setText(text) })
        editor.format()
    }

    override fun doValidate(): ValidationInfo? {
        val patch = PipelineVisualModel.parse(value)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return ValidationInfo("请输入节点属性的 JSON 对象", editor.field)
        if (patch.entrySet().any { !it.value.isJsonObject })
            return ValidationInfo("每个节点属性的值都必须是 JSON 对象", editor.field)
        return null
    }
}
