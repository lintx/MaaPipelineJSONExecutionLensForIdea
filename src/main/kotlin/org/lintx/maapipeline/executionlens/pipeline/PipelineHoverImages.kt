package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import java.awt.image.BufferedImage

internal object PipelineHoverImages {
    fun annotationAt(element: PsiElement, operations: List<PipelineOperation>): PipelinePreviewAnnotation? {
        val context = PipelineJsonContext.at(element) ?: return null
        val owner = context.node.value ?: return null
        val comment = generateSequence(element) { it.parent }.filterIsInstance<com.intellij.psi.PsiComment>().firstOrNull()
        val rawStart = owner.textRange.endOffset - context.location.rawJson.length
        return PipelinePreviewModel.annotations(operations).firstOrNull { annotation ->
            if (annotation.operation.source?.name != context.node.name || annotation.operation.source?.sourceFile != context.file.virtualFile) return@firstOrNull false
            if (comment != null) annotation.operation.roiHints[annotation.key]?.range?.shiftRight(rawStart)?.intersects(comment.textRange) == true
            else annotation.operation.parameterPaths[annotation.key]?.let { path ->
                val field = PipelineOperationEdits.at(owner, path)?.parent as? JsonProperty
                field?.textRange?.contains(element.textRange) == true
            } == true
        }
    }

    internal data class Preview(val file: VirtualFile, val image: BufferedImage, val operations: List<PipelineOperation> = emptyList(),
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
                if (!project.isDisposed) PipelineImagePreview.show(project, preview.file, preview.image, preview.operations,
                    preview.catalog::get, preview.templates)
            }, ModalityState.nonModal())
        }
        return true
    }

    internal fun load(element: PsiElement, key: String): Preview? {
        val context = PipelineJsonContext.at(element) ?: return null
        val project = element.project
        val source = context.file.virtualFile
        val catalog = linkedMapOf<String, PipelineNodeLocation>()
        fun resolve(name: String): PipelineNodeLocation? {
            catalog[name]?.let { return it }
            val file = PipelineNavigationIndex.values(project, "node:$name").singleOrNull()?.first ?: return null
            val psi = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return null
            val property = (psi.topLevelValue as? JsonObject)?.findProperty(name) ?: return null
            return PipelineNodeLocation(name, property.nameElement.textRange, property.textRange, sourceFile = file,
                rawJson = PipelinePreviewHints.sourceText(property)).also { catalog[name] = it }
        }
        if (key == "preview") {
            val node = context.location
            val path = PipelineVisualModel.preview(node.rawJson) ?: return null
            val file = PipelineVisualAssets.resolve(project, source, path, true) ?: return null
            val image = PipelineVisualAssets.read(file) ?: return null
            val operations = PipelineVisualAssets.leaves(PipelineVisualModel.operation(node, resolve = ::resolve)) +
                listOf(PipelineVisualModel.operation(node, true, ::resolve)).filter { it.type != "DoNothing" }
            PipelinePreviewModel.annotations(operations, ::resolve)
            val templates = PipelineVisualAssets.previewTemplates(project, source, operations, ::resolve)
            return Preview(file, image, operations, catalog.toMap(), templates)
        }
        if (key.startsWith("template:")) {
            val url = java.net.URLDecoder.decode(key.removePrefix("template:"), java.nio.charset.StandardCharsets.UTF_8)
            val literals = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(context.node, com.intellij.json.psi.JsonStringLiteral::class.java)
                .filter { literal -> generateSequence(literal as PsiElement) { it.parent }.filterIsInstance<JsonProperty>().any { it.name == "template" } }
            val file = literals.asSequence().flatMap { PipelineVisualAssets.templates(project, source, it.value).asSequence() }
                .firstOrNull { it.url == url } ?: return null
            return PipelineVisualAssets.read(file)?.let { Preview(file, it) }
        }
        if (key == "colors") {
            val owner = generateSequence(element) { it.parent }.filterIsInstance<JsonObject>()
                .firstOrNull { it.findProperty("recognition") != null || it.findProperty("type") != null }
            val operation = PipelineVisualModel.operation(context.location.copy(rawJson = owner?.text ?: context.location.rawJson))
            val colors = PipelineVisualModel.colors(operation) ?: return null
            val image = PipelineVisualAssets.colorImage(colors) ?: return null
            return Preview(source, image)
        }
        return null
    }
}
