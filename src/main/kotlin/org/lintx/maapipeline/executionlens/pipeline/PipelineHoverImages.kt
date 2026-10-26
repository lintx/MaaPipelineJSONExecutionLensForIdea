package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import java.awt.image.BufferedImage

internal object PipelineHoverImages {
    internal fun colorKey(operation: PipelineOperation): String {
        val identity = listOf(operation.source?.sourceFile?.url.orEmpty(), operation.source?.name.orEmpty(),
            operation.ownerPath.joinToString("/")).joinToString("\n")
        return "colors:" + java.net.URLEncoder.encode(identity, java.nio.charset.StandardCharsets.UTF_8)
    }

    fun annotationAt(element: PsiElement, operations: List<PipelineOperation>): PipelinePreviewAnnotation? {
        val context = PipelineJsonContext.at(element) ?: return null
        val owner = context.node.value ?: return null
        val comment = generateSequence(element) { it.parent }.filterIsInstance<com.intellij.psi.PsiComment>().firstOrNull()
        val rawStart = owner.textRange.endOffset - context.location.rawJson.length
        return PipelinePreviewModel.annotations(operations).firstOrNull { annotation ->
            val source = annotation.operation.source ?: return@firstOrNull false
            if (source.name != context.node.name) return@firstOrNull false
            if (comment != null) annotation.operation.roiHints[annotation.key]?.let { hint ->
                (hint.sourceFile ?: source.sourceFile) == context.file.virtualFile &&
                    hint.range.shiftRight(rawStart).intersects(comment.textRange)
            } == true
            else source.sourceFile == context.file.virtualFile && annotation.operation.parameterPaths[annotation.key]?.let { path ->
                val field = PipelineOperationEdits.at(owner, path)?.parent as? JsonProperty
                field?.textRange?.contains(element.textRange) == true
            } == true
        }
    }

    internal data class Preview(val file: VirtualFile, val image: BufferedImage, val node: PipelineNodeLocation,
        val operations: List<PipelineOperation> = emptyList(),
        val catalog: Map<String, PipelineNodeLocation> = emptyMap(),
        val templates: Map<Int, List<PipelineVisualAssets.TemplatePreview>> = emptyMap())

    fun open(element: PsiElement, link: String): Boolean {
        val key = link.removePrefix("psi_element://").takeIf { it.startsWith("maa-image:") }?.removePrefix("maa-image:") ?: return false
        val pointer = SmartPointerManager.createPointer(element)
        val project = element.project
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val preview = ReadAction.compute<Preview?, RuntimeException> {
                if (project.isDisposed) null else pointer.element?.let { load(it, key) }
            } ?: return@executeOnPooledThread
            application.invokeLater({
                if (!project.isDisposed) PipelineImagePreview.show(project, preview.node, preview.image, preview.operations,
                    preview.catalog::get, preview.templates)
            }, ModalityState.nonModal())
        }
        return true
    }

    internal fun load(element: PsiElement, key: String): Preview? {
        val context = PipelineJsonContext.at(element) ?: return null
        val project = element.project
        val source = context.file.virtualFile
        val previewContext = PipelinePreviewContext(context)
        fun resolve(name: String) = previewContext.resolve(name)
        val environmentId = previewContext.environment.id
        val node = previewContext.location
        if (PipelineVisualModel.parse(node.rawJson) == null) return null
        val scope = PipelineJsonOperationContext.at(element)
        val selected = scope?.operation(element, context, node, ::resolve)
        if (key == "preview") {
            val hint = PipelinePreviewHints.preview(node)
            val background = if (hint == null) PipelinePreviewSettings.getInstance().background(source)
                else PipelineVisualAssets.resolve(project, hint.sourceFile ?: source, hint.path, true, environmentId)
                    ?.let { file -> PipelineVisualAssets.read(file)?.let { file to it } }
            val (file, image) = background ?: return null
            val fullPreview = generateSequence(element) { it.parent }.filterIsInstance<com.intellij.psi.PsiComment>().any { it.text.contains("@preview") }
            val operations = if (!fullPreview && selected != null) PipelineVisualAssets.leaves(selected)
                else PipelineVisualAssets.leaves(PipelineVisualModel.operation(node, resolve = ::resolve)) +
                    listOf(PipelineVisualModel.operation(node, true, ::resolve)).filter { it.type != "DoNothing" }
            PipelinePreviewModel.annotations(operations, ::resolve)
            val templates = PipelineVisualAssets.previewTemplates(project, source, operations, ::resolve, environmentId)
            return Preview(file, image, node, operations, previewContext.catalog.toMap(), templates)
        }
        if (key.startsWith("template:")) {
            val url = java.net.URLDecoder.decode(key.removePrefix("template:"), java.nio.charset.StandardCharsets.UTF_8)
            val operation = selected ?: PipelineVisualModel.operation(node, resolve = ::resolve)
            val file = PipelineVisualAssets.leaves(operation).asSequence().flatMap { child ->
                val templates = child.parameters["template"]
                val paths = if (templates?.isJsonArray == true) templates.asJsonArray.toList() else listOfNotNull(templates)
                paths.filter { it.isJsonPrimitive }.flatMap { path ->
                    PipelineVisualAssets.templates(project, child.source?.sourceFile ?: source, path.asString, environmentId)
                }.asSequence()
            }
                .firstOrNull { it.url == url } ?: return null
            return PipelineVisualAssets.read(file)?.let { Preview(file, it, previewContext.location) }
        }
        if (key == "colors" || key.startsWith("colors:")) {
            val operation = if (key == "colors") selected else selected?.let(PipelineVisualAssets::leaves)?.firstOrNull { colorKey(it) == key }
            operation ?: return null
            val colors = PipelineVisualModel.colors(operation) ?: return null
            val image = PipelineVisualAssets.colorImage(colors) ?: return null
            return Preview(source, image, previewContext.location)
        }
        return null
    }
}
