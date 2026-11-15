package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

internal object PipelinePreviewCommentCompletion {
    private val regionKeys = listOf("roi", "target", "begin", "end")
    private val tokens = Regex("\"[^\"]*(?:\"|$)|[^\\s]+")

    fun complete(parameters: CompletionParameters, result: CompletionResultSet): Boolean {
        val comment = PsiTreeUtil.getParentOfType(parameters.position, PsiComment::class.java, false) ?: return false
        val offset = parameters.offset
        val line = comment.text.take((offset - comment.textRange.startOffset).coerceIn(0, comment.textLength))
            .substringAfterLast('\n').trimStart().removePrefix("//").removePrefix("/*").removePrefix("*").trimStart()
        fun add(value: String, prefix: String, detail: String, suffix: String = "", coordinates: Boolean = false, priority: Double = 0.0) {
            val matches = result.withPrefixMatcher(PipelineCompletionMatcher(prefix.removePrefix("\"")))
            val rankedPriority = if (priority > 0 && value.removeSurrounding("\"").startsWith(prefix.removePrefix("\""), true)) priority + 100 else priority
            matches.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(value).withTailText("  $detail", true)
                .withInsertHandler { context, _ ->
                    val start = offset - prefix.length
                    val end = start + value.length
                    context.document.replaceString(start, context.tailOffset, value)
                    context.tailOffset = end
                    val closing = if (coordinates) ']' else if (value.startsWith('"')) '"' else null
                    if (closing != null && context.document.charsSequence.getOrNull(end) == closing) {
                        context.document.deleteString(end, end + 1)
                    }
                    var caret = end
                    if (suffix.isNotEmpty()) {
                        if (context.document.charsSequence.getOrNull(end) !in setOf(' ', '\t')) {
                            context.document.insertString(end, suffix)
                        }
                        caret++
                    }
                    context.editor.caretModel.moveToOffset(caret)
                    if (coordinates) context.editor.selectionModel.setSelection(start + 1, end - 1)
                    context.setAddCompletionChar(false)
                }, rankedPriority))
        }
        if (Regex("^@[a-zA-Z]*$").matches(line)) {
            add("@preview", line, "参考图：[控制器] 图片路径", " ")
            add("@roi", line, "示意区域：[控制器] [roi/target/begin/end] [x, y, w, h]", " ")
            return true
        }
        val directive = Regex("^@(preview|roi)\\s+").find(line) ?: return false
        val body = line.substring(directive.value.length)
        val parts = tokens.findAll(body).map { it.value }.toList()
        val current = if (body.lastOrNull()?.isWhitespace() == false) parts.lastOrNull().orEmpty() else ""
        val completed = if (current.isEmpty()) parts else parts.dropLast(1)
        val file = parameters.originalFile.virtualFile ?: return true
        val project = parameters.originalFile.project
        val roots = (listOfNotNull(PipelineProjectFiles.base(project)) + ProjectRootManager.getInstance(project).contentRoots).distinct()
        val manifest = (PipelineProjectFiles.manifest(project, file) ?: roots.firstNotNullOfOrNull {
            it.findFileByRelativePath("assets/interface.json") ?: it.findChild("interface.json")
        })?.let(PipelineProjectFiles::text)
        val environments = PipelineEnvironment.available(manifest)
        val controllers = environments.map { it.id }.plus(environments.map { it.controller })
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        fun quoted(value: String): String = if (value.any(Char::isWhitespace)) "\"$value\"" else value
        if (directive.groupValues[1] == "roi") {
            if (completed.any { '[' in it }) return true
            if (completed.none { it.removeSurrounding("\"") in regionKeys }) {
                regionKeys.forEach { add(it, current, "区域字段", " ") }
            }
            if (completed.none { it.removeSurrounding("\"") !in regionKeys }) {
                controllers.forEach { add(quoted(it), current, "控制器", " ", priority = 100.0) }
            }
            // 数组中的空格属于同一个值，不能把后半段坐标当作控制器或字段候选。
            val arrayStart = body.indexOf('[')
            val prefix = if (arrayStart >= 0) body.substring(arrayStart) else current
            if (',' !in prefix) add("[0, 0, 0, 0]", prefix, "x, y, width, height（支持负数）", coordinates = true)
            return true
        }
        if (completed.isEmpty()) controllers.forEach { add(quoted(it), current, "控制器", " ", priority = 100.0) }
        if (DumbService.isDumb(project)) return true
        val scope = GlobalSearchScope.projectScope(project)
        val paths = linkedSetOf<String>()
        listOf("png", "jpg", "jpeg", "webp", "bmp").forEach { extension ->
            FilenameIndex.getAllFilesByExt(project, extension, scope).forEach { image ->
                val path = roots.firstNotNullOfOrNull { VfsUtilCore.getRelativePath(image, it, '/') }
                if (path != null) paths += path
            }
        }
        paths.forEach { add(quoted(it), current, "项目参考图") }
        return true
    }
}

class PipelinePreviewCommentTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if ((!charTyped.isLetterOrDigit() && charTyped !in "@/\\_ [") || !isMaaJson(file)) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val element = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return Result.CONTINUE
        val comment = PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) ?: return Result.CONTINUE
        val line = comment.text.take((offset - comment.textRange.startOffset).coerceIn(0, comment.textLength)).substringAfterLast('\n')
        if (charTyped != '@' && !Regex("@(?:preview|roi)\\b|@[a-zA-Z]*$").containsMatchIn(line)) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}
