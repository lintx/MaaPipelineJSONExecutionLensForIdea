package com.maapipeline.executionlens.pipeline

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

internal enum class PipelineTraceEventKind(
    val label: String,
) {
    TASK("任务"),
    PIPELINE_NODE("节点"),
    NEXT_LIST("候选后继"),
    RECOGNITION("识别"),
    ACTION("动作"),
    WAIT_FREEZES("等待静止"),
}

internal enum class PipelineTraceStatus(
    val label: String,
) {
    STARTING("开始"),
    SUCCEEDED("成功"),
    FAILED("失败"),
}

internal data class PipelineTraceCandidate(
    val name: String,
    val isJumpBack: Boolean,
    val isAnchor: Boolean,
)

internal data class PipelineTraceEvent(
    val lineNumber: Int,
    val timestamp: String?,
    val message: String,
    val kind: PipelineTraceEventKind,
    val status: PipelineTraceStatus,
    val taskId: Long?,
    val entryName: String?,
    val nodeName: String?,
    val nodeId: Long?,
    val candidates: List<PipelineTraceCandidate> = emptyList(),
    val anchorName: String? = null,
)

internal class PipelineExecutionTrace(
    val file: VirtualFile,
    val events: List<PipelineTraceEvent>,
    val taskEntries: Map<Long, String>,
) {
    val nodeEvents: List<PipelineTraceEvent> = events.filter { it.kind == PipelineTraceEventKind.PIPELINE_NODE }

    val latestNodeStates: Map<String, PipelineTraceEvent> = nodeEvents
        .filter { it.nodeName != null }
        .associateBy { it.nodeName!! }

    val executedNodeNames: List<String> = nodeEvents
        .asSequence()
        .filter { it.status == PipelineTraceStatus.STARTING }
        .mapNotNull { it.nodeName }
        .distinct()
        .toList()

    fun eventsForNode(nodeName: String): List<PipelineTraceEvent> = events.filter { it.nodeName == nodeName }
}

internal data class PipelineTraceParseResult(
    val events: List<PipelineTraceEvent>,
    val taskEntries: Map<Long, String>,
)

internal object PipelineTraceParser {
    private const val messagePrefix = "[msg="
    private const val fallbackMessagePrefix = "[message="
    private const val detailsPrefix = "[details="
    private const val fallbackDetailsPrefix = "[details_json="

    fun parse(file: VirtualFile): PipelineExecutionTrace {
        val parsed = file.inputStream.use { input ->
            parseLines(input.bufferedReader(StandardCharsets.UTF_8).lineSequence())
        }
        return PipelineExecutionTrace(file, parsed.events, parsed.taskEntries)
    }

    internal fun parseText(text: String): PipelineTraceParseResult = parseLines(text.lineSequence())

    private fun parseLines(lines: Sequence<String>): PipelineTraceParseResult {
        val events = mutableListOf<PipelineTraceEvent>()
        val taskEntries = mutableMapOf<Long, String>()
        var lineNumber = 0
        lines.forEach { line ->
            lineNumber++
            parseLine(line, lineNumber, events, taskEntries)
        }
        return PipelineTraceParseResult(events, taskEntries)
    }

    private fun parseLine(
        line: String,
        lineNumber: Int,
        events: MutableList<PipelineTraceEvent>,
        taskEntries: MutableMap<Long, String>,
    ) {
        val messageStart = line.indexOf(messagePrefix).takeIf { it >= 0 }
            ?: line.indexOf(fallbackMessagePrefix)
        if (messageStart < 0) return
        val actualMessagePrefix = if (line.startsWith(messagePrefix, messageStart)) messagePrefix else fallbackMessagePrefix
        val messageEnd = line.indexOf(']', messageStart + actualMessagePrefix.length)
        if (messageEnd < 0) return
        val message = line.substring(messageStart + actualMessagePrefix.length, messageEnd)
        val status = statusOf(message) ?: return
        val kind = kindOf(message) ?: return
        val detailsStart = line.indexOf(detailsPrefix, messageEnd).takeIf { it >= 0 }
            ?: line.indexOf(fallbackDetailsPrefix, messageEnd)
        if (detailsStart < 0) return
        val actualDetailsPrefix = if (line.startsWith(detailsPrefix, detailsStart)) detailsPrefix else fallbackDetailsPrefix
        val jsonStart = line.indexOf('{', detailsStart + actualDetailsPrefix.length)
        if (jsonStart < 0) return
        val jsonEnd = findJsonEnd(line, jsonStart) ?: return
        val details = runCatching {
            JsonParser.parseString(line.substring(jsonStart, jsonEnd + 1)).asJsonObject
        }.getOrNull() ?: return

        val taskId = details.longValue("task_id")
        val entryName = details.stringValue("entry")
        if (kind == PipelineTraceEventKind.TASK && taskId != null && entryName != null) {
            taskEntries[taskId] = entryName
        }
        val nodeName = details.stringValue("name")
        val candidates = if (kind == PipelineTraceEventKind.NEXT_LIST) {
            details.getAsJsonArray("list")?.mapNotNull { it.asJsonObject.toCandidate() }.orEmpty()
        } else {
            emptyList()
        }
        val event = PipelineTraceEvent(
            lineNumber = lineNumber,
            timestamp = line.substringAfter('[', "").substringBefore(']').takeIf(String::isNotEmpty),
            message = message,
            kind = kind,
            status = status,
            taskId = taskId,
            entryName = entryName ?: taskId?.let(taskEntries::get),
            nodeName = nodeName,
            nodeId = details.longValue("node_id"),
            candidates = candidates,
            anchorName = details.stringValue("anchor"),
        )
        if (events.lastOrNull()?.sameEvent(event) != true) {
            events += event
        }
    }

    private fun kindOf(message: String): PipelineTraceEventKind? = when {
        message.startsWith("Tasker.Task.") -> PipelineTraceEventKind.TASK
        message.startsWith("Node.PipelineNode.") -> PipelineTraceEventKind.PIPELINE_NODE
        message.startsWith("Node.NextList.") -> PipelineTraceEventKind.NEXT_LIST
        message.startsWith("Node.Recognition.") -> PipelineTraceEventKind.RECOGNITION
        message.startsWith("Node.Action.") -> PipelineTraceEventKind.ACTION
        message.startsWith("Node.WaitFreezes.") -> PipelineTraceEventKind.WAIT_FREEZES
        else -> null
    }

    private fun statusOf(message: String): PipelineTraceStatus? = when {
        message.endsWith(".Starting") -> PipelineTraceStatus.STARTING
        message.endsWith(".Succeeded") -> PipelineTraceStatus.SUCCEEDED
        message.endsWith(".Failed") -> PipelineTraceStatus.FAILED
        else -> null
    }

    private fun findJsonEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun JsonObject.toCandidate(): PipelineTraceCandidate? {
        val name = stringValue("name") ?: return null
        return PipelineTraceCandidate(
            name = name,
            isJumpBack = booleanValue("jump_back"),
            isAnchor = booleanValue("anchor"),
        )
    }

    private fun JsonObject.stringValue(name: String): String? = get(name)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.takeIf { it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.longValue(name: String): Long? = get(name)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.let { runCatching { it.asLong }.getOrNull() }

    private fun JsonObject.booleanValue(name: String): Boolean = get(name)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.let { runCatching { it.asBoolean }.getOrDefault(false) }
        ?: false

    private fun PipelineTraceEvent.sameEvent(other: PipelineTraceEvent): Boolean =
        message == other.message &&
            status == other.status &&
            taskId == other.taskId &&
            nodeName == other.nodeName &&
            nodeId == other.nodeId &&
            candidates == other.candidates
}
