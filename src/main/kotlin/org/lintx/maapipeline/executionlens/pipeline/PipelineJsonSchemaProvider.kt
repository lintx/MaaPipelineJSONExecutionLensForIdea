package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.psi.PsiManager
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion

class PipelineJsonSchemaProvider : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(object : JsonSchemaFileProvider {
        private var snapshotSource: VirtualFile? = null
        private var snapshot: PipelineProjectSchemaBundle? = null
        override fun getName() = "MaaPipeline project schema"
        override fun getSchemaType() = SchemaType.schema
        override fun getSchemaVersion() = JsonSchemaVersion.SCHEMA_2020_12
        override fun getSchemaFile(): VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
            val roots = listOfNotNull(project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }) +
                ProjectRootManager.getInstance(project).contentRoots
            val source = roots.distinct().firstNotNullOfOrNull { root ->
                listOf("tools/schema/pipeline.schema.json", "tools/pipeline.schema.json", "pipeline.schema.json")
                    .firstNotNullOfOrNull { root.findFileByRelativePath(it) }
            } ?: return@compute null
            synchronized(this) {
                if (snapshotSource == source && snapshot?.isCurrent() == true) return@compute snapshot?.file
                val updated = PipelineProjectSchemaBundle.build(source) ?: return@compute null
                snapshotSource = source
                snapshot = updated
                updated.file
            }
        }
        override fun isAvailable(file: VirtualFile): Boolean {
            if (file.extension?.lowercase() !in setOf("json", "jsonc") || file.name.endsWith(".schema.json") || getSchemaFile() == null) return false
            return ReadAction.compute<Boolean, RuntimeException> {
                if (PipelineFileDetector.isPipelineFile(file, PipelineProjectFiles.text(file))) true
                else {
                    // 补全时 JSON 常处于半成品状态，不能因尚未输入冒号而撤掉已能从 PSI 辨认的节点 schema。
                    val psi = PsiManager.getInstance(project).findFile(file) as? JsonFile
                    val root = psi?.topLevelValue as? JsonObject
                    root?.propertyList?.any { property ->
                        (property.value as? JsonObject)?.propertyList?.any {
                            it.name in setOf("recognition", "action", "next", "on_error", "anchor")
                        } == true
                    } == true
                }
            }
        }
    })
}
