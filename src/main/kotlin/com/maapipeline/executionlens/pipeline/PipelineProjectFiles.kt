package com.maapipeline.executionlens.pipeline

import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

internal object PipelineProjectFiles {
    fun base(project: Project): VirtualFile? = project.basePath?.let {
        LocalFileSystem.getInstance().findFileByPath(it)
    }

    fun manifest(project: Project, file: VirtualFile? = null): VirtualFile? {
        val ancestor = file?.let { generateSequence(it.parent) { parent -> parent.parent }
            .mapNotNull { parent -> parent.findChild("interface.json") }.firstOrNull() }
        val root = base(project)
        return ancestor ?: root?.findFileByRelativePath("assets/interface.json") ?: root?.findChild("interface.json")
    }

    fun pipelineRoots(project: Project, file: VirtualFile? = null): List<VirtualFile> {
        val manifest = manifest(project, file)
        val roots = mutableListOf<VirtualFile>()
        if (manifest != null) {
            val root = json(manifest)
            // 不混入不同控制器的附加资源，默认使用 manifest 的第一个资源域。
            val resource = root?.get("resource")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            val paths = resource?.get("path")
            val values = if (paths?.isJsonArray == true) paths.asJsonArray.toList() else listOfNotNull(paths)
            values.filter { it.isJsonPrimitive }.forEach { path ->
                manifest.parent.findFileByRelativePath(path.asString.removePrefix("./") + "/pipeline")?.let(roots::add)
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

    fun pipelineFiles(project: Project, file: VirtualFile? = null): List<VirtualFile> =
        pipelineRoots(project, file).flatMap(::jsonFiles).distinct().ifEmpty { listOfNotNull(file) }

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
        JsonParser.parseString(text(file)).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()
}
