package com.maapipeline.executionlens.pipeline

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

class PipelineReferenceInspection : LocalInspectionTool() {
    override fun runForWholeFile(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is JsonFile) return
                val virtualFile = file.virtualFile ?: return
                if (!PipelineFileDetector.isPipelineFile(virtualFile, file.text)) return
                PipelineDocumentIndexer.diagnostics(file.project, virtualFile).forEach { diagnostic ->
                    ProgressManager.checkCanceled()
                    val range = diagnostic.range
                    if (range.isEmpty || range.endOffset > file.textLength) return@forEach
                    val element = generateSequence(file.findElementAt(range.startOffset)) { it.parent }
                        .firstOrNull { it !is PsiFile && it.textRange.contains(range) }
                        ?: return@forEach
                    val highlight = when (diagnostic.severity) {
                        PipelineDiagnosticSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR
                        PipelineDiagnosticSeverity.WARNING -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                    }
                    holder.registerProblem(
                        element,
                        "${diagnostic.message}（${diagnostic.kind.label} → ${diagnostic.targetName}）。${diagnostic.suggestion}",
                        highlight,
                        range.shiftLeft(element.textRange.startOffset),
                    )
                }
            }
        }
}
