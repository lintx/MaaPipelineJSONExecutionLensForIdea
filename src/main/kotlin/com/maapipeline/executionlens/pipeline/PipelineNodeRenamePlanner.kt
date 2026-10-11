package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

internal data class PipelineNodeRenameFilePlan(
    val file: VirtualFile,
    val originalText: String,
    val edits: List<PipelineTextEdit>,
)

internal data class PipelineNodeRenamePlan(
    val oldName: String,
    val newName: String,
    val files: List<PipelineNodeRenameFilePlan>,
    val conflicts: List<String>,
) {
    val editCount: Int get() = files.sumOf { it.edits.size }

    val affectedFileCount: Int get() = files.size
}

internal object PipelineNodeRenamePlanner {
    fun build(
        model: PipelineDocumentModel,
        selectedNode: PipelineNodeLocation,
        newName: String,
        textByFile: Map<VirtualFile, String>,
    ): PipelineNodeRenamePlan {
        val oldName = selectedNode.name
        val editsByFile = linkedMapOf<VirtualFile, MutableList<PipelineTextEdit>>()
        val rangesByFile = mutableMapOf<VirtualFile, MutableMap<Pair<Int, Int>, String>>()
        val conflicts = mutableListOf<String>()

        fun addEdit(sourceFile: VirtualFile, range: TextRange, replacement: String, description: String) {
            val text = textByFile[sourceFile]
            if (text == null || range.startOffset < 0 || range.endOffset > text.length) {
                conflicts += "$description：无法定位源码范围"
                return
            }
            val key = range.startOffset to range.endOffset
            val existing = rangesByFile.getOrPut(sourceFile) { mutableMapOf() }[key]
            if (existing != null) {
                if (existing != replacement) conflicts += "$description：同一范围存在冲突写入"
                return
            }
            val original = text.substring(range.startOffset, range.endOffset)
            val edit = PipelineJsonTextEdits.replacementEdit(range, original, replacement)
            rangesByFile.getOrPut(sourceFile) { mutableMapOf() }[key] = replacement
            editsByFile.getOrPut(sourceFile) { mutableListOf() } += edit
        }

        val selectedFile = selectedNode.sourceFile ?: model.file
        addEdit(selectedFile, selectedNode.nameRange, quoteJsonString(newName), "节点定义 ${selectedNode.name}")

        model.projectNodes.forEach { sourceNode ->
            val sourceFile = sourceNode.sourceFile ?: model.file
            (sourceNode.next + sourceNode.onError + sourceNode.embeddedReferences)
                .filter { it.targetName == oldName }
                .forEach { reference ->
                    val replacement = referenceReplacement(reference, newName)
                    if (replacement != null) {
                        addEdit(sourceFile, reference.range, replacement, "节点引用 ${sourceNode.name}")
                    } else {
                        conflicts += "节点引用 ${sourceNode.name}：无法保留原始标记"
                    }
                }
            sourceNode.anchorDefinitions
                .filter { it.targetNodeName == oldName }
                .forEach { definition ->
                    val targetRange = definition.targetRange
                    if (targetRange == null) {
                        conflicts += "Anchor ${definition.name}：缺少目标值范围"
                    } else {
                        addEdit(sourceFile, targetRange, quoteJsonString(newName), "Anchor ${definition.name}")
                    }
                }
        }

        model.pipelineOverrides.forEach { override ->
            if (override.targetName == oldName) {
                addEdit(override.file, override.range, quoteJsonString(newName), "任务覆盖 ${override.targetName}")
            }
            override.fields.flatMap { it.references }
                .filter { it.targetName == oldName }
                .forEach { reference ->
                    val replacement = referenceReplacement(reference, newName)
                    if (replacement != null) {
                        addEdit(override.file, reference.range, replacement, "任务覆盖中的节点引用 ${override.targetName}")
                    } else {
                        conflicts += "任务覆盖 ${override.targetName}：无法保留原始标记"
                    }
                }
        }

        model.pipelineEntries
            .filter { it.entryName == oldName }
            .forEach { entry ->
                addEdit(entry.file, entry.range, quoteJsonString(newName), "任务入口 ${entry.entryName}")
            }

        val files = editsByFile.mapNotNull { (sourceFile, edits) ->
            textByFile[sourceFile]?.let { text ->
                PipelineNodeRenameFilePlan(sourceFile, text, edits.sortedBy { it.range.startOffset })
            }
        }
        return PipelineNodeRenamePlan(oldName, newName, files, conflicts.distinct())
    }

    private fun referenceReplacement(reference: PipelineReference, newName: String): String? {
        if (!reference.displayName.endsWith(reference.targetName)) return null
        val prefix = reference.displayName.removeSuffix(reference.targetName)
        return quoteJsonString(prefix + newName)
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
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> append(character)
            }
        }
        append('"')
    }
}
