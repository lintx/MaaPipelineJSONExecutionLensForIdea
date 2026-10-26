package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement

internal data class PipelineJsonOperationContext(val owner: JsonObject, val kind: String) {
    val typed: JsonObject? get() = owner.findProperty(kind)?.value as? JsonObject ?: owner.takeIf { it.findProperty("type") != null }
    val parameters: JsonObject get() = typed?.findProperty("param")?.value as? JsonObject ?: typed ?: owner
    val type: String? get() = (typed?.findProperty("type")?.value as? JsonStringLiteral)?.value
        ?: (owner.findProperty(kind)?.value as? JsonStringLiteral)?.value
    val isAction: Boolean get() = kind == "action"
    val inline: Boolean get() = owner.parent is JsonArray && (owner.parent.parent as? JsonProperty)?.name in setOf("all_of", "any_of")

    fun operation(element: PsiElement, context: PipelineJsonContext, node: PipelineNodeLocation,
        resolve: (String) -> PipelineNodeLocation?): PipelineOperation? {
        val literal = generateSequence(element) { it.parent }.filterIsInstance<JsonStringLiteral>().firstOrNull()
        val array = literal?.parent as? JsonArray
        if ((array?.parent as? JsonProperty)?.name in setOf("all_of", "any_of")) {
            val name = literal!!.value
            val target = resolve(name)
            return if (target == null) PipelineOperation("Reference", emptyMap(), reference = name, source = node)
                else PipelineVisualModel.operation(target, resolve = resolve).copy(reference = name)
        }
        val path = mutableListOf<String>()
        var current: PsiElement = owner
        val root = context.node.value ?: return null
        while (current != root) {
            when (val parent = current.parent) {
                is JsonProperty -> { path.add(0, parent.name); current = parent.parent }
                is JsonArray -> { path.add(0, parent.valueList.indexOf(current).toString()); current = parent }
                else -> return null
            }
        }
        val operation = PipelineVisualModel.operation(node, isAction, resolve)
        fun find(candidate: PipelineOperation): PipelineOperation? =
            if (candidate.ownerPath == path && candidate.source?.name == node.name) candidate
            else candidate.children.firstNotNullOfOrNull(::find)
        return find(operation)
    }

    companion object {
        fun at(element: PsiElement): PipelineJsonOperationContext? {
            val field = generateSequence(element) { it.parent }.filterIsInstance<JsonProperty>().firstOrNull()?.name
            val objects = generateSequence(element) { it.parent }.filterIsInstance<JsonObject>()
            for (owner in objects) {
                val parent = owner.parent
                if (parent is JsonProperty && parent.name in setOf("recognition", "action")) {
                    return (parent.parent as? JsonObject)?.let { PipelineJsonOperationContext(it, parent.name) }
                }
                if (parent is JsonArray && (parent.parent as? JsonProperty)?.name in setOf("all_of", "any_of")) {
                    return PipelineJsonOperationContext(owner, "recognition")
                }
                if (parent is JsonProperty && parent.parent is JsonObject && parent.parent.parent is JsonFile) {
                    val action = field == "action" || field in PipelineVisualModel.operationKeys(true) - PipelineVisualModel.operationKeys(false)
                    return PipelineJsonOperationContext(owner, if (action) "action" else "recognition")
                }
            }
            return null
        }
    }
}
