package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonParser
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil

internal object PipelineOperationEdits {
    fun objectValue(project: Project, text: String): JsonObject? {
        val file = PsiFileFactory.getInstance(project).createFileFromText("PipelineFragment.json", JsonFileType.INSTANCE, text) as? JsonFile
        return file?.takeUnless { PsiTreeUtil.hasErrorElements(it) }?.topLevelValue as? JsonObject
    }

    fun at(root: JsonValue, path: List<String>): JsonValue? = path.fold(root as JsonValue?) { current, part ->
        when (current) {
            is JsonObject -> current.findProperty(part)?.value
            is JsonArray -> part.toIntOrNull()?.let { current.valueList.getOrNull(it) }
            else -> null
        }
    }

    fun fragment(owner: JsonObject, isAction: Boolean): String {
        val keys = PipelineVisualModel.operationKeys(isAction)
        val members = owner.propertyList.filter { it.name in keys }.map { it.text }.toMutableList()
        val field = if (isAction) "action" else "recognition"
        if (owner.findProperty(field) == null) members.add(0, "\"$field\": \"${if (isAction) "DoNothing" else "DirectHit"}\"")
        return "{\n" + members.joinToString(",\n") + "\n}"
    }

    fun validateFragment(project: Project, fragment: String, isAction: Boolean): String? {
        val owner = objectValue(project, fragment) ?: return "请输入包含类型和参数的 JSON 对象。"
        val allowed = PipelineVisualModel.operationKeys(isAction)
        if (owner.propertyList.any { it.name !in allowed }) return "请只编辑当前${if (isAction) "动作" else "条件"}的字段。"
        if (owner.propertyList.map { it.name }.distinct().size != owner.propertyList.size) return "字段名不能重复。"
        return null
    }

    fun operationEdit(project: Project, owner: JsonObject, fragment: String, isAction: Boolean): PipelineTextEdit? {
        require(validateFragment(project, fragment, isAction) == null)
        val replacement = objectValue(project, fragment)!!
        var updated = owner.text
        val keys = PipelineVisualModel.operationKeys(isAction)
        val incoming = replacement.propertyList.associate { it.name to it.value!!.text }
        for (name in keys) {
            val current = objectValue(project, updated)!!
            val property = current.findProperty(name)
            val value = incoming[name]
            updated = when {
                property != null && value == null -> PipelineJsonTextEdits.applyEdits(updated,
                    PipelineJsonTextEdits.deletionEdits(updated, property.textRange))
                property == null && value != null -> PipelineJsonTextEdits.appendMember(updated,
                    "\"$name\": $value", memberIndent(current))
                property?.value != null && value != null -> {
                    val original = property.value!!
                    val changed = mergeValue(project, original.text, value)
                    updated.replaceRange(original.textRange.startOffset, original.textRange.endOffset,
                        PipelineJsonTextEdits.alignValue(updated, original.textRange.startOffset, changed))
                }
                else -> updated
            }
        }
        if (updated == owner.text) return null
        return PipelineJsonTextEdits.replacementEdit(owner.textRange, owner.text, updated)
    }

    private fun memberIndent(owner: JsonObject): String {
        val text = owner.text
        val start = owner.propertyList.firstOrNull()?.textRange?.startOffset?.minus(owner.textRange.startOffset) ?: return "  "
        val line = text.lastIndexOf('\n', start) + 1
        return text.substring(line, start).takeIf { it.isNotEmpty() && it.all(Char::isWhitespace) } ?: "  "
    }

    private fun mergeValue(project: Project, original: String, incoming: String): String {
        if (runCatching { PipelineJsonc.parse(original) == PipelineJsonc.parse(incoming) }.getOrDefault(false)) return original
        val oldObject = objectValue(project, original)
        val newObject = objectValue(project, incoming)
        if (oldObject != null && newObject != null) {
            var text = original
            val entries = newObject.propertyList.associate { it.name to it.value!!.text }
            (oldObject.propertyList.map { it.name } + entries.keys).distinct().forEach { name ->
                val current = objectValue(project, text)!!
                val property = current.findProperty(name)
                val value = entries[name]
                text = when {
                    property != null && value == null -> PipelineJsonTextEdits.applyEdits(text,
                        PipelineJsonTextEdits.deletionEdits(text, property.textRange))
                    property == null && value != null -> PipelineJsonTextEdits.appendMember(text,
                        "\"$name\": $value", memberIndent(current))
                    property?.value != null && value != null -> {
                        val previous = property.value!!
                        val merged = mergeValue(project, previous.text, value)
                        text.replaceRange(previous.textRange.startOffset, previous.textRange.endOffset,
                            PipelineJsonTextEdits.alignValue(text, previous.textRange.startOffset, merged))
                    }
                    else -> text
                }
            }
            return text
        }
        val oldArray = parseValue(project, original) as? JsonArray
        val newArray = parseValue(project, incoming) as? JsonArray
        if (oldArray != null && newArray != null && oldArray.valueList.size == newArray.valueList.size) {
            var text = original
            oldArray.valueList.zip(newArray.valueList).asReversed().forEach { (old, new) ->
                val start = old.textRange.startOffset - oldArray.textRange.startOffset
                val end = old.textRange.endOffset - oldArray.textRange.startOffset
                text = text.replaceRange(start, end, PipelineJsonTextEdits.alignValue(text, start, mergeValue(project, old.text, new.text)))
            }
            return text
        }
        return incoming
    }

    private fun parseValue(project: Project, text: String): JsonValue? =
        (PsiFileFactory.getInstance(project).createFileFromText("Value.json", JsonFileType.INSTANCE, text) as? JsonFile)?.topLevelValue

    fun roiEdit(project: Project, owner: JsonObject, operation: PipelineOperation, key: String,
        rectangle: java.awt.Rectangle): PipelineTextEdit? = roiValuesEdit(owner, operation, key,
            listOf(rectangle.x, rectangle.y, rectangle.width, rectangle.height))

    fun roiValuesEdit(owner: JsonObject, operation: PipelineOperation, key: String, values: List<Int>): PipelineTextEdit? {
        if (values.size !in setOf(2, 4)) return null
        if (PipelineVisualModel.region(operation, key).coordinates == null) return null
        val replacement = values.toString()
        if (PipelineVisualModel.region(operation, key).fromComment) {
            val hint = operation.roiHints[key] ?: return null
            val property = owner.parent as? com.intellij.json.psi.JsonProperty
            val raw = property?.let(PipelinePreviewHints::sourceText) ?: owner.text
            val current = PipelinePreviewHints.read(raw)[hint.path]?.regions?.lastOrNull {
                (it.key ?: "roi") == key && it.controller.equals(hint.controller, true)
            } ?: return null
            val start = owner.textRange.startOffset - (raw.length - owner.textLength)
            return PipelineTextEdit(current.range.shiftRight(start), replacement)
        }
        return regionValueEdit(owner, operation, key, replacement)
    }

    fun offsetEdit(owner: JsonObject, operation: PipelineOperation, key: String, values: List<Int>): PipelineTextEdit? {
        if (values.size != 4) return null
        return regionValueEdit(owner, operation, "${key}_offset", values.toString())
    }

    private fun regionValueEdit(owner: JsonObject, operation: PipelineOperation, key: String, replacement: String): PipelineTextEdit? {
        val baseKey = key.removeSuffix("_offset")
        val path = operation.parameterPaths[key] ?: operation.parameterPaths[baseKey]?.dropLast(1)?.plus(key) ?: run {
            val operationOwner = at(owner, operation.ownerPath) as? JsonObject ?: return null
            val typed = operationOwner.findProperty(if (operation.isAction) "action" else "recognition")?.value as? JsonObject
                ?: operationOwner.takeIf { it.findProperty("type") != null }
            val typedPath = if (typed === operationOwner) operation.ownerPath else operation.ownerPath + if (operation.isAction) "action" else "recognition"
            val param = typed?.findProperty("param")?.value as? JsonObject
            when {
                param != null -> typedPath + listOf("param", key)
                typed != null -> typedPath + listOf("param", key)
                else -> operation.ownerPath + key
            }
        }
        val value = at(owner, path)
        if (value != null) return PipelineTextEdit(value.textRange, replacement)
        val container = at(owner, path.dropLast(1)) as? JsonObject
        if (container == null && path.getOrNull(path.size - 2) == "param") {
            val typed = at(owner, path.dropLast(2)) as? JsonObject ?: return null
            val updated = PipelineJsonTextEdits.appendMember(typed.text, "\"param\": {\"$key\": $replacement}", memberIndent(typed))
            return PipelineJsonTextEdits.replacementEdit(typed.textRange, typed.text, updated)
        }
        if (container == null) return null
        val updated = PipelineJsonTextEdits.appendMember(container.text, "\"$key\": $replacement", memberIndent(container))
        return PipelineJsonTextEdits.replacementEdit(container.textRange, container.text, updated)
    }
}
