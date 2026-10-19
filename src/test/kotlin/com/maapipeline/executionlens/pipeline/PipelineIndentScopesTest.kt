package com.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PipelineIndentScopesTest {
    @Test
    fun nestedBlocksCloseAtTheirOwnBraceAndSiblingBlocksRemainSeparate() {
        val indentation = PipelineIndentScopes(listOf(0, 1, 2, 2, 1, 1, 2, 1, 0))
        assertEquals(setOf(PipelineIndentScope(0, 8, 1), PipelineIndentScope(1, 4, 2), PipelineIndentScope(5, 7, 2)),
            indentation.scopes.toSet())
        assertEquals(PipelineIndentScope(1, 4, 2), indentation.at(3))
        assertEquals(PipelineIndentScope(5, 7, 2), indentation.at(5))
        assertEquals(PipelineIndentScope(0, 8, 1), indentation.at(8))
        assertNull(indentation.at(-1))
        assertNull(indentation.at(9))
    }

    @Test
    fun emptyAndDeepTraceRowsDoNotRequireRecursiveTraversal() {
        assertNull(PipelineIndentScopes(emptyList()).at(0))
        val indentation = PipelineIndentScopes(listOf(0, 10_000, 0))
        assertEquals(10_000, indentation.scopes.size)
        assertEquals(10_000, indentation.at(1)?.depth)
    }
}
