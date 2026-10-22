package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory

internal object PipelineTemporaryOverrides {
    fun validate(text: String): String? {
        val root = PipelineVisualModel.parse(text) ?: return "请输入 JSON 对象，例如 {\"节点名\": {\"enabled\": true}}"
        return if (root.entrySet().any { !it.value.isJsonObject }) "每个节点的覆盖必须是 JSON 对象" else null
    }

    fun parse(project: Project, text: String): List<PipelineOverrideLocation> {
        if (validate(text) != null) return emptyList()
        val file = com.intellij.testFramework.LightVirtualFile("Pipeline 临时覆盖.json", JsonFileType.INSTANCE, text)
        file.isWritable = false
        val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return emptyList()
        val root = psi.topLevelValue as? JsonObject ?: return emptyList()
        return root.propertyList.mapNotNull { property ->
            val value = property.value as? JsonObject ?: return@mapNotNull null
            PipelineOverrideLocation(psi.virtualFile, property.name, property.textRange,
                value.propertyList.mapNotNull { field -> field.value?.let {
                    PipelineFieldLocation(field.name, it.text, field.textRange, it.textRange,
                        sourceFile = psi.virtualFile, isDerived = true)
                } }, valueRange = value.textRange, contextPath = "临时覆盖", rawJson = value.text)
        }
    }
}
