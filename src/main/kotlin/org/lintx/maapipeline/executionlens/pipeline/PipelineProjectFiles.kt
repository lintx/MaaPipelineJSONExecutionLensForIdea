package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

internal object PipelineProjectFiles {
    data class ResourceLayer(
        val path: String,
        val label: String,
        val isBase: Boolean,
    )

    data class ResourceSource(
        val layer: ResourceLayer,
        val file: VirtualFile?,
        val manifestDirectory: VirtualFile,
        val targetRelativePath: String,
    )

    fun base(project: Project): VirtualFile? = project.basePath?.let {
        LocalFileSystem.getInstance().findFileByPath(it)
    }

    fun manifest(project: Project, file: VirtualFile? = null): VirtualFile? {
        val ancestor = file?.let { generateSequence(it.parent) { parent -> parent.parent }
            .mapNotNull { parent -> parent.findChild("interface.json") }.firstOrNull() }
        val root = base(project)
        return ancestor ?: root?.findFileByRelativePath("assets/interface.json") ?: root?.findChild("interface.json")
    }

    fun resourcePaths(text: String, environmentId: String = ""): List<String> {
        return resourceLayers(text, environmentId).map { it.path }
    }

    fun resourceLayers(text: String, environmentId: String = ""): List<ResourceLayer> {
        val root = PipelineVisualModel.parse(text) ?: return emptyList()
        fun paths(value: com.google.gson.JsonElement?): List<String> =
            (if (value?.isJsonArray == true) value.asJsonArray.toList() else listOfNotNull(value))
                .filter { it.isJsonPrimitive }.map { it.asString.removePrefix("./") }
        val resource = root.get("resource")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val controllers = root.get("controller")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
        val controller = controllers.firstOrNull { environmentId.isNotEmpty() && it.get("name")?.asString == environmentId }
        val base = paths(resource?.get("path"))
        val attached = paths(controller?.get("attach_resource_path"))
        return (base.map { ResourceLayer(it, "基础资源 · ${it.substringAfterLast('/')}", true) } +
            attached.map { ResourceLayer(it, it.substringAfterLast('/'), false) })
            .distinctBy { it.path }
    }

    fun resourceLayerFiles(
        manifestDirectory: VirtualFile?,
        source: VirtualFile,
        layers: List<ResourceLayer>,
    ): List<ResourceSource> {
        val resourceRoot = manifestDirectory ?: return emptyList()
        val relative = layers.asSequence().mapNotNull { layer ->
            resourcePipelinePath(layer)?.let(resourceRoot::findFileByRelativePath)
        }.firstOrNull { pipeline -> source.path.startsWith(pipeline.path.trimEnd('/') + "/") }
            ?.let { pipeline -> source.path.removePrefix(pipeline.path.trimEnd('/') + "/") }
            ?: source.name
        return layers.mapNotNull { layer ->
            val pipelinePath = resourcePipelinePath(layer) ?: return@mapNotNull null
            val target = "$pipelinePath/$relative"
            val file = resourceRoot.findFileByRelativePath(target)
            ResourceSource(layer, file, resourceRoot, target)
        }
    }

    private fun resourcePipelinePath(layer: ResourceLayer): String? {
        val segments = layer.path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." || it.contains(':') }) return null
        return (segments + "pipeline").joinToString("/")
    }

    fun pipelineRoots(project: Project, file: VirtualFile? = null, environmentId: String = ""): List<VirtualFile> {
        val manifest = manifest(project, file)
        val roots = mutableListOf<VirtualFile>()
        if (manifest != null) {
            resourceLayers(text(manifest), environmentId).forEach { layer ->
                val path = layer.path
                manifest.parent.findFileByRelativePath("$path/pipeline")?.let(roots::add)
            }
        }
        val containing = file?.let { generateSequence(it.parent) { parent -> parent.parent }
            .firstOrNull { parent -> parent.name.equals("pipeline", true) } }
        if (roots.isEmpty()) {
            val base = base(project)
            listOfNotNull(containing, base?.findFileByRelativePath("assets/resource/pipeline"),
                base?.findFileByRelativePath("resource/pipeline"), base?.findChild("pipeline"))
                .forEach(roots::add)
        }
        if (containing != null && roots.none { containing.path.startsWith(it.path) }) return listOf(containing)
        return roots.distinct()
    }

    fun pipelineFiles(project: Project, file: VirtualFile? = null, environmentId: String = ""): List<VirtualFile> =
        pipelineRoots(project, file, environmentId).flatMap { jsonFiles(it).sortedBy(VirtualFile::getPath) }
            .distinct().ifEmpty { listOfNotNull(file) }

    fun taskFiles(project: Project, file: VirtualFile): List<VirtualFile> {
        val manifest = manifest(project, file)
        if (manifest != null) {
            val imports = json(manifest)?.get("import")?.takeIf { it.isJsonArray }?.asJsonArray
            val result = linkedSetOf<VirtualFile>(manifest)
            fun visit(current: VirtualFile) {
                ProgressManager.checkCanceled()
                val paths = json(current)?.get("import")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
                paths.filter { it.isJsonPrimitive }.forEach { path ->
                    val imported = current.parent.findFileByRelativePath(path.asString.removePrefix("./"))
                    if (imported != null && result.add(imported)) visit(imported)
                }
            }
            if (imports != null) { visit(manifest); return result.toList() }
        }
        val ancestors = generateSequence(file.parent) { it.parent }
        val taskRoot = ancestors.mapNotNull { it.findChild("tasks") ?: it.findChild("task") }.firstOrNull()
        return taskRoot?.let(::jsonFiles).orEmpty()
    }

    fun translations(project: Project, file: VirtualFile): Map<String, String> {
        val manifest = manifest(project, file) ?: return emptyMap()
        val languages = json(manifest)?.get("languages")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        val language = if (java.util.Locale.getDefault().language == "zh") "zh_cn" else "en_us"
        val paths = (languages.keySet().filter { it != language } + language).distinct()
        return buildMap {
            paths.forEach { key ->
                val path = languages.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                val locale = manifest.parent.findFileByRelativePath(path) ?: return@forEach
                json(locale)?.entrySet()?.forEach { (name, value) ->
                    if (value.isJsonPrimitive) {
                        val translationKey = "$$name"
                        put(translationKey, value.asString)
                        put("$translationKey#search", listOfNotNull(get("$translationKey#search"), value.asString).joinToString(" "))
                    }
                }
            }
        }
    }

    fun translationFiles(project: Project, file: VirtualFile): List<VirtualFile> {
        val manifest = manifest(project, file) ?: return emptyList()
        val languages = json(manifest)?.get("languages")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        return languages.entrySet().mapNotNull { (_, path) ->
            path.takeIf { it.isJsonPrimitive }?.asString?.let { manifest.parent.findFileByRelativePath(it) }
        }.distinct()
    }

    fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)?.text
        ?: String(file.contentsToByteArray(), Charsets.UTF_8)

    fun jsonFiles(root: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        fun visit(file: VirtualFile) {
            ProgressManager.checkCanceled()
            if (file.isDirectory) file.children.forEach(::visit)
            else if (file.extension?.lowercase() in setOf("json", "jsonc")) result += file
        }
        visit(root)
        return result
    }

    private fun json(file: VirtualFile) = runCatching {
        PipelineJsonc.parse(text(file)).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()
}
