package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.util.ProcessingContext

internal data class PipelineJsonContext(val node: JsonProperty, val field: JsonProperty?, val file: JsonFile) {
    val location: PipelineNodeLocation get() = PipelineNodeLocation(node.name, node.nameElement.textRange, node.textRange,
        sourceFile = file.virtualFile, rawJson = PipelinePreviewHints.sourceText(node))

    companion object {
        fun at(element: PsiElement): PipelineJsonContext? {
            val file = element.containingFile as? JsonFile ?: return null
            val virtualFile = file.virtualFile ?: return null
            val pipeline = CachedValuesManager.getCachedValue(file) {
                CachedValueProvider.Result.create(PipelineFileDetector.isPipelineFile(virtualFile, file.text), file)
            }
            if (!pipeline) return null
            val properties = generateSequence(element) { it.parent }.filterIsInstance<JsonProperty>().toList()
            val node = properties.firstOrNull { it.parent is JsonObject && it.parent.parent is JsonFile } ?: return null
            if (node.value !is JsonObject) return null
            return PipelineJsonContext(node, properties.firstOrNull { it != node }, file)
        }
    }
}

class PipelineJsonReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(JsonStringLiteral::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val literal = element as JsonStringLiteral
                val location = PipelineJsonContext.at(literal) ?: return PsiReference.EMPTY_ARRAY
                val field = location.field ?: return PsiReference.EMPTY_ARRAY
                if (field.nameElement == literal || field.value?.textRange?.contains(literal.textRange) != true) return PsiReference.EMPTY_ARRAY
                val key = if ((field.parent as? JsonObject)?.parent.let { it is JsonProperty && it.name == "anchor" }) {
                    "anchor"
                } else if (field.name == "name" && field.parent is JsonObject && field.parent.parent !is JsonFile) {
                    val parent = generateSequence(field.parent.parent) { it.parent }.filterIsInstance<JsonProperty>().firstOrNull()
                    parent?.name
                } else field.name
                if (key !in setOf("next", "on_error", "target", "begin", "end", "roi", "all_of", "any_of", "anchor", "template", "custom_action", "custom_recognition")) return PsiReference.EMPTY_ARRAY
                if (literal.value.isBlank()) return PsiReference.EMPTY_ARRAY
                if (key == "anchor" && field.name == "anchor" && field.value !is JsonObject) return PsiReference.EMPTY_ARRAY
                return arrayOf(PipelineJsonReference(literal, location, key!!))
            }
        })
    }
}

internal class PipelineJsonReference(
    literal: JsonStringLiteral,
    private val context: PipelineJsonContext,
    private val key: String,
) : PsiPolyVariantReferenceBase<JsonStringLiteral>(literal, TextRange(1, (literal.textLength - 1).coerceAtLeast(1)), true) {
    override fun getVariants(): Array<Any> = emptyArray()

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        val value = element.value
        val targets: List<PsiElement> = when (key) {
            "template" -> listOfNotNull(PipelineVisualAssets.resolve(project, context.file.virtualFile, value)?.let { file ->
                PsiManager.getInstance(project).findFile(file) ?: PsiManager.getInstance(project).findDirectory(file)
            })
            "custom_action", "custom_recognition" -> PipelineNavigationIndex.implementations(project, value, key.removePrefix("custom_")).mapNotNull { (file, offset) ->
                PsiManager.getInstance(project).findFile(file)?.let { PipelineSourceTarget(it, offset, value) }
            }
            else -> nodeTargets(value)
        }
        return targets.distinctBy { it.containingFile?.virtualFile?.url to it.textOffset }.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    private fun nodeTargets(value: String): List<PsiElement> {
        val project = element.project
        val name = value.replace(Regex("^(?:\\[(?:JumpBack|Anchor)])+"), "")
        val attrAnchor = (element.parent as? JsonProperty)?.takeIf { it.name == "name" }?.parent
            ?.let { it as? JsonObject }?.findProperty("anchor")?.value?.text == "true"
        val anchor = value.substring(0, value.length - name.length).contains("[Anchor]") || attrAnchor
        val roots = PipelineProjectFiles.pipelineRoots(project, context.file.virtualFile)
        fun inDomain(file: VirtualFile) = roots.isEmpty() || roots.any { file.path.startsWith(it.path + "/") }
        fun definitions(target: String): List<PsiElement> = PipelineNavigationIndex.values(project, "node:$target")
            .filter { inDomain(it.first) }.mapNotNull { (file, _) ->
                val psi = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return@mapNotNull null
                val property = (psi.topLevelValue as? JsonObject)?.findProperty(target) ?: return@mapNotNull null
                PipelineSourceTarget(psi, property.nameElement.textOffset, target)
            }
        if (!anchor) return definitions(name)
        // JSON 定义未绑定到唯一执行路径，保留所有可行目标，不能把 Anchor 名当成普通节点名。
        val bindings = PipelineNavigationIndex.values(project, "anchor:$name").filter { inDomain(it.first) }
        val possible = bindings.flatMap { (_, entry) -> entry.substringAfter('\t').takeIf { it.isNotBlank() }?.let(::definitions).orEmpty() }
        if (possible.isNotEmpty()) return possible
        return bindings.mapNotNull { (file, entry) ->
            val psi = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return@mapNotNull null
            val owner = (psi.topLevelValue as? JsonObject)?.findProperty(entry.substringBefore('\t'))?.value as? JsonObject
            val definition = (owner?.findProperty("anchor")?.value as? JsonObject)?.findProperty(name)
            definition?.let { PipelineSourceTarget(psi, it.nameElement.textOffset, "Anchor $name") }
        }
    }
}

internal class PipelineSourceTarget(private val file: PsiFile, private val offset: Int, private val label: String) : FakePsiElement() {
    override fun getParent(): PsiElement = file
    override fun getContainingFile(): PsiFile = file
    override fun getName() = "$label · ${file.name}"
    override fun getTextOffset() = offset
    override fun getTextRange() = TextRange(offset, offset)
    override fun getNavigationElement(): PsiElement = this
    override fun canNavigate() = file.virtualFile?.isValid == true
    override fun canNavigateToSource() = canNavigate()
    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, virtualFile, offset), requestFocus)
    }
}
