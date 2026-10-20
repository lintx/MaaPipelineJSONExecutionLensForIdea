package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.vfs.VirtualFile

internal class PipelineReadingGraph(
    nodes: List<PipelineNodeLocation>,
    private val effective: (PipelineNodeLocation) -> PipelineNodeLocation = { it },
) {
    private val byKey = nodes.associateBy(::key)
    private val byName = nodes.groupBy { it.name }
    private var cachedRoot: String? = null
    private var cachedParents: Map<String, Pair<String, String>?> = emptyMap()
    val anchorFlow by lazy { PipelineAnchorFlow(this, effective) }
    private val referencedAnchors by lazy {
        nodes.flatMap { node -> effective(node).let { it.next + it.onError + it.embeddedReferences } }
            .filter { it.isAnchor }.mapTo(mutableSetOf()) { it.targetName }
    }
    private val anchors by lazy {
        nodes.flatMap { owner -> effective(owner).anchorDefinitions.map { owner to it } }.groupBy { it.second.name }
    }

    fun node(file: VirtualFile, name: String): PipelineNodeLocation? = byKey["${file.url}#$name"]

    fun uniqueNode(name: String): PipelineNodeLocation? = byName[name]?.singleOrNull()

    fun anchorDefinitions(name: String): List<Pair<PipelineNodeLocation, PipelineAnchorDefinition>> = anchors[name].orEmpty()

    fun visibleAnchorChanges(node: PipelineNodeLocation): List<PipelineAnchorDefinition> =
        effective(node).anchorDefinitions.filter { it.name in referencedAnchors }

    fun successors(node: PipelineNodeLocation): List<Pair<String, PipelineNodeLocation>> {
        val value = effective(node)
        val references = value.next + value.onError + value.embeddedReferences.filter { it.kind == PipelineReferenceKind.SUBTASK }
        return references.flatMap { reference ->
            if (reference.isAnchor) {
                anchors[reference.targetName].orEmpty().mapNotNull { (owner, definition) ->
                    if (definition.isCleared) null else byName[definition.targetNodeName ?: owner.name]?.singleOrNull()
                        ?.let { "Anchor 候选（未确定）" to it }
                }
            } else reference.target?.let { target ->
                byKey["${target.file.url}#${target.name}"]?.let { reference.kind.label to it }
            }?.let(::listOf).orEmpty()
        }.distinctBy { it.first to key(it.second) }
    }

    fun reachable(root: PipelineNodeLocation): Set<String> = parentsFrom(root).keys

    private fun parentsFrom(root: PipelineNodeLocation): Map<String, Pair<String, String>?> {
        val rootKey = key(root)
        if (cachedRoot == rootKey) return cachedParents
        val parents = linkedMapOf<String, Pair<String, String>?> (rootKey to null)
        val queue = ArrayDeque<String>()
        if (rootKey in byKey) queue.add(rootKey)
        while (queue.isNotEmpty()) {
            val currentKey = queue.removeFirst()
            successors(byKey.getValue(currentKey)).forEach { (label, next) ->
                val nextKey = key(next)
                if (nextKey !in parents) { parents[nextKey] = currentKey to label; queue.add(nextKey) }
            }
        }
        cachedRoot = rootKey
        cachedParents = parents
        return parents
    }

    fun path(root: PipelineNodeLocation, target: PipelineNodeLocation): List<Pair<String, PipelineNodeLocation>>? {
        val parents = parentsFrom(root)
        var cursor = key(target)
        if (cursor !in parents || cursor !in byKey) return null
        val result = mutableListOf<Pair<String, PipelineNodeLocation>>()
        while (true) {
            val parent = parents[cursor]
            result += (parent?.second.orEmpty() to byKey.getValue(cursor))
            cursor = parent?.first ?: break
        }
        return result.asReversed()
    }

    companion object {
        fun key(node: PipelineNodeLocation): String = "${node.sourceFile?.url.orEmpty()}#${node.name}"
    }
}
