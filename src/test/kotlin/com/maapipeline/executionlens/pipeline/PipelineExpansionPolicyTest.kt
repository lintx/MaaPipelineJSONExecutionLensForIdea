package com.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineExpansionPolicyTest {
    @Test fun canContinuePastInitialBudgetWithoutUnrollingCycles() {
        assertEquals(PipelineExpansionBoundary.BUDGET,
            PipelineExpansionPolicy.boundary("Next", emptySet(), emptySet(), false, 200, 200))
        assertNull(PipelineExpansionPolicy.boundary("Next", emptySet(), emptySet(), false, 200, 400))
        assertEquals(PipelineExpansionBoundary.CYCLE,
            PipelineExpansionPolicy.boundary("Root", setOf("Root"), setOf("Root"), true, 1, 400))
    }

    @Test fun sharedNodeCanExpandAtAnIndependentInstance() {
        assertEquals(PipelineExpansionBoundary.SHARED,
            PipelineExpansionPolicy.boundary("Shared", emptySet(), setOf("Shared"), false, 2, 200))
        assertNull(PipelineExpansionPolicy.boundary("Shared", emptySet(), setOf("Shared"), true, 2, 200))
        val first = PipelineExpansionPolicy.instanceKey("left:next:0", "Shared")
        assertEquals(first, PipelineExpansionPolicy.instanceKey("left:next:0", "Shared"))
        assertNotEquals(first, PipelineExpansionPolicy.instanceKey("right:next:0", "Shared"))
        assertTrue(first.length < 50)
    }

    @Test fun walksLongAndCyclicGraphsWithoutUsingTheCallStack() {
        val reachable = PipelineExpansionPolicy.reachable(0, Int::toString) { node ->
            when { node < 9999 -> listOf(node + 1); else -> listOf(0) }
        }
        assertEquals(10000, reachable.size)
    }
}
