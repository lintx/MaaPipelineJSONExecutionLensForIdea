package com.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PipelineGraphCyclesTest {
    @Test fun separatesSharedBranchesFromCyclesAndHandlesSelfLoops() {
        val result = PipelineGraphCycles.components(mapOf(
            "Start" to listOf("Left", "Right"), "Left" to listOf("End"),
            "Right" to listOf("End"), "End" to listOf("Again"), "Again" to listOf("End"),
            "Alone" to listOf("Alone"),
        ))
        assertEquals(result["End"], result["Again"])
        assertNotEquals(result["Left"], result["Right"])
        assertNotEquals(result["Left"], result["End"])
        assertNotEquals(result["Alone"], result["End"])
    }

    @Test fun indexesTenThousandNodesWithoutTheCallStack() {
        val graph = (0 until 10000).associate { it.toString() to listOf(((it + 1) % 10000).toString()) }
        val result = PipelineGraphCycles.components(graph)
        assertEquals(10000, result.size)
        assertEquals(1, result.values.toSet().size)
    }
}
