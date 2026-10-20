package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class PipelineTraceTaskOverrides(
    val taskId: Long,
    val entryName: String,
    val startLineNumber: Int,
    val sourceLineNumber: Int,
    val rawJson: String,
)

internal class PipelineTraceOverrideCollector {
    private data class Pending(
        val process: String,
        val thread: String,
        val entry: String,
        val line: Int,
        val raw: String,
        val apiWrapper: Boolean,
        var taskId: Long? = null,
    )
    private val pending = mutableListOf<Pending>()
    val snapshots = mutableListOf<PipelineTraceTaskOverrides>()

    fun accept(line: String, number: Int) {
        val post = line.contains("Tasker::post_task]") || line.contains("[MaaTaskerPostTask]")
        if (!post && !line.contains("Context::create]")) return
        val process = processOf(line)
        val thread = threadOf(line)
        if (post && !line.contains("| leave")) {
            val entry = field(line, "entry") ?: return
            val raw = field(line, "pipeline_override")?.let(::normalize) ?: return
            val wrapper = line.contains("[MaaTaskerPostTask]")
            val previous = pending.lastOrNull()
            if (!wrapper && previous?.apiWrapper == true && previous.process == process &&
                previous.thread == thread && previous.entry == entry && previous.taskId == null) pending.removeLast()
            pending += Pending(process, thread, entry, number, raw, wrapper, field(line, "task_id")?.toLongOrNull())
        } else if (line.contains("Context::create]")) {
            val id = field(line, "id")?.toLongOrNull() ?: return
            pending.lastOrNull { it.process == process && it.thread == thread && it.taskId == null }?.taskId = id
        }
    }

    fun started(event: PipelineTraceEvent, line: String) {
        if (event.kind != PipelineTraceEventKind.TASK || event.status != PipelineTraceStatus.STARTING) return
        val taskId = event.taskId ?: return
        val entry = event.entryName ?: return
        val process = processOf(line)
        val exact = pending.filter { it.process == process && it.taskId == taskId && it.entry == entry }
        val candidates = if (exact.isNotEmpty()) exact else pending.filter {
            it.process == process && it.taskId == null && it.entry == entry
        }
        // 多个同名任务并发提交且缺少关联 ID 时不猜测，避免把另一轮运行的参数显示为事实。
        val source = candidates.singleOrNull() ?: return
        pending.remove(source)
        snapshots += PipelineTraceTaskOverrides(taskId, entry, event.lineNumber, source.line, source.raw)
    }

    private fun processOf(line: String): String = Regex("\\[P[xX]([^]]+)]").find(line)?.groupValues?.get(1).orEmpty()
    private fun threadOf(line: String): String = Regex("\\[T[xX]([^]]+)]").find(line)?.groupValues?.get(1).orEmpty()

    private fun normalize(raw: String): String? {
        return try {
        val value = JsonParser.parseString(raw)
        val result = when {
            value.isJsonNull -> JsonObject()
            value.isJsonObject -> value.asJsonObject
            value.isJsonArray -> {
                var merged = JsonObject()
                for (patch in value.asJsonArray) {
                    if (!patch.isJsonObject) return null
                    merged = PipelineTaskContext.merge(merged, patch).asJsonObject
                }
                merged
            }
            else -> return null
        }
        result.toString()
        } catch (_: com.google.gson.JsonParseException) { null }
    }

    private fun field(line: String, name: String): String? {
        val prefix = "[$name="
        val start = line.indexOf(prefix).takeIf { it >= 0 }?.plus(prefix.length) ?: return null
        if (line.getOrNull(start) !in setOf('{', '[')) return line.substring(start).substringBefore(']').trim()
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until line.length) {
            val character = line[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return line.substring(start, index + 1)
                }
            }
        }
        return null
    }
}
