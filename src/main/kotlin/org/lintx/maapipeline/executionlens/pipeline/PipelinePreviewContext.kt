package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.psi.PsiManager

internal class PipelinePreviewContext(private val context: PipelineJsonContext) {
    private val project = context.file.project
    val environment = PipelineEnvironment.selected(project, context.file.virtualFile)
    private val roots = PipelineProjectFiles.pipelineRoots(project, context.file.virtualFile, environment.id)
    val catalog = linkedMapOf<String, PipelineNodeLocation>()
    val location: PipelineNodeLocation get() = resolve(context.node.name) ?: context.location.copy(
        environmentId = environment.id, controllerType = environment.controller)

    fun resolve(name: String): PipelineNodeLocation? {
        catalog[name]?.let { return it }
        val files = (PipelineNavigationIndex.values(project, "node:$name").map { it.first } +
            listOfNotNull(context.file.virtualFile.takeIf { name == context.node.name })).distinct()
        val layers = files.mapNotNull { file ->
            roots.indexOfFirst { file.path.startsWith(it.path + "/") }.takeIf { it >= 0 }?.let { it to file }
        }.groupBy({ it.first }, { it.second })
        // 同一层的重复定义不是资源覆盖，不能为悬浮预览随意选择一个。
        if (layers.values.any { it.size > 1 }) return null
        val definitions = layers.toSortedMap().values.flatten().mapNotNull { file ->
            val psi = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return@mapNotNull null
            val property = (psi.topLevelValue as? JsonObject)?.findProperty(name) ?: return@mapNotNull null
            PipelineNodeLocation(name, property.nameElement.textRange, property.textRange,
                sourceFile = file, rawJson = PipelinePreviewHints.sourceText(property),
                environmentId = environment.id, controllerType = environment.controller)
        }
        val base = definitions.firstOrNull() ?: return null
        var json = PipelineJsonc.parse(base.rawJson)
        definitions.drop(1).forEach { json = PipelineTaskContext.merge(json, PipelineJsonc.parse(it.rawJson)) }
        return base.copy(rawJson = if (definitions.size == 1) base.rawJson else json.toString(),
            previewSources = definitions.map { PipelinePreviewSource(it.rawJson, it.sourceFile) }).also { catalog[name] = it }
    }
}
