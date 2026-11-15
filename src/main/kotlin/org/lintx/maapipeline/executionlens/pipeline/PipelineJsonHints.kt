package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hints.declarative.AboveLineIndentedPosition
import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFontSize
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.HintMarginPadding
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.codeInsight.navigation.getPsiElementPopup
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil

internal data class PipelineJsonNodeUse(val name: String, val anchor: Boolean, val field: String)

internal object PipelineJsonSymbols {
    fun description(property: JsonProperty): String? {
        val value = (property.value as? JsonObject)?.findProperty("desc")?.value
        return when (value) {
            is JsonStringLiteral -> value.value
            is JsonArray -> value.valueList.filterIsInstance<JsonStringLiteral>().joinToString("\n") { it.value }
            else -> null
        }?.takeIf(String::isNotBlank)
    }

    fun use(literal: JsonStringLiteral): PipelineJsonNodeUse? {
        val property = generateSequence(literal.parent) { it.parent }.filterIsInstance<JsonProperty>().firstOrNull() ?: return null
        if (property.nameElement == literal) return null
        return use(property, literal.value)?.takeIf { it.name.isNotBlank() }
    }

    fun use(property: JsonProperty, value: String): PipelineJsonNodeUse? {
        val properties = generateSequence(property as PsiElement) { it.parent }.filterIsInstance<JsonProperty>().toList()
        val anchorDefinition = (property.parent as? JsonObject)?.parent.let { it is JsonProperty && it.name == "anchor" }
        val field = when {
            anchorDefinition -> "anchor"
            property.name == "name" -> properties.drop(1).firstOrNull()?.name
            else -> property.name
        }
        if (field !in setOf("next", "on_error", "entry", "roi", "target", "begin", "end", "all_of", "any_of", "color_filter", "sub") &&
            !anchorDefinition) return null
        if (field != "sub" && properties.any { it.name in setOf("custom_action_param", "custom_recognition_param") }) return null
        val marker = Regex("^(?:\\[(?:JumpBack|Anchor)])+").find(value)?.value.orEmpty()
        val attributes = property.parent as? JsonObject
        val anchor = "[Anchor]" in marker || property.name == "name" && attributes?.findProperty("anchor")?.value?.text == "true"
        return PipelineJsonNodeUse(value.removePrefix(marker), anchor, field!!)
    }
}

internal class PipelineJsonSymbolLookup(private val project: Project, private val file: JsonFile) {
    private val source = file.virtualFile
    private val roots = source?.let { PipelineProjectFiles.pipelineRoots(project, it) }.orEmpty()
    private val manifest = source?.let { PipelineProjectFiles.manifest(project, it) }
    private val local = ((file.topLevelValue as? JsonObject)?.propertyList.orEmpty())
        .mapNotNull { property -> PipelineJsonSymbols.description(property)?.let { property.name to it } }.toMap()
    private val descriptions = mutableMapOf<String, String?>()

    private fun inDomain(candidate: VirtualFile): Boolean = roots.isEmpty() || candidate == source ||
        roots.any { candidate.path.startsWith(it.path + "/") } || manifest?.parent?.let { directory ->
            candidate.path.startsWith(directory.path + "/") &&
                (candidate == manifest || "/task/" in candidate.path || "/tasks/" in candidate.path)
        } == true

    fun description(name: String, anchor: Boolean = false): String? {
        if (anchor) {
            val targets = PipelineNavigationIndex.values(project, "anchor:$name").filter { inDomain(it.first) }
                .map { it.second.substringAfter('\t') }.filter(String::isNotBlank).distinct()
            return targets.singleOrNull()?.let { description(it) }
        }
        if (name in local) return local[name]
        return descriptions.getOrPut(name) {
            val candidates = PipelineNavigationIndex.values(project, "desc:$name").filter { inDomain(it.first) }
            val preferred = candidates.firstOrNull { it.first == source } ?: candidates.singleOrNull()
            preferred?.second?.let { PipelineJsonc.parse(it).asString }
        }
    }

    fun usages(name: String): List<JsonStringLiteral> {
        val supportsSubTasks by lazy { source?.let { PipelineProjectFeatures.supportsMaaEndSubTasks(project, it) } == true }
        return PipelineNavigationIndex.values(project, "use:$name").filter { inDomain(it.first) }.mapNotNull { (candidate, position) ->
            val offset = position.toIntOrNull() ?: return@mapNotNull null
            val psi = PsiManager.getInstance(project).findFile(candidate) as? JsonFile ?: return@mapNotNull null
            val literal = PsiTreeUtil.getParentOfType(psi.findElementAt(offset), JsonStringLiteral::class.java, false)
                ?: return@mapNotNull null
            val use = PipelineJsonSymbols.use(literal) ?: return@mapNotNull null
            literal.takeIf { use.name == name && !use.anchor && (use.field != "sub" || supportsSubTasks) }
        }.distinctBy { it.containingFile.virtualFile to it.textOffset }
    }
}

class PipelineJsonDescriptionHints : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): SharedBypassCollector? {
        if (file !is JsonFile || !isMaaJson(file) || DumbService.isDumb(file.project)) return null
        val source = file.virtualFile ?: return null
        val isPipeline = PipelineFileDetector.isPipelineFile(source, file.text)
        val lookup = PipelineJsonSymbolLookup(file.project, file)
        val format = HintFormat(HintColorKind.TextWithoutBackground, HintFontSize.ABitSmallerThanInEditor, HintMarginPadding.OnlyPadding)
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (isPipeline && element is JsonProperty && element.parent?.parent is JsonFile && element.value is JsonObject) {
                    val nodeName = element.name
                    val count = lookup.usages(nodeName).size
                    sink.addPresentation(AboveLineIndentedPosition(element.textOffset), tooltip = "查看 $nodeName 的引用", hintFormat = format) {
                        text("$count 处引用", InlayActionData(StringInlayActionPayload(nodeName), PipelineJsonReferenceInlayAction.ID))
                    }
                }
                if (element !is JsonStringLiteral) return
                val description = PipelineJsonSymbols.use(element)?.let { lookup.description(it.name, it.anchor) }
                if (description != null) {
                    val compact = description.replace(Regex("\\s+"), " ").trim()
                    val text = compact.take(100) + if (compact.length > 100) "…" else ""
                    sink.addPresentation(EndOfLinePosition(editor.document.getLineNumber(element.textOffset), -element.textOffset),
                        tooltip = description, hintFormat = format) { text(text) }
                }
            }
        }
    }
}

internal object PipelineJsonReferences {
    fun showUsages(project: Project, source: VirtualFile, name: String, editor: Editor) {
        ReadAction.nonBlocking<List<PsiElement>> {
            val file = PsiManager.getInstance(project).findFile(source) as? JsonFile ?: return@nonBlocking emptyList()
            PipelineJsonSymbolLookup(project, file).usages(name).map { literal ->
                val owner = generateSequence(literal.parent) { it.parent }.filterIsInstance<JsonProperty>()
                    .firstOrNull { it.parent?.parent is JsonFile }?.name.orEmpty()
                val field = PipelineJsonSymbols.use(literal)?.field.orEmpty()
                PipelineSourceTarget(literal.containingFile, literal.textOffset, "$owner · $field")
            }
        }.expireWith(project).finishOnUiThread(ModalityState.nonModal()) { targets ->
            if (!editor.isDisposed) {
                if (targets.isEmpty()) HintManager.getInstance().showInformationHint(editor, "没有找到节点引用")
                else getPsiElementPopup(targets.toTypedArray(), "$name 的引用").showInBestPositionFor(editor)
            }
        }.submit(AppExecutorUtil.getAppExecutorService())
    }
}
