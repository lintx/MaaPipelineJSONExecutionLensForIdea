package com.maapipeline.executionlens.pipeline

import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.jsonSchema.impl.JsonOriginalPsiWalker
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import com.jetbrains.jsonSchema.impl.NetworkntValidationBridge
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal class PipelineNodeDialog(
    private val project: Project,
) : DialogWrapper(project, true) {
    private val nameField = JBTextField()
    private val bodyField = JBTextArea("{\n  \"recognition\": {},\n  \"action\": {}\n}").apply {
        lineWrap = false
        wrapStyleWord = false
        tabSize = 2
    }

    init {
        title = "添加 Pipeline 节点"
        setOKButtonText("添加")
        init()
    }

    val nodeName: String
        get() = nameField.text.trim()

    val nodeBody: String
        get() = bodyField.text.trim()

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(8)
        val form = JPanel(GridBagLayout())
        form.add(
            JLabel("节点名："),
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, JBUI.scale(6), JBUI.scale(8))
            },
        )
        form.add(
            nameField.apply { preferredSize = JBUI.size(420, 30) },
            GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(0, 0, JBUI.scale(6), 0)
            },
        )
        form.add(
            JLabel("节点对象："),
            GridBagConstraints().apply {
                gridx = 0
                gridy = 1
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, 0, JBUI.scale(8))
            },
        )
        form.add(
            JBScrollPane(bodyField).apply { preferredSize = JBUI.size(620, 360) },
            GridBagConstraints().apply {
                gridx = 1
                gridy = 1
                fill = GridBagConstraints.BOTH
                weightx = 1.0
                weighty = 1.0
            },
        )
        add(form, BorderLayout.CENTER)
        add(JLabel("节点对象必须是 JSON 对象；Pipeline JSON/JSONC 仍是唯一事实来源。"), BorderLayout.SOUTH)
    }

    override fun doValidate(): ValidationInfo? = ReadAction.compute<ValidationInfo?, RuntimeException> { validateNode() }

    private fun validateNode(): ValidationInfo? {
        if (nodeName.isEmpty()) return ValidationInfo("节点名不能为空。", nameField)
        if (nodeName.contains('"') || nodeName.contains('\n') || nodeName.contains('\r')) {
            return ValidationInfo("节点名不能包含引号或换行。", nameField)
        }
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
            "PipelineNode.json",
            JsonFileType.INSTANCE,
            nodeBody,
        )
        if (PsiTreeUtil.hasErrorElements(psiFile)) {
            return ValidationInfo("节点对象 JSON 语法错误。", bodyField)
        }
        if ((psiFile as? JsonFile)?.topLevelValue !is JsonObject) {
            return ValidationInfo("节点对象必须是 JSON 对象。", bodyField)
        }
        validateAgainstPipelineSchema()?.let { error ->
            return ValidationInfo("Pipeline Schema 校验失败：$error", bodyField)
        }
        return null
    }

    private fun validateAgainstPipelineSchema(): String? {
        val projectBasePath = project.basePath ?: return null
        val schemaPath = Paths.get(projectBasePath, "tools", "schema", "pipeline.schema.json")
        val schemaFile = LocalFileSystem.getInstance().findFileByPath(schemaPath.toString()) ?: return null
        val syntheticText = "{\n  ${quoteJsonString(nodeName)}: $nodeBody\n}"
        val syntheticFile = PsiFileFactory.getInstance(project).createFileFromText(
            "PipelineNodeSchemaValidation.json",
            JsonFileType.INSTANCE,
            syntheticText,
        ) as? JsonFile ?: return "无法创建临时 JSON 文档"
        val rootValue = syntheticFile.topLevelValue ?: return "无法读取临时 JSON 文档"
        return try {
            val errors = NetworkntValidationBridge.getInstance(project).validate(
                schemaFile,
                JsonOriginalPsiWalker.INSTANCE,
                rootValue,
                JsonSchemaVersion.SCHEMA_2020_12,
            )
            errors.values.firstOrNull()?.message
        } catch (exception: Exception) {
            "无法读取 MaaEnd Pipeline Schema：${exception.message ?: "未知错误"}"
        }
    }

    private fun quoteJsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
