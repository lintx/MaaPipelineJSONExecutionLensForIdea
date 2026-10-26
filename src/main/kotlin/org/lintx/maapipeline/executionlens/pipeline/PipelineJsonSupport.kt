package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.codeinsight.JsonStandardComplianceProvider
import com.intellij.json.psi.JsonElement
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.util.indexing.FileBasedIndex

internal fun isMaaJson(file: PsiFile): Boolean {
    val source = file.virtualFile ?: file.originalFile.virtualFile ?: return false
    if (source.extension?.lowercase() !in setOf("json", "jsonc")) return false
    return com.intellij.psi.util.CachedValuesManager.getCachedValue(file) {
        val path = source.path.replace('\\', '/').lowercase()
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject
        val supported = "/pipeline/" in path || root?.propertyList?.any { property ->
            val node = property.value as? JsonObject
            node?.propertyList?.any { it.name in setOf("recognition", "action", "next", "on_error", "anchor") } == true
        } == true || root?.let { it.findProperty("task")?.value is com.intellij.json.psi.JsonArray ||
            it.findProperty("option")?.value is JsonObject && it.findProperty("import") != null ||
            ("/tasks/" in path || "/task/" in path) && it.propertyList.any { property ->
                (property.value as? JsonObject)?.findProperty("entry") != null
            }
        } == true
        com.intellij.psi.util.CachedValueProvider.Result.create(supported, file)
    }
}

class PipelineJsonCompliance : JsonStandardComplianceProvider() {
    override fun isCommentAllowed(comment: PsiComment): Boolean = isMaaJson(comment.containingFile)
    override fun isTrailingCommaAllowed(element: JsonElement): Boolean = isMaaJson(element.containingFile)
}

class PipelineJsonCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!isMaaJson(file)) return
        val position = parameters.position
        val property = generateSequence(position) { it.parent }.filterIsInstance<JsonProperty>().firstOrNull()
        val literal = generateSequence(position) { it.parent }.filterIsInstance<JsonStringLiteral>().firstOrNull()
        val keyPosition = property?.nameElement?.textRange?.contains(position.textRange) == true
        val owner = generateSequence(position) { it.parent }.filterIsInstance<JsonObject>().firstOrNull()
        val ancestors = generateSequence(position) { it.parent }.filterIsInstance<JsonProperty>().toList()
        val operationContext = PipelineJsonOperationContext.at(position)
        val taskFile = ((file as? JsonFile)?.topLevelValue as? JsonObject)?.findProperty("task") != null ||
            file.virtualFile?.path?.let { "/tasks/" in it || "/task/" in it } == true
        val referenceParent = when (val parent = owner?.parent) {
            is JsonProperty -> parent
            is JsonArray -> parent.parent as? JsonProperty
            else -> null
        }
        val referenceObject = referenceParent?.name in setOf("next", "on_error")
        val matches = result.withPrefixMatcher(PipelineCompletionMatcher(result.prefixMatcher.prefix))
        if (ancestors.any { it.name in setOf("custom_action_param", "custom_recognition_param") } &&
            (property == null || PipelineJsonSymbols.use(property, literal?.value.orEmpty()) == null) &&
            PipelineJsonRegisteredParameters.complete(parameters, matches)) {
            result.stopHere()
            return
        }
        fun add(value: String, detail: String = "", string: Boolean = true, key: Boolean = false, defaultValue: String? = null,
            customKind: String? = null) {
            matches.addElement(LookupElementBuilder.create(value)
                .withPresentableText(if (customKind == null) value else "Custom · $value")
                .withTailText(detail.substringBefore('\n').takeIf { it.isNotBlank() }?.let {
                    "  " + if (it.length > 36) it.take(35) + "…" else it
                }.orEmpty(), true)
                .withInsertHandler { context, _ ->
                    val document = context.document
                    val inserted = if (customKind == null) value else "Custom"
                    // 匹配不区分大小写，但写入必须保留节点、模板和字段定义中的原始拼写。
                    document.replaceString(context.startOffset, context.tailOffset, inserted)
                    var end = context.startOffset + inserted.length
                    context.tailOffset = end
                    if (string || key) {
                        if (document.charsSequence.getOrNull(context.startOffset - 1) != '"') {
                            document.insertString(context.startOffset, "\"")
                            end++
                        }
                        if (document.charsSequence.getOrNull(end) != '"') document.insertString(end, "\"")
                        end++
                    }
                    if (key) {
                        val next = document.charsSequence.drop(end).indexOfFirst { !it.isWhitespace() }.let { if (it < 0) document.textLength else end + it }
                        if (document.charsSequence.getOrNull(next) != ':') {
                            val initial = defaultValue ?: when (value) {
                                "enabled" -> "true"
                                "anchor" -> if (referenceObject) "false" else "{}"
                                "jump_back" -> "false"
                                "recognition" -> "\"DirectHit\""
                                "action" -> "\"DoNothing\""
                                "next", "on_error", "all_of", "any_of", "template", "expected", "lower", "upper", "option" -> "[]"
                                "roi", "roi_offset", "target_offset", "begin_offset", "end_offset" -> "[0, 0, 0, 0]"
                                "target", "begin", "end" -> "true"
                                "param", "pipeline_override", "custom_action_param", "custom_recognition_param" -> "{}"
                                "timeout" -> "20000"
                                "threshold" -> "0.7"
                                "rate_limit" -> "1000"
                                "pre_delay", "post_delay" -> "200"
                                "max_hit" -> "4294967295"
                                "repeat" -> "1"
                                "index", "box_index", "repeat_delay", "pre_wait_freezes", "post_wait_freezes", "repeat_wait_freezes" -> "0"
                                "inverse", "connected", "green_mask", "only_rec", "auto_up", "only_hover", "detach" -> "false"
                                else -> "\"\""
                            }
                            document.insertString(end, ": $initial")
                            end += 2
                            if (initial.first() in setOf('"', '[', '{')) end++
                        } else end = next + 1
                    }
                    context.editor.caretModel.moveToOffset(end)
                    context.setAddCompletionChar(false)
                    if (customKind != null) insertCustomName(context, customKind, value)
                })
        }
        if (keyPosition || property == null) {
            val containingKey = (owner?.parent as? JsonProperty)?.name
            if (containingKey in setOf("anchor", "pipeline_override")) {
                if (com.intellij.openapi.project.DumbService.isDumb(file.project)) return
                val prefix = if (containingKey == "anchor") "anchor:" else "node:"
                FileBasedIndex.getInstance().getAllKeys(PipelineNavigationIndex.NAME, file.project)
                    .filter { it.startsWith(prefix) }.forEach { add(it.removePrefix(prefix), key = true,
                        defaultValue = if (containingKey == "pipeline_override") "{}" else "\"\"") }
                return
            }
            val nodeKeys = (listOf("desc", "recognition", "action", "next", "on_error", "anchor", "enabled",
                "timeout", "rate_limit", "max_hit", "pre_delay", "post_delay", "repeat", "repeat_delay", "pre_wait_freezes", "post_wait_freezes",
                "repeat_wait_freezes", "focus", "attach") + recognitionKeys + actionKeys).distinct()
            val keys = when {
                referenceObject -> listOf("name", "jump_back", "anchor")
                ancestors.any { it.name in setOf("custom_action_param", "custom_recognition_param") } -> emptyList()
                owner != null && owner == operationContext?.typed -> listOf("type", "param")
                owner != null && owner == operationContext?.parameters && operationContext.typed != null ->
                    (if (operationContext.isAction) actionKeys else recognitionKeys).toList()
                owner != null && owner == operationContext?.owner && operationContext.inline ->
                    (recognitionKeys + listOf("recognition", "type", "param")).toList()
                taskFile && containingKey == "option" && owner?.parent?.parent?.parent is JsonFile -> emptyList()
                taskFile && ancestors.any { it.name == "pipeline_override" } -> nodeKeys
                taskFile && ancestors.any { it.name == "inputs" } -> listOf("name", "label", "default", "verify", "pipeline_type")
                taskFile && ancestors.any { it.name == "cases" } -> listOf("name", "label", "description", "option", "pipeline_override")
                taskFile && ancestors.any { it.name == "task" } -> listOf("name", "label", "entry", "description", "option", "pipeline_override")
                taskFile && ancestors.any { it.name == "option" } -> listOf("label", "description", "type", "cases", "default_case", "inputs", "option", "pipeline_override")
                taskFile -> listOf("task", "option", "import", "controller", "resource", "agent", "name", "label", "description", "version")
                owner?.parent is JsonFile -> emptyList()
                else -> nodeKeys.filter { it !in setOf("type", "param") }
            }
            keys.filter { owner?.findProperty(it) == null || it == property?.name }.forEach { add(it, PipelinePropertyCatalog.help(it), key = true) }
            return
        }
        val owners = generateSequence(property as com.intellij.psi.PsiElement) { it.parent }.filterIsInstance<JsonProperty>().toList()
        val key = property.name
        val use = PipelineJsonSymbols.use(property, literal?.value.orEmpty())
        if (owners.any { it.name in setOf("custom_action_param", "custom_recognition_param") } && use == null) return
        val isAction = operationContext?.isAction == true
        fun addOperationTypes(action: Boolean) {
            (if (action) actions else recognitions).forEach { add(it, if (action) "动作类型" else "识别类型") }
            val kind = if (action) "action" else "recognition"
            PipelineNavigationIndex.customNames(file.project, kind).forEach {
                add(it, if (action) "自定义动作" else "自定义识别", customKind = kind)
            }
        }
        when {
            key == "action" || key == "type" && isAction -> addOperationTypes(true)
            key == "recognition" || key == "type" && operationContext?.kind == "recognition" -> addOperationTypes(false)
            key == "type" && taskFile -> listOf("select", "multiselect", "input").forEach { add(it, "选项类型") }
            key == "box_index" -> {
                val count = (operationContext?.parameters?.findProperty("all_of")?.value as? JsonArray)?.valueList?.size ?: 0
                (0 until count).forEach { add(it.toString(), "第 ${it + 1} 个子条件", string = false) }
            }
            key in setOf("enabled", "inverse", "connected", "green_mask", "only_rec", "auto_up", "only_hover", "detach", "jump_back") || referenceObject && key == "anchor" -> listOf("true", "false").forEach { add(it, string = false) }
            key == "order_by" -> listOf("Horizontal", "Vertical", "Score", "Area", "Random", "Expected").forEach { add(it) }
            key == "detector" -> listOf("SIFT", "SURF", "ORB", "BRISK", "KAZE", "AKAZE").forEach { add(it, "特征检测器") }
            key == "format" -> listOf("png", "jpg", "webp").forEach { add(it, "截图格式") }
            key == "method" -> {
                val recognition = operationContext?.type
                val values = if (recognition == "ColorMatch") listOf(4 to "RGB", 40 to "HSV", 6 to "灰度")
                    else if (recognition == "TemplateMatch") listOf(0 to "SQDIFF", 1 to "SQDIFF_NORMED", 2 to "CCORR", 3 to "CCORR_NORMED", 4 to "CCOEFF", 5 to "CCOEFF_NORMED", 10001 to "反转 SQDIFF_NORMED") else emptyList()
                values.forEach { add(it.first.toString(), it.second, string = false) }
            }
            key in setOf("custom_action", "custom_recognition") -> {
                PipelineNavigationIndex.customNames(file.project, key.removePrefix("custom_")).forEach { add(it, "自定义实现") }
            }
            owners.any { it.name == "template" } -> {
                if (com.intellij.openapi.project.DumbService.isDumb(file.project)) return
                val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(file.project)
                listOf("png", "jpg", "jpeg", "webp", "bmp").forEach { extension ->
                    com.intellij.psi.search.FilenameIndex.getAllFilesByExt(file.project, extension, scope).forEach { image ->
                        val path = image.path.substringAfter("/image/", image.name)
                        add(path, "模板图片")
                    }
                }
            }
            use != null && (property.value !is JsonArray || key in setOf("next", "on_error", "all_of", "any_of", "sub") || key == "end" && literal != null) -> {
                if (com.intellij.openapi.project.DumbService.isDumb(file.project)) return
                val prefix = literal?.value.orEmpty().substringBefore("IntellijIdeaRulezzz")
                val anchor = use.anchor
                val attributes = use.field in setOf("next", "on_error", "roi", "target", "begin", "end")
                val marker = if (attributes) Regex("^(?:\\[(?:JumpBack|Anchor)])+").find(prefix)?.value.orEmpty() else ""
                val lookup = if (anchor) "anchor:" else "node:"
                FileBasedIndex.getInstance().getAllKeys(PipelineNavigationIndex.NAME, file.project).filter { it.startsWith(lookup) }.forEach {
                    add(marker + it.removePrefix(lookup), if (anchor) "Anchor" else "节点")
                }
                if (!anchor && attributes) {
                    add("[Anchor]", "引用运行时 Anchor")
                    if (use.field in setOf("next", "on_error")) add("[JumpBack]", "子链结束后重试父 next")
                }
                if (key == "roi" && !anchor) {
                    subRecognitionNames(property).forEach { add(it, "前置子识别区域") }
                }
            }
        }
    }

    private fun insertCustomName(context: com.intellij.codeInsight.completion.InsertionContext, kind: String, name: String) {
        val documents = PsiDocumentManager.getInstance(context.project)
        documents.commitDocument(context.document)
        val file = documents.getPsiFile(context.document) ?: return
        val element = file.findElementAt(context.startOffset) ?: return
        val property = PsiTreeUtil.getParentOfType(element, JsonProperty::class.java) ?: return
        val owner = property.parent as? JsonObject ?: return
        val generator = JsonElementGenerator(context.project)
        fun put(target: JsonObject, key: String, value: String): JsonProperty {
            val created = generator.createProperty(key, value)
            val existing = target.findProperty(key)
            return (if (existing == null) com.intellij.json.psi.JsonPsiUtil.addProperty(target, created, false)
                else existing.replace(created)) as JsonProperty
        }
        // v2 的实现名属于 param，不能把自定义名字当作框架类型，也不能覆盖用户已有的参数对象。
        val parameters = if (property.name == "type") {
            (owner.findProperty("param")?.value as? JsonObject) ?: (put(owner, "param", "{}").value as JsonObject)
        } else owner
        val inserted = put(parameters, "custom_$kind", com.google.gson.JsonPrimitive(name).toString())
        val pointer = com.intellij.psi.SmartPointerManager.getInstance(context.project).createSmartPsiElementPointer(inserted)
        // PSI 插入会推迟同步文档；先完成同步并重取有效文件，不能将旧 AST 交给格式器。
        documents.doPostponedOperationsAndUnblockDocument(context.document)
        documents.commitDocument(context.document)
        val currentFile = documents.getPsiFile(context.document)?.takeIf { it.isValid } ?: return
        val range = pointer.element?.takeIf { it.isValid && it.containingFile == currentFile }?.textRange ?: return
        CodeStyleManager.getInstance(context.project).reformatText(currentFile, range.startOffset, range.endOffset)
        documents.doPostponedOperationsAndUnblockDocument(context.document)
    }

    private fun subRecognitionNames(property: JsonProperty): Set<String> {
        val result = linkedSetOf<String>()
        var child: com.intellij.psi.PsiElement = property
        while (true) {
            val parent = child.parent ?: break
            if (parent is JsonArray && (parent.parent as? JsonProperty)?.name == "all_of") {
                parent.valueList.takeWhile { it != child }.filterIsInstance<JsonObject>().forEach { sibling ->
                    (sibling.findProperty("sub_name")?.value as? JsonStringLiteral)?.value?.let(result::add)
                }
            }
            child = parent
        }
        return result
    }
    companion object {
        private val recognitionKeys = PipelineVisualModel.operationKeys(false) - "recognition"
        private val actionKeys = PipelineVisualModel.operationKeys(true) - "action"
        val actions = listOf("DoNothing", "Click", "LongPress", "Swipe", "MultiSwipe", "TouchDown", "TouchMove", "TouchUp", "Scroll", "ClickKey", "LongPressKey", "KeyDown", "KeyUp", "InputText", "StartApp", "StopApp", "Command", "Shell", "Screencap", "Custom")
        val recognitions = listOf("DirectHit", "TemplateMatch", "FeatureMatch", "ColorMatch", "OCR", "NeuralNetworkClassify", "NeuralNetworkDetect", "And", "Or", "Custom")
    }
}

internal class PipelineCompletionMatcher(prefix: String) : PrefixMatcher(prefix) {
    private val matcher = NameUtil.buildMatcher("*$prefix").withCaseSensitivity(NameUtil.MatchingCaseSensitivity.NONE).build()
    override fun prefixMatches(name: String): Boolean = matcher.matches(name)
    override fun cloneWithPrefix(prefix: String): PrefixMatcher = PipelineCompletionMatcher(prefix)
}
