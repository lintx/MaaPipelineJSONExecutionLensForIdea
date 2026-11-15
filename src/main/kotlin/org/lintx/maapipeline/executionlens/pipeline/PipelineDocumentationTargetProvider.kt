package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.openapi.application.ModalityState
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager

class PipelineDocumentationTargetProvider : DocumentationTargetProvider, PsiDocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> =
        target(file.findElementAt(offset))?.let(::listOf).orEmpty()

    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? =
        target(originalElement ?: element)

    private fun target(element: PsiElement?): DocumentationTarget? =
        PipelineJsonDocumentationProvider().previewElement(element)?.let(::PipelineDocumentationTarget)
}

internal class PipelineDocumentationTarget(val element: PsiElement,
    val popupMarker: String = "maapipeline-hover-" + java.util.UUID.randomUUID()) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let { PipelineDocumentationTarget(it, popupMarker) } }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(PipelineJsonContext.at(element)?.node?.name ?: "Pipeline").presentation()

    override fun computeDocumentation(): DocumentationResult? {
        val document = PipelineJsonDocumentationProvider().generateContent(element, element, popupMarker) ?: return null
        val content = DocumentationContent.content(document.html)
        return DocumentationResult.documentation(content)
            .images(document.images)
    }
}

class PipelineImageDocumentationLinkHandler : com.intellij.platform.backend.documentation.DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): com.intellij.platform.backend.documentation.LinkResolveResult? {
        val source = target as? PipelineDocumentationTarget ?: return null
        if (!PipelineHoverImages.open(source.element, url, source.popupMarker)) return null
        return com.intellij.platform.backend.documentation.LinkResolveResult.resolvedTarget(PipelineImageActionTarget(source))
    }
}

private class PipelineImageActionTarget(private val source: PipelineDocumentationTarget) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = source.createPointer()
        return Pointer { (pointer.dereference() as? PipelineDocumentationTarget)?.let(::PipelineImageActionTarget) }
    }

    override fun computePresentation(): TargetPresentation = source.computePresentation()
    override fun computeDocumentation(): DocumentationResult? = null
}
