package org.lintx.maapipeline.executionlens.pipeline

internal object PipelineGraphCycles {
    fun components(graph: Map<String, List<String>>): Map<String, Int> {
        val visited = mutableSetOf<String>()
        val finished = mutableListOf<String>()
        val reverse = linkedMapOf<String, MutableList<String>>()
        graph.forEach { (source, targets) ->
            reverse.getOrPut(source) { mutableListOf() }
            targets.forEach { reverse.getOrPut(it) { mutableListOf() }.add(source) }
        }
        // 每个强连通分量中的边都是循环边，整图只遍历两次，避免逐边递归并复制路径集合。
        reverse.keys.forEach { root ->
            val pending = ArrayDeque<Pair<String, Boolean>>()
            pending.add(root to false)
            while (pending.isNotEmpty()) {
                val (node, exiting) = pending.removeLast()
                if (exiting) finished.add(node)
                else if (visited.add(node)) {
                    pending.add(node to true)
                    graph[node].orEmpty().forEach { if (it !in visited) pending.add(it to false) }
                }
            }
        }
        val result = linkedMapOf<String, Int>()
        var component = 0
        finished.asReversed().forEach { root ->
            if (root in result) return@forEach
            val pending = ArrayDeque<String>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                if (result.putIfAbsent(node, component) == null) reverse[node].orEmpty().forEach(pending::addLast)
            }
            component++
        }
        return result
    }
}
