package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineReferenceDiagnosticsTest {
    private val range = TextRange(4, 15)
    private val reference = PipelineReference("Missing", "Missing", range, false, false)

    @Test
    fun missingJumpBackTargetIsAnErrorButResolvedCrossFileTargetIsValid() {
        assertEquals(PipelineDiagnosticSeverity.ERROR, PipelineReferenceDiagnostics.issue(reference.copy(isJumpBack = true))?.severity)
        assertNull(PipelineReferenceDiagnostics.issue(reference.copy(targetExists = true, isCrossFile = true)))
    }

    @Test
    fun ambiguousNodeAndDynamicAnchorProduceDistinctWarnings() {
        val duplicate = PipelineReferenceDiagnostics.issue(reference.copy(targetExists = true, isAmbiguousTarget = true))!!
        assertEquals("目标名称不唯一", duplicate.message)
        val dynamic = PipelineReferenceDiagnostics.issue(reference.copy(
            isAnchor = true, isAnchorUnresolved = true, isAmbiguousTarget = true,
        ))!!
        assertEquals(PipelineDiagnosticSeverity.WARNING, dynamic.severity)
        assertTrue(dynamic.suggestion.contains("运行时最后设置生效"))
    }

    @Test
    fun undefinedAndClearedAnchorsNeverBecomeMissingOrdinaryNodeErrors() {
        val undefined = reference.copy(isAnchor = true, isAnchorUnresolved = true)
        assertEquals("Anchor 未定义", PipelineReferenceDiagnostics.issue(undefined)?.message)
        val cleared = PipelineAnchorTarget(LightVirtualFile("Owner.json"), "A", range, "Owner", null, null, true)
        assertEquals("Anchor 已被清除", PipelineReferenceDiagnostics.issue(undefined.copy(anchorTarget = cleared))?.message)
    }

    @Test
    fun missingMappedTargetHasAnAnchorSpecificSuggestion() {
        val anchor = PipelineAnchorTarget(LightVirtualFile("Owner.json"), "A", range, "Owner", "Missing", null, false)
        val issue = PipelineReferenceDiagnostics.issue(reference.copy(
            isAnchor = true, isAnchorUnresolved = true, anchorTarget = anchor,
        ))!!
        assertEquals("Anchor 的目标节点不存在", issue.message)
        assertTrue(issue.suggestion.contains("映射"))
    }
}
