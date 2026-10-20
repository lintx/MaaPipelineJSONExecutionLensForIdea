package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
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

internal class PipelineDocumentationTarget(val element: PsiElement) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let(::PipelineDocumentationTarget) }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(PipelineJsonContext.at(element)?.node?.name ?: "Pipeline").presentation()

    override fun computeDocumentation(): DocumentationResult? {
        val html = PipelineJsonDocumentationProvider().generateDoc(element, element) ?: return null
        // 原生 AdaptiveImageView 可直接读取内嵌 PNG，旧式与新版悬浮入口共享同一份内容。
        val content = DocumentationContent.content(html)
        return DocumentationResult.documentation(content)
    }
}

class PipelineImageDocumentationLinkHandler : com.intellij.platform.backend.documentation.DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): com.intellij.platform.backend.documentation.LinkResolveResult? {
        val source = (target as? PipelineDocumentationTarget)?.element ?: return null
        if (!PipelineHoverImages.open(source, url)) return null
        return com.intellij.platform.backend.documentation.LinkResolveResult.resolvedTarget(target)
    }
}
