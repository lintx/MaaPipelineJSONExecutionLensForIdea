package com.maapipeline.executionlens.pipeline

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

private class PipelineDocumentationTarget(private val element: PsiElement) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let(::PipelineDocumentationTarget) }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(PipelineJsonContext.at(element)?.node?.name ?: "Pipeline").presentation()

    override fun computeDocumentation(): DocumentationResult? {
        val html = PipelineJsonDocumentationProvider().generateDoc(element, element) ?: return null
        // 新版原生悬浮窗口直接接收图片映射，不依赖被组合提供器丢失的旧式图片回调。
        val content = DocumentationContent.content(html, PipelineJsonDocumentationProvider.imagesFor(html))
        return DocumentationResult.documentation(content)
    }
}
