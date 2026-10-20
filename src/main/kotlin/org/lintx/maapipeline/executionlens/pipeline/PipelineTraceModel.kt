package org.lintx.maapipeline.executionlens.pipeline

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
    NEXT_LIST("next 候选"),
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

internal enum class PipelineTraceCandidateOutcome(
    val label: String,
) {
    SELECTED("本次运行选择"),
    SKIPPED("本次运行未选择"),
    MISSED("本轮识别未命中"),
    MATCHED("本轮识别命中，执行未观察到"),
    UNOBSERVED("本次运行未观察到"),
}

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
    val processId: String? = null,
)

internal class PipelineExecutionTrace(
    val file: VirtualFile,
    val events: List<PipelineTraceEvent>,
    val taskEntries: Map<Long, String>,
    val taskOverrides: List<PipelineTraceTaskOverrides> = emptyList(),
) {
    private val scopedEvents = mutableMapOf<String?, List<PipelineTraceEvent>>()
    private val scopedNodeStates = mutableMapOf<String?, Map<String, PipelineTraceEvent>>()
    private val scopedRounds = mutableMapOf<String?, List<PipelineTraceRound>>()
    private val scopedReplays = mutableMapOf<String?, PipelineTraceReplay>()
    private val scopedPlayback = mutableMapOf<String?, PipelineLogPlaybackSource>()
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

    fun overridesForEntry(entryName: String?): PipelineTraceTaskOverrides? {
        if (entryName == null) return null
        val start = events.lastOrNull { it.kind == PipelineTraceEventKind.TASK &&
            it.status == PipelineTraceStatus.STARTING && it.entryName == entryName } ?: return null
        return taskOverrides.singleOrNull { it.startLineNumber == start.lineNumber && it.taskId == start.taskId }
    }

    fun eventsForEntry(entryName: String?): List<PipelineTraceEvent> = scopedEvents.getOrPut(entryName) {
        pipelineTraceEventsForEntry(events, taskEntries, entryName)
    }

    fun nodeStatesForEntry(entryName: String?): Map<String, PipelineTraceEvent> = scopedNodeStates.getOrPut(entryName) {
        eventsForEntry(entryName).filter { it.kind == PipelineTraceEventKind.PIPELINE_NODE && it.nodeName != null }
            .associateBy { it.nodeName!! }
    }

    fun roundsForEntry(entryName: String?): List<PipelineTraceRound> = scopedRounds.getOrPut(entryName) {
        pipelineTraceRounds(eventsForEntry(entryName))
    }

    fun replayForEntry(entryName: String?): PipelineTraceReplay = scopedReplays.getOrPut(entryName) {
        PipelineTraceReplayBuilder.build(eventsForEntry(entryName), roundsForEntry(entryName))
    }

    fun playbackForEntry(entryName: String?): PipelineLogPlaybackSource = scopedPlayback.getOrPut(entryName) {
        PipelineLogPlaybackSource(eventsForEntry(entryName), roundsForEntry(entryName), replayForEntry(entryName))
    }

    fun latestNextListForNode(nodeName: String, taskId: Long? = null): PipelineTraceEvent? = events
        .asSequence()
        .filter {
            it.kind == PipelineTraceEventKind.NEXT_LIST &&
                it.nodeName == nodeName &&
                (taskId == null || it.taskId == taskId)
        }
        .lastOrNull()

    fun candidateOutcome(
        nodeName: String,
        candidateName: String,
        taskId: Long? = null,
    ): PipelineTraceCandidateOutcome {
        return pipelineTraceCandidateOutcome(events, nodeName, candidateName, taskId)
    }

    fun candidateOutcomeForEntry(
        nodeName: String,
        candidateName: String,
        entryName: String?,
    ): PipelineTraceCandidateOutcome {
        return roundsForEntry(entryName).lastOrNull { it.started.nodeName == nodeName }?.outcome(candidateName)
            ?: PipelineTraceCandidateOutcome.UNOBSERVED
    }
}

internal fun pipelineTraceEventsForEntry(
    events: List<PipelineTraceEvent>,
    taskEntries: Map<Long, String>,
    entryName: String?,
): List<PipelineTraceEvent> {
    if (entryName == null) return events
    val latestTaskId = pipelineTraceLatestTaskIds(events, taskEntries)[entryName] ?: return emptyList()
    val start = events.lastOrNull { it.taskId == latestTaskId && it.entryName == entryName &&
        it.kind == PipelineTraceEventKind.TASK && it.status == PipelineTraceStatus.STARTING }
    // 同名任务可能运行多次，候选和节点状态必须来自同一个 task_id，不能拼成一条虚假的路径。
    return events.filter { it.taskId == latestTaskId && (start == null ||
        it.lineNumber >= start.lineNumber && (start.processId == null || it.processId == start.processId)) }
}

private fun pipelineTraceLatestTaskIds(events: List<PipelineTraceEvent>, taskEntries: Map<Long, String>): Map<String, Long> {
    val latestEvents = mutableMapOf<String, Long>()
    val latestStarts = mutableMapOf<String, Long>()
    events.forEach { event ->
        val taskId = event.taskId ?: return@forEach
        val entry = taskEntries[taskId] ?: return@forEach
        latestEvents[entry] = taskId
        if (event.kind == PipelineTraceEventKind.TASK && event.status == PipelineTraceStatus.STARTING) latestStarts[entry] = taskId
    }
    return latestEvents + latestStarts
}

internal fun pipelineTraceCandidateOutcome(
    events: List<PipelineTraceEvent>,
    nodeName: String,
    candidateName: String,
    taskId: Long? = null,
): PipelineTraceCandidateOutcome {
    val scoped = if (taskId == null) events else events.filter { it.taskId == taskId }
    return pipelineTraceRounds(scoped).lastOrNull { it.started.nodeName == nodeName }?.outcome(candidateName)
        ?: PipelineTraceCandidateOutcome.UNOBSERVED
}

internal data class PipelineTraceParseResult(
    val events: List<PipelineTraceEvent>,
    val taskEntries: Map<Long, String>,
    val taskOverrides: List<PipelineTraceTaskOverrides> = emptyList(),
)

internal object PipelineTraceParser {
    private val processPattern = Regex("\\[P[xX]([^]]+)]")
    private const val messagePrefix = "[msg="
    private const val fallbackMessagePrefix = "[message="
    private const val detailsPrefix = "[details="
    private const val fallbackDetailsPrefix = "[details_json="

    fun parse(file: VirtualFile, checkCanceled: () -> Unit = {}): PipelineExecutionTrace {
        val parsed = file.inputStream.use { input ->
            parseLines(input.bufferedReader(StandardCharsets.UTF_8).lineSequence(), checkCanceled)
        }
        return PipelineExecutionTrace(file, parsed.events, parsed.taskEntries, parsed.taskOverrides)
    }

    internal fun parseText(text: String): PipelineTraceParseResult = parseLines(text.lineSequence())

    private fun parseLines(lines: Sequence<String>, checkCanceled: () -> Unit = {}): PipelineTraceParseResult {
        checkCanceled()
        val events = mutableListOf<PipelineTraceEvent>()
        val taskEntries = mutableMapOf<Long, String>()
        val overrides = PipelineTraceOverrideCollector()
        var lineNumber = 0
        lines.forEach { line ->
            lineNumber++
            if (lineNumber % 1000 == 0) checkCanceled()
            overrides.accept(line, lineNumber)
            val size = events.size
            parseLine(line, lineNumber, events, taskEntries)
            if (events.size != size) overrides.started(events.last(), line)
        }
        checkCanceled()
        return PipelineTraceParseResult(events, taskEntries, overrides.snapshots)
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
            details.getAsJsonArray("list")?.mapNotNull { it.toCandidate() }.orEmpty()
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
            processId = processPattern.find(line)?.groupValues?.get(1),
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

    private fun JsonElement.toCandidate(): PipelineTraceCandidate? {
        if (isJsonPrimitive && asJsonPrimitive.isString) {
            return traceCandidate(asString, isJumpBack = false, isAnchor = false)
        }
        val objectValue = takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        val name = objectValue.stringValue("name") ?: return null
        return traceCandidate(
            rawName = name,
            isJumpBack = objectValue.booleanValue("jump_back"),
            isAnchor = objectValue.booleanValue("anchor"),
        )
    }

    private fun traceCandidate(rawName: String, isJumpBack: Boolean, isAnchor: Boolean): PipelineTraceCandidate {
        var name = rawName
        var jumpBack = isJumpBack
        var anchor = isAnchor
        while (name.startsWith("[")) {
            when {
                name.startsWith("[JumpBack]") -> { jumpBack = true; name = name.removePrefix("[JumpBack]") }
                name.startsWith("[Anchor]") -> { anchor = true; name = name.removePrefix("[Anchor]") }
                else -> break
            }
        }
        return PipelineTraceCandidate(name, jumpBack, anchor)
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
            timestamp == other.timestamp &&
            status == other.status &&
            taskId == other.taskId &&
            nodeName == other.nodeName &&
            nodeId == other.nodeId &&
            candidates == other.candidates &&
            anchorName == other.anchorName &&
            processId == other.processId
}
