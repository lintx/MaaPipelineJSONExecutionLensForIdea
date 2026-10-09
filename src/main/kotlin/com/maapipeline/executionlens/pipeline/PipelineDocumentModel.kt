package com.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

data class PipelineNodeLocation(
    val name: String,
    val nameRange: TextRange,
    val propertyRange: TextRange,
)

data class PipelineDocumentModel(
    val file: VirtualFile,
    val version: Long,
    val nodes: List<PipelineNodeLocation>,
    val hasSyntaxErrors: Boolean,
)

internal object PipelineDocumentIndexer {
    fun index(project: Project, file: VirtualFile): PipelineDocumentModel {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val psiFile = PsiManager.getInstance(project).findFile(file)
        val jsonFile = psiFile as? JsonFile
        val rootObject = jsonFile?.topLevelValue as? JsonObject
        val nodes = rootObject?.propertyList.orEmpty().mapNotNull { property ->
            val nameRange = property.nameElement.textRange ?: return@mapNotNull null
            val propertyRange = property.textRange ?: nameRange
            PipelineNodeLocation(property.name, nameRange, propertyRange)
        }
        return PipelineDocumentModel(
            file = file,
            version = document?.modificationStamp ?: -1L,
            nodes = nodes,
            hasSyntaxErrors = jsonFile == null || rootObject == null,
        )
    }
}
