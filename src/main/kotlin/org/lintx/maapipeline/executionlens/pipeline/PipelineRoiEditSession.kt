package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonParser
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Rectangle

internal class PipelineRoiEditSession(private val project: Project, operations: List<PipelineOperation>,
    private val blocked: (VirtualFile) -> Boolean = { false }) {
    private data class Source(val document: Document, var snapshot: PipelineEditSnapshot)
    private val sources = ReadAction.compute<Map<VirtualFile, Source>, RuntimeException> {
        operations.flatMap { operation -> listOfNotNull(operation.source?.sourceFile) +
            operation.roiHints.values.mapNotNull { it.sourceFile } +
            operation.source?.let(PipelinePreviewHints::sources).orEmpty().mapNotNull { it.sourceFile } }.distinct().mapNotNull { file ->
            FileDocumentManager.getInstance().getDocument(file)?.let { file to Source(it, PipelineEditSnapshot(it.modificationStamp, it.text)) }
        }.toMap()
    }

    fun unavailable(annotation: PipelinePreviewAnnotation, offset: Boolean = false): String? = ReadAction.compute<String?, RuntimeException> {
        val node = annotation.operation.source ?: return@compute "没有可写入的节点定义"
        val file = sourceFile(annotation, offset) ?: return@compute "没有可写入的源文件"
        val source = sources[file] ?: return@compute "无法读取源文件"
        val definition = annotation.operation.copy(simulatedRegions = annotation.operation.simulatedRegions - annotation.key)
        if (!offset && PipelineVisualModel.region(definition, annotation.key).coordinates == null)
            return@compute "运行时目标；请调整其来源条件的 ROI"
        if (!file.isWritable || blocked(file) || generated(source.snapshot.text)) return@compute "源文件只读或由生成器维护"
        val owner = PipelineOperationEdits.objectValue(project, source.snapshot.text)?.findProperty(node.name)?.value
            ?: return@compute "无法定位节点定义"
        if (!editingComment(annotation, offset) &&
            runCatching { PipelineJsonc.parse(owner.text) != PipelineJsonc.parse(node.rawJson) }.getOrDefault(true))
            return@compute "当前值包含任务覆盖，请在对应的原始 JSON 中调整 ROI"
        null
    }

    fun apply(annotation: PipelinePreviewAnnotation, rectangle: Rectangle): Boolean = apply(annotation,
        listOf(rectangle.x, rectangle.y, rectangle.width, rectangle.height))

    fun apply(annotation: PipelinePreviewAnnotation, values: List<Int>): Boolean = applyEdit(annotation, annotation.key) { owner ->
        PipelineOperationEdits.roiValuesEdit(owner, annotation.operation.copy(simulatedRegions = annotation.operation.simulatedRegions - annotation.key), annotation.key, values)
    }

    fun applyOffset(annotation: PipelinePreviewAnnotation, values: List<Int>): Boolean = applyEdit(annotation, "${annotation.key}_offset") { owner ->
        PipelineOperationEdits.offsetEdit(owner, annotation.operation, annotation.key, values)
    }

    private fun applyEdit(annotation: PipelinePreviewAnnotation, field: String,
        createEdit: (com.intellij.json.psi.JsonObject) -> PipelineTextEdit?): Boolean {
        unavailable(annotation, offset = field.endsWith("_offset"))?.let { Messages.showWarningDialog(project, it, "无法修改 ROI"); return false }
        val node = annotation.operation.source ?: return false
        val file = sourceFile(annotation, field.endsWith("_offset")) ?: return false
        val source = sources.getValue(file)
        val edit = ReadAction.compute<PipelineTextEdit?, RuntimeException> {
            val owner = PipelineOperationEdits.objectValue(project, source.snapshot.text)?.findProperty(node.name)?.value
                as? com.intellij.json.psi.JsonObject ?: return@compute null
            createEdit(owner)
        } ?: return false
        var applied = false
        WriteCommandAction.runWriteCommandAction(project, "调整 Pipeline $field", null, Runnable {
            // 弹窗期间源码可能被其他编辑器修改；版本和文本都相同才允许应用旧坐标。
            if (file.isWritable && !blocked(file) && source.snapshot.matches(source.document.modificationStamp, source.document.text)) {
                source.document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
                source.snapshot = PipelineEditSnapshot(source.document.modificationStamp, source.document.text)
                applied = true
            }
        })
        if (!applied) Messages.showWarningDialog(project, "源文件已修改，未覆盖任何内容。请重新打开参考图后调整。", "ROI 已过期")
        return applied
    }

    fun currentOperation(operation: PipelineOperation): PipelineOperation = ReadAction.compute<PipelineOperation, RuntimeException> {
        val source = operation.source ?: return@compute operation
        val text = sources[source.sourceFile]?.snapshot?.text ?: return@compute operation
        val property = PipelineOperationEdits.objectValue(project, text)?.findProperty(source.name) ?: return@compute operation
        val layered = source.previewSources.isNotEmpty()
        if (!layered && PipelineJsonc.parse(property.value?.text ?: "{}") != PipelineJsonc.parse(source.rawJson)) return@compute operation
        val updatedSources = if (!layered) emptyList() else source.previewSources.map { layer ->
            val currentText = sources[layer.sourceFile]?.snapshot?.text
            val currentProperty = currentText?.let { PipelineOperationEdits.objectValue(project, it)?.findProperty(source.name) }
            if (currentProperty == null) layer else layer.copy(rawJson = PipelinePreviewHints.sourceText(currentProperty))
        }
        val originalBase = PipelinePreviewHints.sources(source).firstOrNull { it.sourceFile == source.sourceFile }?.rawJson
        val followsBase = originalBase != null && PipelineJsonc.parse(originalBase) == PipelineJsonc.parse(source.rawJson)
        val root = PipelineVisualModel.operation(source.copy(
            rawJson = if (layered && !followsBase) source.rawJson else PipelinePreviewHints.sourceText(property),
            previewSource = null, previewSources = updatedSources), operation.isAction)
        fun find(candidate: PipelineOperation): PipelineOperation? =
            if (candidate.ownerPath == operation.ownerPath) candidate else candidate.children.firstNotNullOfOrNull(::find)
        (find(root) ?: operation).copy(reference = operation.reference)
    }

    private fun editingComment(annotation: PipelinePreviewAnnotation, offset: Boolean): Boolean =
        !offset && annotation.region.fromComment

    private fun sourceFile(annotation: PipelinePreviewAnnotation, offset: Boolean): VirtualFile? =
        if (editingComment(annotation, offset)) annotation.operation.roiHints[annotation.key]?.sourceFile ?: annotation.operation.source?.sourceFile
        else annotation.operation.source?.sourceFile

    private fun generated(text: String): Boolean = text.lineSequence().take(40).map(String::trim)
        .filter { it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") }
        .any { Regex("(?i)generated|autogenerated|do\\s+not\\s+edit|由[^\\r\\n]*生成").containsMatchIn(it) }
}
