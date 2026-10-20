package org.lintx.maapipeline.executionlens.pipeline

internal enum class PipelineExpansionBoundary { CYCLE, SHARED, BUDGET }

internal object PipelineExpansionPolicy {
    fun instanceKey(parent: String, target: String): String = "flow:" + java.util.UUID.nameUUIDFromBytes(
        "$parent:$target".toByteArray(Charsets.UTF_8))

    fun boundary(target: String, ancestors: Set<String>, seen: Set<String>, expandShared: Boolean,
        rendered: Int, budget: Int): PipelineExpansionBoundary? = when {
        target in ancestors -> PipelineExpansionBoundary.CYCLE
        target in seen && !expandShared -> PipelineExpansionBoundary.SHARED
        rendered >= budget -> PipelineExpansionBoundary.BUDGET
        else -> null
    }

    fun <Node> reachable(root: Node, key: (Node) -> String, successors: (Node) -> List<Node>): Set<String> {
        val visited = linkedSetOf<String>()
        val pending = ArrayDeque<Node>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!visited.add(key(node))) continue
            successors(node).asReversed().forEach(pending::addLast)
        }
        return visited
    }
}
