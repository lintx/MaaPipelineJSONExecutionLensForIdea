package org.lintx.maapipeline.executionlens.pipeline

internal data class PipelineAnchorValue(
    val targets: Set<String> = emptySet(),
    val mayBeUnset: Boolean = false,
    val unknown: Boolean = false,
) {
    fun merge(other: PipelineAnchorValue): PipelineAnchorValue = PipelineAnchorValue(
        targets + other.targets, mayBeUnset || other.mayBeUnset, unknown || other.unknown,
    )
}

internal data class PipelineAnchorContext(val values: Map<String, PipelineAnchorValue> = emptyMap()) {
    fun value(name: String): PipelineAnchorValue = values[name] ?: PipelineAnchorValue(mayBeUnset = true, unknown = true)

    fun bind(name: String, target: String): PipelineAnchorContext = copy(values = values + (name to PipelineAnchorValue(setOf(target))))
}

internal data class PipelineAnchorResolution(
    val name: String,
    val value: PipelineAnchorValue,
    val targets: List<PipelineNodeLocation>,
) {
    val inlineTarget: PipelineNodeLocation?
        get() = targets.singleOrNull()?.takeIf { !value.unknown && value.targets.size == 1 }
    val isCleared: Boolean get() = !value.unknown && value.targets.isEmpty() && value.mayBeUnset

    fun hint(): String = buildString {
        append("Anchor $name；按当前显示路径推导")
        if (value.unknown) append("，包含未知运行状态")
        if (value.targets.isNotEmpty()) append(" → ${value.targets.joinToString("、")}")
        if (value.mayBeUnset) append("；未设置或清除时跳过此候选")
    }
}

internal class PipelineAnchorFlow(
    private val graph: PipelineReadingGraph,
    private val effective: (PipelineNodeLocation) -> PipelineNodeLocation,
) {
    private data class Effects(val writes: Map<String, PipelineAnchorValue>, val unknown: Boolean)
    private val effects = mutableMapOf<String, Effects>()

    fun afterNode(node: PipelineNodeLocation, incoming: PipelineAnchorContext): PipelineAnchorContext {
        val value = effective(node)
        if (!value.enabled) return PipelineAnchorContext()
        var context = beforeAction(node, incoming)
        if (opaque(value)) context = PipelineAnchorContext()
        else context = afterSubtasks(value.embeddedReferences.filter { it.kind == PipelineReferenceKind.SUBTASK }, context)
        // anchor 在动作完成后应用，当前节点的显式赋值覆盖此前调用产生的不确定状态。
        val definitions = value.anchorDefinitions.associate { definition -> definition.name to assigned(node, definition) }
        return context.copy(values = context.values + definitions)
    }

    fun forSubtask(node: PipelineNodeLocation, index: Int, incoming: PipelineAnchorContext): PipelineAnchorContext {
        val value = effective(node)
        val references = value.embeddedReferences.filter { it.kind == PipelineReferenceKind.SUBTASK }
        val parameters = PipelineVisualModel.operation(value, isAction = true).parameters["custom_action_param"]
        val random = parameters?.takeIf { it.isJsonObject }?.asJsonObject?.get("random_choice")
            ?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
        val previous = when {
            random == 1 -> emptyList()
            random > 1 -> references.filterIndexed { candidate, _ -> candidate != index }
            else -> references.take(index)
        }
        // 父动作尚未完成，不能提前应用父 Anchor；此前子调用的可能改写需要传给后续调用。
        return afterSubtasks(previous, beforeAction(node, incoming))
    }

    private fun beforeAction(node: PipelineNodeLocation, incoming: PipelineAnchorContext): PipelineAnchorContext {
        val value = effective(node)
        if (!value.enabled) return PipelineAnchorContext()
        var context = incoming
        if (value.recognition?.summary?.startsWith("Custom") == true) context = PipelineAnchorContext()
        else if (context.values.isNotEmpty() && (value.next + value.onError + value.embeddedReferences).any { it.isCycle }) {
            context = widen(context, effectsFrom(node))
        }
        return context
    }

    private fun afterSubtasks(references: List<PipelineReference>, incoming: PipelineAnchorContext): PipelineAnchorContext {
        var context = incoming
        references.forEach { reference ->
            val possible = targets(reference, context)
            if (possible.isEmpty() || reference.isAnchor && context.value(reference.targetName).unknown) return PipelineAnchorContext()
            possible.forEach { context = widen(context, effectsFrom(it)) }
        }
        return context
    }

    fun forBranch(references: List<PipelineReference>, field: String,
        incoming: PipelineAnchorContext): PipelineAnchorContext {
        if (field == "subtask") return incoming
        var context = incoming
        if (references.any { reference -> targets(reference, incoming).any { effective(it).recognition?.summary?.startsWith("Custom") == true } }) {
            context = PipelineAnchorContext()
        }
        if (field != "next") return context
        // JumpBack 返回后重试同一 next，父节点的 anchor 不会重新赋值；合并子链可能清除或改写的值。
        val returned = mutableSetOf<String>()
        val jumps = references.filter { it.isJumpBack }
        var changed = true
        while (changed) {
            changed = false
            for (reference in jumps) {
                val possible = targets(reference, context)
                val unresolved = if (reference.isAnchor) context.value(reference.targetName).unknown else possible.isEmpty()
                if (unresolved) return PipelineAnchorContext()
                possible.forEach { target ->
                    if (returned.add(PipelineReadingGraph.key(target))) {
                        context = widen(context, effectsFrom(target))
                        changed = true
                    }
                }
            }
        }
        return context
    }

    fun resolve(reference: PipelineReference, context: PipelineAnchorContext): PipelineAnchorResolution {
        val value = context.value(reference.targetName)
        return PipelineAnchorResolution(reference.targetName, value, targets(reference, context))
    }

    private fun targets(reference: PipelineReference, context: PipelineAnchorContext): List<PipelineNodeLocation> {
        if (!reference.isAnchor) return reference.target?.let { graph.node(it.file, it.name) }?.let(::listOf).orEmpty()
        val value = context.value(reference.targetName)
        val names = if (value.unknown) {
            value.targets + graph.anchorDefinitions(reference.targetName).mapNotNull { (owner, definition) ->
                if (definition.isCleared) null else definition.targetNodeName ?: owner.name
            }
        } else value.targets
        return names.mapNotNull(graph::uniqueNode)
    }

    private fun effectsFrom(root: PipelineNodeLocation): Effects = effects.getOrPut(PipelineReadingGraph.key(root)) {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<PipelineNodeLocation>()
        val writes = mutableMapOf<String, PipelineAnchorValue>()
        val adjacency = linkedMapOf<String, List<String>>()
        var unknown = false
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!visited.add(PipelineReadingGraph.key(node))) continue
            val value = effective(node)
            if (!value.enabled) continue
            if (opaque(value)) { unknown = true; break }
            value.anchorDefinitions.forEach { definition ->
                val write = assigned(node, definition)
                writes[definition.name] = writes[definition.name]?.merge(write) ?: write
            }
            val references = value.next + value.onError + value.embeddedReferences
            if (references.any { !it.isAnchor && !it.targetExists }) unknown = true
            if (references.any { it.isAnchor && graph.anchorDefinitions(it.targetName).isEmpty() }) unknown = true
            if (unknown) break
            val next = graph.successors(node).map { it.second }
            adjacency[PipelineReadingGraph.key(node)] = next.map(PipelineReadingGraph::key)
            next.forEach(pending::addLast)
        }
        val result = Effects(writes, unknown)
        if (!unknown) {
            // 同一循环分量的可达写入相同，共享汇总，避免长循环逐节点重复扫描。
            val components = PipelineGraphCycles.components(adjacency)
            val component = components[PipelineReadingGraph.key(root)]
            components.filterValues { it == component }.keys.forEach { effects[it] = result }
        }
        result
    }

    private fun widen(context: PipelineAnchorContext, effect: Effects): PipelineAnchorContext {
        if (effect.unknown) return PipelineAnchorContext()
        return context.copy(values = context.values + effect.writes.mapValues { (name, value) -> context.value(name).merge(value) })
    }

    private fun assigned(owner: PipelineNodeLocation, definition: PipelineAnchorDefinition): PipelineAnchorValue =
        if (definition.isCleared) PipelineAnchorValue(mayBeUnset = true)
        else PipelineAnchorValue(setOf(definition.targetNodeName ?: owner.name))

    private fun opaque(node: PipelineNodeLocation): Boolean =
        node.recognition?.summary?.startsWith("Custom") == true ||
            node.action?.summary?.startsWith("Custom") == true && node.embeddedReferences.none { it.kind == PipelineReferenceKind.SUBTASK }
}
