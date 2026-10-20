package org.lintx.maapipeline.executionlens.pipeline

internal data class PipelineIndentScope(val firstRow: Int, val lastRow: Int, val depth: Int)

internal class PipelineIndentScopes(depths: List<Int>) {
    val scopes: List<PipelineIndentScope>

    init {
        val completed = mutableListOf<PipelineIndentScope>()
        val open = ArrayDeque<Pair<Int, Int>>()
        depths.forEachIndexed { index, depth ->
            while (open.isNotEmpty() && open.last().second > depth) {
                val (first, level) = open.removeLast()
                completed += PipelineIndentScope(first, index, level)
            }
            val previous = depths.getOrElse(index - 1) { 0 }
            for (level in previous + 1..depth) open.addLast((index - 1).coerceAtLeast(0) to level)
        }
        while (open.isNotEmpty()) {
            val (first, level) = open.removeLast()
            completed += PipelineIndentScope(first, depths.lastIndex, level)
        }
        scopes = completed
    }

    fun at(row: Int): PipelineIndentScope? = scopes.asSequence()
        .filter { row in it.firstRow..it.lastRow }
        .maxByOrNull { it.depth }
}
