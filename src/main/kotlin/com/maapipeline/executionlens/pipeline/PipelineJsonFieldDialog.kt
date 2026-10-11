package com.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.JsonFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.jetbrains.jsonSchema.impl.JsonOriginalPsiWalker
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import com.jetbrains.jsonSchema.impl.NetworkntValidationBridge
import java.awt.BorderLayout
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal class PipelineJsonFieldDialog(
    private val project: Project,
    private val fieldName: String,
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

    override fun doValidate(): ValidationInfo? = ReadAction.compute<ValidationInfo?, RuntimeException> { validateValue() }

    private fun validateValue(): ValidationInfo? {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
            "PipelineField.json",
            JsonFileType.INSTANCE,
            value,
        )
        if (PsiTreeUtil.hasErrorElements(psiFile)) {
            return ValidationInfo("JSON 语法错误，请修正后再应用。", textArea)
        }
        val schemaError = validateAgainstPipelineSchema()
        if (schemaError != null) {
            return ValidationInfo("Pipeline Schema 校验失败：$schemaError", textArea)
        }
        return null
    }

    private fun validateAgainstPipelineSchema(): String? {
        val projectBasePath = project.basePath ?: return null
        val schemaPath = Paths.get(projectBasePath, "tools", "schema", "pipeline.schema.json")
        val schemaFile = LocalFileSystem.getInstance().findFileByPath(schemaPath.toString()) ?: return null
        val syntheticText = "{\n  \"__MaaPipelineSchemaValidationNode\": {\n    \"$fieldName\": $value\n  }\n}"
        val syntheticFile = PsiFileFactory.getInstance(project).createFileFromText(
            "PipelineSchemaValidation.json",
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
}
