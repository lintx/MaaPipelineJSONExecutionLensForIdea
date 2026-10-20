package org.lintx.maapipeline.executionlens.pipeline

internal enum class PipelinePlaybackGranularity(val label: String) {
    NODE("节点"), EVENT("详细事件");
    override fun toString(): String = label
}

internal data class PipelinePlaybackFrame(
    val text: String,
    val timestamp: String?,
    val nodeName: String?,
    val lineNumber: Int?,
)

internal interface PipelinePlaybackSource {
    val canSeekBackward: Boolean
    val canAutoplay: Boolean
    fun positions(granularity: PipelinePlaybackGranularity): List<Int>
    fun frame(eventIndex: Int): PipelinePlaybackFrame?
}

internal class PipelinePlaybackCursor(val source: PipelinePlaybackSource) {
    var granularity = PipelinePlaybackGranularity.NODE
        private set
    var eventIndex: Int = source.positions(granularity).firstOrNull() ?: -1
        private set
    val positions: List<Int> get() = source.positions(granularity)
    val size: Int get() = positions.size
    val position: Int get() = positions.binarySearch(eventIndex).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
    val frame: PipelinePlaybackFrame? get() = source.frame(eventIndex)
    val previousPosition: Int get() = if (positions.getOrNull(position) == eventIndex) position - 1 else position
    val canPrevious: Boolean get() = source.canSeekBackward && previousPosition >= 0 && size > 0
    val canNext: Boolean get() = position < size - 1

    fun seek(position: Int): Boolean {
        val target = positions.getOrNull(position) ?: return false
        if (target == eventIndex || target < eventIndex && !source.canSeekBackward) return false
        eventIndex = target
        return true
    }

    fun changeGranularity(value: PipelinePlaybackGranularity) {
        if (value == granularity) return
        granularity = value
        // 粒度改变时不前进到尚未观察的位置，也不丢失当前事件的时间边界。
    }
}

internal class PipelineLogPlaybackSource(
    val events: List<PipelineTraceEvent>,
    private val rounds: List<PipelineTraceRound>,
    private val replay: PipelineTraceReplay,
) : PipelinePlaybackSource {
    override val canSeekBackward = true
    override val canAutoplay = true
    private val allPositions = events.indices.toList()
    private val nodePositions = events.indices.filter { index ->
        events[index].kind == PipelineTraceEventKind.TASK ||
            events[index].kind == PipelineTraceEventKind.PIPELINE_NODE && events[index].status == PipelineTraceStatus.STARTING
    }.let { positions ->
        (positions + listOfNotNull(events.indices.firstOrNull(), events.indices.lastOrNull())).distinct().sorted()
    }
    private val replayByLine = replay.steps.groupBy { it.event.lineNumber }
    private val nodeEvents = events.filter { it.kind == PipelineTraceEventKind.PIPELINE_NODE }.groupBy { it.nodeName }
    private val eventsByNode = events.groupBy { it.nodeName }
    private val roundsByNode = rounds.groupBy { it.started.nodeName }
    private val callsByNode = replay.calls.flatMap { call -> listOf(call.caller to call, call.target to call) }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }

    override fun positions(granularity: PipelinePlaybackGranularity): List<Int> =
        if (granularity == PipelinePlaybackGranularity.NODE) nodePositions else allPositions

    override fun frame(eventIndex: Int): PipelinePlaybackFrame? = events.getOrNull(eventIndex)?.let { event ->
        PipelinePlaybackFrame(
            replayByLine[event.lineNumber]?.map { it.text }?.distinct()?.joinToString(" · ")
                ?: "${event.kind.label} ${event.nodeName ?: event.entryName.orEmpty()}：${event.status.label}",
            event.timestamp, event.nodeName, event.lineNumber,
        )
    }

    fun lineAt(eventIndex: Int): Int = events.getOrNull(eventIndex)?.lineNumber ?: 0

    fun eventsThrough(eventIndex: Int): List<PipelineTraceEvent> = events.subList(0, (eventIndex + 1).coerceIn(0, events.size))

    fun nodeState(nodeName: String, eventIndex: Int): PipelineTraceEvent? =
        nodeEvents[nodeName].orEmpty().prefix(lineAt(eventIndex)) { it.lineNumber }.lastOrNull()

    fun nodeEventsThrough(nodeName: String, eventIndex: Int): List<PipelineTraceEvent> =
        eventsByNode[nodeName].orEmpty().prefix(lineAt(eventIndex)) { it.lineNumber }

    private fun clip(round: PipelineTraceRound, line: Int): PipelineTraceRound = round.copy(
        observations = round.observations.prefix(line) { it.lineNumber },
        selected = round.selected?.takeIf { it.lineNumber <= line },
    )

    fun roundsThrough(nodeName: String, eventIndex: Int): List<PipelineTraceRound> {
        val line = lineAt(eventIndex)
        return roundsByNode[nodeName].orEmpty().prefix(line) { it.started.lineNumber }.takeLast(6).map { clip(it, line) }
    }

    fun outcome(nodeName: String, candidate: String, eventIndex: Int): PipelineTraceCandidateOutcome {
        val line = lineAt(eventIndex)
        val round = roundsByNode[nodeName].orEmpty().prefix(line) { it.started.lineNumber }.lastOrNull()
            ?: return PipelineTraceCandidateOutcome.UNOBSERVED
        return clip(round, line).outcome(candidate)
    }

    fun callsThrough(nodeName: String, eventIndex: Int): List<PipelineTraceCall> {
        val line = lineAt(eventIndex)
        return callsByNode[nodeName].orEmpty().prefix(line) { it.entered.lineNumber }.takeLast(6).map { call ->
            call.copy(resumed = call.resumed?.takeIf { it.lineNumber <= line })
        }
    }

    private fun <Value> List<Value>.prefix(line: Int, lineNumber: (Value) -> Int): List<Value> {
        var lower = 0
        var upper = size
        while (lower < upper) {
            val middle = (lower + upper) ushr 1
            if (lineNumber(this[middle]) <= line) lower = middle + 1 else upper = middle
        }
        return subList(0, lower)
    }
}
