package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil

internal object PipelineReferenceEdits {
    private fun owner(project: Project, text: String, reference: PipelineReference): JsonObject? {
        val root = PipelineOperationEdits.objectValue(project, text) ?: return null
        return PsiTreeUtil.findChildrenOfType(root, JsonObject::class.java).firstOrNull {
            it.findProperty("name")?.value?.textRange == reference.range
        }
    }
    fun range(project: Project, text: String, reference: PipelineReference): TextRange =
        if (reference.isString) reference.range else owner(project, text, reference)?.textRange ?: reference.range

    fun edit(project: Project, text: String, reference: PipelineReference, value: String): PipelineTextEdit? {
        fun quote(value: String) = com.google.gson.JsonPrimitive(value).toString()
        if (reference.isString) return PipelineTextEdit(reference.range, quote(value))
        val owner = owner(project, text, reference) ?: return null
        val name = value.replace(Regex("^(?:\\[(?:JumpBack|Anchor)])+"), "")
        val changes = mutableListOf(PipelineTextEdit(owner.findProperty("name")!!.value!!.textRange, quote(name)))
        val flags = linkedMapOf("jump_back" to value.contains("[JumpBack]"), "anchor" to value.contains("[Anchor]"))
        flags.forEach { (key, enabled) -> owner.findProperty(key)?.value?.let { changes += PipelineTextEdit(it.textRange, enabled.toString()) } }
        var updated = owner.text
        changes.sortedByDescending { it.range.startOffset }.forEach {
            updated = updated.replaceRange(it.range.startOffset - owner.textRange.startOffset, it.range.endOffset - owner.textRange.startOffset, it.replacement)
        }
        flags.filter { (key, enabled) -> enabled && owner.findProperty(key) == null }.forEach { (key, _) ->
            val indent = Regex("(?m)\\n([ \\t]+)\\\"").find(owner.text)?.groupValues?.get(1) ?: "  "
            updated = PipelineJsonTextEdits.appendMember(updated, "\"$key\": true", indent)
        }
        return PipelineTextEdit(owner.textRange, updated)
    }
}
