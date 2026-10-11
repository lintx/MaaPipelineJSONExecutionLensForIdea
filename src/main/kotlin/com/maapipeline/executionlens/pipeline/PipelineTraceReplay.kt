package com.maapipeline.executionlens.pipeline

internal data class PipelineTraceRound(
    val started: PipelineTraceEvent,
    val observations: List<PipelineTraceEvent>,
    val selected: PipelineTraceEvent?,
) {
    fun outcome(candidateName: String): PipelineTraceCandidateOutcome {
        if (started.candidates.none { it.name == candidateName }) return PipelineTraceCandidateOutcome.UNOBSERVED
        if (selected?.nodeName == candidateName) return PipelineTraceCandidateOutcome.SELECTED
        val recognition = observations.lastOrNull {
            it.kind == PipelineTraceEventKind.RECOGNITION && it.nodeName == candidateName &&
                it.status != PipelineTraceStatus.STARTING
        }
        return when (recognition?.status) {
            PipelineTraceStatus.FAILED -> PipelineTraceCandidateOutcome.MISSED
            PipelineTraceStatus.SUCCEEDED -> PipelineTraceCandidateOutcome.MATCHED
            else -> if (selected != null) PipelineTraceCandidateOutcome.SKIPPED else PipelineTraceCandidateOutcome.UNOBSERVED
        }
    }
}

internal fun pipelineTraceRounds(events: List<PipelineTraceEvent>): List<PipelineTraceRound> = buildList {
    events.groupBy { it.taskId }.values.forEach { taskEvents ->
        var started: PipelineTraceEvent? = null
        val observations = mutableListOf<PipelineTraceEvent>()
        fun finish(selected: PipelineTraceEvent? = null) {
            started?.let { add(PipelineTraceRound(it, observations.toList(), selected)) }
            started = null
            observations.clear()
        }
        taskEvents.forEach { event ->
            when {
                event.kind == PipelineTraceEventKind.TASK -> finish()
                event.kind == PipelineTraceEventKind.NEXT_LIST && event.status == PipelineTraceStatus.STARTING -> {
                    finish()
                    started = event
                }
                event.kind == PipelineTraceEventKind.PIPELINE_NODE && event.status == PipelineTraceStatus.STARTING -> {
                    val selected = event.takeIf { current -> started?.candidates?.any { it.name == current.nodeName } == true }
                    finish(selected)
                }
                started != null -> {
                    observations += event
                    if (event.kind == PipelineTraceEventKind.NEXT_LIST && event.status == PipelineTraceStatus.FAILED) finish()
                }
            }
        }
        finish()
    }
}.sortedBy { it.started.lineNumber }

internal data class PipelineTraceCall(
    val caller: String,
    val target: String,
    val taskId: Long?,
    val depth: Int,
    val entered: PipelineTraceEvent,
    val resumed: PipelineTraceEvent?,
)

internal data class PipelineTraceReplayStep(
    val event: PipelineTraceEvent,
    val depth: Int,
    val text: String,
    val nodeName: String? = event.nodeName,
)

internal data class PipelineTraceReplay(
    val steps: List<PipelineTraceReplayStep>,
    val calls: List<PipelineTraceCall>,
    val warnings: List<String>,
)

internal object PipelineTraceReplayBuilder {
    private data class PendingCall(
        val caller: String,
        val callerNodeId: Long?,
        val target: String,
        val taskId: Long?,
        val depth: Int,
        val entered: PipelineTraceEvent,
        var resumed: PipelineTraceEvent? = null,
    ) {
        fun snapshot() = PipelineTraceCall(caller, target, taskId, depth, entered, resumed)
    }

    fun build(events: List<PipelineTraceEvent>, rounds: List<PipelineTraceRound> = pipelineTraceRounds(events)): PipelineTraceReplay {
        val selectedRounds = rounds.mapNotNull { round -> round.selected?.let { it.lineNumber to round } }.toMap()
        val steps = mutableListOf<PipelineTraceReplayStep>()
        val calls = mutableListOf<PendingCall>()
        events.groupBy { it.taskId }.values.forEach { taskEvents ->
            val stack = mutableListOf<PendingCall>()
            taskEvents.forEach { event ->
                when {
                    event.kind == PipelineTraceEventKind.TASK -> {
                        steps += PipelineTraceReplayStep(event, 0, "任务 ${event.entryName ?: event.taskId ?: "未关联"}：${event.status.label}")
                        stack.clear()
                    }
                    event.kind == PipelineTraceEventKind.NEXT_LIST && event.status == PipelineTraceStatus.STARTING -> {
                        val matching = stack.indices.filter { index ->
                            val frame = stack[index]
                            frame.caller == event.nodeName &&
                                (stack.last().target != event.nodeName ||
                                    (frame.callerNodeId != null && event.nodeId != null)) &&
                                (frame.callerNodeId == null || event.nodeId == null || frame.callerNodeId == event.nodeId)
                        }
                        // MaaFramework 通知没有独立的返回事件；只根据父候选组再次开始推断返回，明确保留证据不足的情况。
                        if (matching.size == 1) {
                            val index = matching.single()
                            while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
                            val frame = stack.removeAt(index)
                            frame.resumed = event
                            steps += PipelineTraceReplayStep(event, frame.depth, "↩ 推断返回 ${frame.caller}：父候选组再次开始", frame.caller)
                        }
                    }
                    event.kind == PipelineTraceEventKind.PIPELINE_NODE && event.status == PipelineTraceStatus.STARTING -> {
                        val round = selectedRounds[event.lineNumber]
                        val candidate = round?.started?.candidates?.singleOrNull { it.name == event.nodeName }
                        val caller = round?.started?.nodeName
                        if (candidate?.isJumpBack == true && caller != null && event.nodeName != null) {
                            val frame = PendingCall(caller, round.started.nodeId, event.nodeName, event.taskId, stack.size, event)
                            stack += frame
                            calls += frame
                            steps += PipelineTraceReplayStep(event, frame.depth, "↩ 推断 JumpBack 进入：$caller → ${event.nodeName}")
                        }
                        steps += PipelineTraceReplayStep(event, stack.size, "执行 ${event.nodeName ?: "未命名节点"}")
                    }
                    event.kind == PipelineTraceEventKind.RECOGNITION && event.status != PipelineTraceStatus.STARTING -> {
                        val status = if (event.status == PipelineTraceStatus.SUCCEEDED) "命中" else "未命中"
                        steps += PipelineTraceReplayStep(event, stack.size, "识别 ${event.nodeName ?: "未命名候选"}：$status")
                    }
                    event.kind == PipelineTraceEventKind.ACTION && event.status != PipelineTraceStatus.STARTING -> {
                        steps += PipelineTraceReplayStep(event, stack.size, "动作 ${event.nodeName ?: "未命名节点"}：${event.status.label}")
                    }
                    event.kind == PipelineTraceEventKind.NEXT_LIST && event.status == PipelineTraceStatus.FAILED -> {
                        steps += PipelineTraceReplayStep(event, stack.size, "${event.nodeName ?: "未命名节点"} 候选组失败，日志未给出具体原因")
                    }
                }
                if (event.anchorName != null) {
                    steps += PipelineTraceReplayStep(event, stack.size,
                        "Anchor 观察：${event.anchorName} · 日志节点 ${event.nodeName ?: "未记录"}")
                }
            }
        }
        val snapshots = calls.map { it.snapshot() }
        val warnings = buildList {
            if (events.isNotEmpty() && events.groupBy { it.taskId }.values.any { task ->
                task.none { it.kind == PipelineTraceEventKind.TASK && it.status == PipelineTraceStatus.STARTING }
            }) add("部分日志缺少任务开始，轨迹可能不完整；没有 task_id 的事件单独处理。")
            val incomplete = snapshots.count { it.resumed == null }
            if (incomplete > 0) add("$incomplete 个 JumpBack 未观察到父候选组恢复，不能确认返回。")
            if (snapshots.isNotEmpty()) add("JumpBack 调用关系来自候选标记和节点事件推断，并非完整运行时调用栈。")
        }
        return PipelineTraceReplay(steps.sortedBy { it.event.lineNumber }, snapshots, warnings)
    }
}
