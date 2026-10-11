package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

internal data class PipelineAnchorUse(
    val file: VirtualFile,
    val range: TextRange,
    val ownerNodeName: String,
    val kind: String,
)

internal data class PipelineAnchorManagerRow(
    val name: String,
    val ownerNodeName: String,
    val definitionText: String,
    val targetText: String,
    val references: List<PipelineAnchorUse>,
    val warningText: String? = null,
    val definitionFile: VirtualFile? = null,
    val definitionRange: TextRange? = null,
    val propertyRange: TextRange? = null,
    val valueRange: TextRange? = null,
    val fieldRange: TextRange? = null,
    val isScalar: Boolean = false,
    val targetFile: VirtualFile? = null,
    val targetRange: TextRange? = null,
    val canEdit: Boolean = false,
    val runtimeText: String = "未加载可关联的 Anchor 运行日志",
)

internal object PipelineAnchorOverview {
    fun build(model: PipelineDocumentModel): List<PipelineAnchorManagerRow> {
        val nodes = model.projectNodes.ifEmpty { model.nodes }
        val usesByName = nodes.flatMap { node ->
            (node.next + node.onError + node.embeddedReferences).filter { it.isAnchor }.map { reference ->
                reference.targetName to PipelineAnchorUse(
                    reference.sourceFile ?: node.sourceFile ?: model.file,
                    reference.range,
                    node.name,
                    reference.propertyPath ?: reference.kind.label,
                )
            }
        }.groupBy({ it.first }, { it.second })
        val definitions = nodes.flatMap { node -> node.anchorDefinitions.map { node to it } }
        val counts = definitions.groupingBy { it.second.name }.eachCount()
        val rows = definitions.map { (node, definition) ->
            val sourceFile = node.sourceFile ?: model.file
            val targetName = definition.targetNodeName ?: node.name
            val targets = nodes.filter { it.name == targetName }
            val target = targets.singleOrNull().takeUnless { definition.isCleared }
            val warnings = buildList {
                if ((counts[definition.name] ?: 0) > 1) add("多个设置点，运行时最后设置生效")
                if (!definition.isCleared && targets.isEmpty()) add("映射目标不存在")
                if (!definition.isCleared && targets.size > 1) add("映射目标名称不唯一")
                if (sourceFile in model.generatedFiles) add("生成文件，只读")
            }
            PipelineAnchorManagerRow(
                name = definition.name,
                ownerNodeName = node.name,
                definitionText = node.name,
                targetText = if (definition.isCleared) "清除绑定" else "绑定到 $targetName",
                references = usesByName[definition.name].orEmpty(),
                warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("；"),
                definitionFile = sourceFile,
                definitionRange = definition.range,
                propertyRange = definition.propertyRange,
                valueRange = node.anchor?.valueRange,
                fieldRange = node.anchor?.range,
                isScalar = definition.isScalar,
                targetFile = target?.sourceFile ?: model.file.takeIf { target != null },
                targetRange = target?.nameRange,
                canEdit = sourceFile.isWritable && sourceFile !in model.generatedFiles,
            )
        }
        val undefined = usesByName.filterKeys { it !in counts }.map { (name, references) ->
            PipelineAnchorManagerRow(
                name = name,
                ownerNodeName = "",
                definitionText = "未定义",
                targetText = "静态索引中没有设置点",
                references = references,
                warningText = "可能由任务覆盖或运行时设置；请核对运行日志",
            )
        }
        return (rows + undefined).sortedWith(compareBy({ it.name }, { it.definitionFile?.path }, { it.definitionRange?.startOffset }))
    }
}
