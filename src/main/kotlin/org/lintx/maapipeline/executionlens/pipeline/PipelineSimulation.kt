package org.lintx.maapipeline.executionlens.pipeline

internal data class PipelineEnvironment(val id: String, val label: String, val controller: String) {
    override fun toString() = label
    companion object {
        fun selected(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile): PipelineEnvironment {
            val configured = available(PipelineProjectFiles.manifest(project, file)?.let(PipelineProjectFiles::text))
            val workspace = PipelineWorkspaceState.getInstance(project)
            val id = workspace.reading(workspace.data.lastContext).environmentId
            return configured.firstOrNull { it.id.equals(id, true) }
                ?: configured.firstOrNull { it.controller.equals(id, true) } ?: configured.first()
        }

        fun available(manifest: String?): List<PipelineEnvironment> {
            val root = manifest?.let(PipelineVisualModel::parse)
            val configured = root?.get("controller")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val type = item.asJsonObject.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val name = item.asJsonObject.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: type
                PipelineEnvironment(name, name, type)
            }.orEmpty()
            return configured.ifEmpty { listOf(PipelineEnvironment("Win32", "Win32", "Win32"), PipelineEnvironment("Adb", "ADB", "Adb"),
                PipelineEnvironment("PlayCover", "PlayCover", "PlayCover"), PipelineEnvironment("WlRoots", "WlRoots", "WlRoots")) }
        }
    }
}

internal enum class PipelineSimulationParentOutcome(val label: String) {
    CONTINUE("继续剩余子任务"), SUCCEEDED("父动作成功，继续 next"), FAILED("父动作失败，进入 on_error");
    override fun toString() = label
}

internal data class PipelineSimulationChoice(val reference: PipelineReference, val node: PipelineNodeLocation?, val kind: String, val unavailable: String? = null) {
    override fun toString() = "$kind → ${node?.name ?: reference.targetName}" +
        (if (reference.isAnchor) " · Anchor ${reference.targetName}" else "") + unavailable?.let { "（$it）" }.orEmpty()
}

internal data class PipelineSimulationReturn(val node: PipelineNodeLocation, val subtask: Boolean,
    val remaining: List<PipelineReference> = emptyList(), val remainingCount: Int = 0)

internal data class PipelineSimulationFrame(val node: PipelineNodeLocation, val anchors: Map<String, String>,
    val results: Map<String, List<Int>>, val targets: Map<String, List<Int>?>,
    val returns: List<PipelineSimulationReturn>, val pending: List<PipelineReference>, val text: String, val ended: Boolean = false,
    val pendingCount: Int = 0, val failed: Boolean = false, val hits: Map<String, Long> = emptyMap())

internal class PipelineSimulation(private val nodes: List<PipelineNodeLocation>, val environment: PipelineEnvironment,
    private val defaults: (PipelineNodeLocation) -> com.google.gson.JsonObject = { com.google.gson.JsonObject() }) : PipelinePlaybackSource {
    private val byName = nodes.groupBy { it.name }
    private val properties = mutableMapOf<PipelineNodeLocation, List<PipelinePropertyValue>>()
    private val operations = mutableMapOf<Pair<PipelineNodeLocation, Boolean>, PipelineOperation>()
    val frames = mutableListOf<PipelineSimulationFrame>()
    override val canSeekBackward = true
    override val canAutoplay = false
    override fun positions(granularity: PipelinePlaybackGranularity) = frames.indices.toList()
    override fun frame(eventIndex: Int): PipelinePlaybackFrame? = frames.getOrNull(eventIndex)?.let {
        PipelinePlaybackFrame(it.text, null, it.node.name, null)
    }
    fun start(root: PipelineNodeLocation) { frames.clear(); frames += enter(root, emptyMap(), emptyMap(), emptyList(), "开始模拟 · ${environment.label}") }

    private fun find(name: String): PipelineNodeLocation? = byName[name]?.singleOrNull()
    private fun property(node: PipelineNodeLocation, name: String): com.google.gson.JsonElement? =
        properties.getOrPut(node) { PipelinePropertyCatalog.properties(node, defaults(node)) }.firstOrNull { it.name == name }?.value

    private fun operation(node: PipelineNodeLocation, isAction: Boolean = false): PipelineOperation =
        operations.getOrPut(node to isAction) {
            val resource = defaults(node)
            fun configured(value: PipelineOperation): PipelineOperation = value.copy(
                parameters = PipelinePropertyCatalog.operationProperties(value, resource).mapNotNull { item ->
                    item.value?.let { item.name to it }
                }.toMap(), children = value.children.map(::configured))
            configured(PipelineVisualModel.operation(node, isAction, ::find))
        }
    private fun target(reference: PipelineReference, frame: PipelineSimulationFrame): PipelineNodeLocation? =
        if (reference.isAnchor) frame.anchors[reference.targetName]?.let(::find) else reference.target?.let { actual ->
            nodes.singleOrNull { it.name == actual.name && it.sourceFile == actual.file }
        } ?: find(reference.targetName)

    private fun choice(reference: PipelineReference, frame: PipelineSimulationFrame, kind: String): PipelineSimulationChoice {
        val node = target(reference, frame)
        val limit = node?.let { property(it, "max_hit") }?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull() ?: 4294967295L
        val hits = frame.hits[node?.name] ?: 0
        val reason = when {
            node == null -> "未绑定或未解析"
            property(node, "enabled")?.toString() == "false" -> "enabled=false"
            hits >= limit -> "已达 max_hit=$limit"
            else -> null
        }
        return PipelineSimulationChoice(reference, node.takeIf { reason == null }, kind, reason)
    }

    fun choices(index: Int): List<PipelineSimulationChoice> {
        val current = frames.getOrNull(index) ?: return emptyList()
        if (current.ended || current.failed) return emptyList()
        val references = if (current.pending.isNotEmpty()) {
            val operation = operation(current.node, true)
            val random = operation.parameters["custom_action_param"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("random_choice")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
            if (random > 0) current.pending else current.pending.take(1)
        } else current.node.next
        return references.map { choice(it, current, if (current.pending.isNotEmpty()) "subtask" else "next") }
    }

    fun errors(index: Int): List<PipelineSimulationChoice> = frames.getOrNull(index)?.let { frame ->
        if (frame.ended) emptyList() else frame.node.onError.map { choice(it, frame, "on_error") }
    }.orEmpty()

    fun advance(index: Int, choice: PipelineSimulationChoice): Boolean {
        val current = frames.getOrNull(index) ?: return false
        val next = choice.node ?: return false
        if (choice !in choices(index) && choice !in errors(index)) return false
        while (frames.size > index + 1) frames.removeAt(frames.lastIndex)
        var returns = current.returns
        var anchors = current.anchors
        if (choice.kind == "subtask") {
            val count = (current.pendingCount - 1).coerceAtLeast(0)
            // RunTask 只隔离返回栈；共享任务状态继续传递，以不可变快照保留回退历史。
            returns = returns + PipelineSimulationReturn(current.node, true,
                if (count == 0) emptyList() else current.pending - choice.reference, count)
        } else if (choice.kind == "on_error" && current.pending.isNotEmpty()) {
            anchors = applyAnchors(current.node, anchors)
        } else if (choice.kind == "next" && choice.reference.isJumpBack) {
            returns = returns + PipelineSimulationReturn(current.node, false)
        }
        frames += enter(next, anchors, current.results, returns, "${choice.kind} · ${current.node.name} → ${next.name}", current.hits)
        return true
    }

    fun requiresParentOutcome(index: Int): Boolean = frames.getOrNull(index)?.let {
        !it.ended && it.node.onError.isEmpty() && it.returns.any { continuation -> continuation.subtask }
    } == true

    fun completeReturns() {
        // 子流程末端的返回不需要用户再次选择；保留每个返回帧，仍可回放完整执行链。
        while (true) {
            val frame = frames.lastOrNull() ?: return
            if (frame.ended || frame.failed || frame.pending.isNotEmpty() || frame.node.next.isNotEmpty() || frame.returns.isEmpty()) return
            if (!finish(frames.lastIndex)) return
        }
    }

    fun finish(index: Int, error: Boolean = false, outcome: PipelineSimulationParentOutcome? = null): Boolean {
        val current = frames.getOrNull(index) ?: return false
        if (current.ended || !error && (current.failed || current.pending.isNotEmpty() || current.node.next.isNotEmpty()) || error && current.node.onError.isNotEmpty()) return false
        val continuation = if (error) current.returns.lastOrNull { it.subtask } else current.returns.lastOrNull()
        if (error && continuation != null && outcome == null) return false
        while (frames.size > index + 1) frames.removeAt(frames.lastIndex)
        if (error && continuation?.subtask == true) {
            val remainingReturns = current.returns.take(current.returns.indexOf(continuation))
            // 不执行自定义动作；子任务失败后由用户指定父动作结果，不能猜测 continue/strict 的实现。
            if (outcome != PipelineSimulationParentOutcome.FAILED) {
                val remaining = if (outcome == PipelineSimulationParentOutcome.CONTINUE) continuation.remaining else emptyList()
                val anchors = if (remaining.isEmpty()) applyAnchors(continuation.node, current.anchors) else current.anchors
                frames += PipelineSimulationFrame(continuation.node, anchors,
                    current.results, targets(continuation.node, current.results, anchors),
                    remainingReturns, remaining, "子任务失败 · ${outcome?.label}", pendingCount = if (remaining.isEmpty()) 0 else continuation.remainingCount,
                    hits = current.hits)
                return true
            }
            frames += PipelineSimulationFrame(continuation.node, applyAnchors(continuation.node, current.anchors),
                current.results, emptyMap(), remainingReturns, emptyList(),
                "子任务失败 · 父动作失败，请选择 on_error", failed = true, hits = current.hits)
            return true
        }
        if (continuation == null || error) {
            frames += current.copy(ended = true, text = if (error) "模拟结束 · 失败且无 on_error" else "模拟结束")
            return true
        }
        if (continuation.subtask) {
            var anchors = current.anchors
            if (continuation.remaining.isEmpty()) anchors = applyAnchors(continuation.node, anchors)
            frames += PipelineSimulationFrame(continuation.node, anchors, current.results,
                targets(continuation.node, current.results, anchors), current.returns.dropLast(1), continuation.remaining,
                "subtask 结束 · 继续 ${continuation.node.name}", pendingCount = continuation.remainingCount, hits = current.hits)
        } else {
            frames += current.copy(node = continuation.node, returns = current.returns.dropLast(1), pending = emptyList(),
                targets = targets(continuation.node, current.results, current.anchors), text = "JumpBack · 从 ${continuation.node.name} 的 next 第一项重新尝试")
        }
        return true
    }

    private fun enter(node: PipelineNodeLocation, incoming: Map<String, String>, previous: Map<String, List<Int>>,
        returns: List<PipelineSimulationReturn>, text: String, hits: Map<String, Long> = emptyMap()): PipelineSimulationFrame {
        val recognition = operation(node)
        fun example(operation: PipelineOperation): List<Int>? {
            operation.roiHints["roi"]?.let { return PipelineVisualModel.region(operation, "roi", ::find).coordinates }
            if (operation.type == "And") return operation.children.getOrNull(operation.text("box_index")?.toIntOrNull() ?: 0)?.let(::example)
            return null
        }
        val result = example(recognition)
        val results = previous - node.name + listOfNotNull(result?.let { node.name to it }).toMap()
        val pending = node.embeddedReferences.filter { it.kind == PipelineReferenceKind.SUBTASK }
        val random = operation(node, true).parameters["custom_action_param"]
            ?.takeIf { it.isJsonObject }?.asJsonObject?.get("random_choice")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
        val count = if (random > 0) minOf(random, pending.size) else pending.size
        val anchors = if (pending.isEmpty()) applyAnchors(node, incoming) else incoming
        return PipelineSimulationFrame(node, anchors, results, targets(node, results, incoming), returns, pending, text,
            pendingCount = count, hits = hits + (node.name to ((hits[node.name] ?: 0) + 1)))
    }

    private fun applyAnchors(node: PipelineNodeLocation, previous: Map<String, String>): Map<String, String> = previous.toMutableMap().apply {
        node.anchorDefinitions.forEach { if (it.isCleared) remove(it.name) else put(it.name, it.targetNodeName ?: node.name) }
    }

    private fun targets(node: PipelineNodeLocation, results: Map<String, List<Int>>, anchors: Map<String, String>): Map<String, List<Int>?> {
        val operation = operation(node, true)
        val keys = when (operation.type) { "Swipe" -> listOf("begin", "end"); "Click", "LongPress", "TouchDown", "TouchMove", "Scroll", "Custom" -> listOf("target"); else -> emptyList() }
        return keys.associateWith { key ->
            val direct = PipelineVisualModel.region(operation, key, ::find).coordinates
            if (direct != null) direct else {
                val value = operation.parameters[key]
                val name = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: node.name
                val result = results[if (name.startsWith("[Anchor]")) anchors[name.removePrefix("[Anchor]")] else name]
                val offset = PipelineVisualModel.integers(operation.parameters["${key}_offset"])
                if (result != null && offset?.size == 4) result.zip(offset) { base, delta -> base + delta } else result
            }
        }
    }
}
