package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PipelineAnchorFlowTest {
    private val file = LightVirtualFile("Main.json")
    private val range = TextRange(0, 1)
    private fun definition(target: String?) = PipelineAnchorDefinition("X", range, target?.takeIf { it.isNotEmpty() }, target == "")
    private fun node(name: String, anchor: String? = null, writes: Boolean = anchor != null,
        next: List<PipelineReference> = emptyList(), action: String? = null) = PipelineNodeLocation(
        name, range, range, sourceFile = file, next = next,
        anchorDefinitions = if (writes) listOf(definition(anchor)) else emptyList(),
        action = action?.let { PipelineFieldLocation("action", it, range) },
    )
    private fun reference(name: String, anchor: Boolean = false, jump: Boolean = false, cycle: Boolean = false) = PipelineReference(
        if (anchor) "[Anchor]$name" else name, name, range, jump, anchor, targetExists = true, isCycle = cycle,
        target = if (anchor) null else PipelineNodeTarget(file, name, range, null, null, null, 0),
    )
    private val anchor = reference("X", anchor = true)

    @Test
    fun currentAssignmentWinsOverOtherProjectDefinitionsAndClearSites() {
        val source = node("Source", "Target", next = listOf(anchor))
        val target = node("Target")
        val flow = PipelineReadingGraph(listOf(source, target, node("Other", "OtherTarget"), node("OtherTarget"), node("Clear", ""))).anchorFlow
        val resolution = flow.resolve(anchor, flow.afterNode(source, PipelineAnchorContext()))
        assertSame(target, resolution.inlineTarget)
        assertEquals(setOf("Target"), resolution.value.targets)
        assertFalse(resolution.value.mayBeUnset)
    }

    @Test
    fun inheritanceRebindingAndClearingDoNotLeakBetweenAlternativeBranches() {
        val source = node("Source", "Target")
        val middle = node("Middle")
        val other = node("Other", "Second")
        val clear = node("Clear", "")
        val flow = PipelineReadingGraph(listOf(source, middle, other, clear, node("Target"), node("Second"))).anchorFlow
        val incoming = flow.afterNode(source, PipelineAnchorContext())
        assertEquals("Target", flow.resolve(anchor, flow.afterNode(middle, incoming)).inlineTarget?.name)
        assertEquals("Second", flow.resolve(anchor, flow.afterNode(other, incoming)).inlineTarget?.name)
        val cleared = flow.resolve(anchor, flow.afterNode(clear, incoming))
        assertTrue(cleared.isCleared)
        assertTrue(cleared.targets.isEmpty())
        assertEquals("Target", flow.resolve(anchor, incoming).inlineTarget?.name)
    }

    @Test
    fun jumpBackClearingKeepsTheOnlyTargetButMarksTheBindingAsOptional() {
        val reference = anchor.copy(isJumpBack = true)
        val source = node("Source", "Target", next = listOf(reference))
        val flow = PipelineReadingGraph(listOf(source, node("Target", ""))).anchorFlow
        val after = flow.afterNode(source, PipelineAnchorContext())
        val resolution = flow.resolve(reference, flow.forBranch(source.next, "next", after))
        assertEquals("Target", resolution.inlineTarget?.name)
        assertTrue(resolution.value.mayBeUnset)
        assertFalse(resolution.isCleared)
        assertTrue(resolution.hint().contains("清除时跳过"))
    }

    @Test
    fun nodeEffectsShowOnlyReferencedAnchorsAndClearingDisablesTheNextAttempt() {
        val source = node("Source", "Target", next = listOf(anchor.copy(isJumpBack = true))).let {
            it.copy(anchorDefinitions = it.anchorDefinitions + definition("Hidden").copy(name = "Unused"))
        }
        val clear = node("Target", "")
        val graph = PipelineReadingGraph(listOf(source, clear))
        assertEquals(listOf("X"), graph.visibleAnchorChanges(source).map { it.name })
        assertEquals(listOf("X"), graph.visibleAnchorChanges(clear).map { it.name })
        val firstAttempt = graph.anchorFlow.afterNode(source, PipelineAnchorContext())
        assertSame(clear, graph.anchorFlow.resolve(anchor, firstAttempt).inlineTarget)
        val retry = graph.anchorFlow.afterNode(clear, firstAttempt)
        assertTrue(graph.anchorFlow.resolve(anchor, retry).isCleared)
        assertNull(graph.anchorFlow.resolve(anchor, retry).inlineTarget)
    }

    @Test
    fun jumpBackRebindingRetainsBothTargetsInsteadOfClaimingACertainTarget() {
        val reference = anchor.copy(isJumpBack = true)
        val source = node("Source", "Target", next = listOf(reference))
        val flow = PipelineReadingGraph(listOf(source, node("Target", "Second"), node("Second"))).anchorFlow
        val resolution = flow.resolve(reference, flow.forBranch(source.next, "next", flow.afterNode(source, PipelineAnchorContext())))
        assertNull(resolution.inlineTarget)
        assertEquals(setOf("Target", "Second"), resolution.value.targets)
    }

    @Test
    fun customEffectsAreUnknownButExplicitAssignmentsAfterTheActionAreKnown() {
        val source = node("Source", "Target", next = listOf(anchor), action = "Custom（处理）")
        val custom = node("Custom", action = "Custom（处理）")
        val flow = PipelineReadingGraph(listOf(source, custom, node("Target"))).anchorFlow
        val after = flow.afterNode(source, PipelineAnchorContext())
        assertEquals("Target", flow.resolve(anchor, after).inlineTarget?.name)
        assertTrue(flow.resolve(anchor, flow.afterNode(custom, after)).value.unknown)
        assertTrue(flow.resolve(anchor, flow.forBranch(source.next, "subtask", after)).value.unknown)
        val jump = reference("Custom", jump = true)
        assertTrue(flow.resolve(anchor, flow.forBranch(listOf(jump), "next", after)).value.unknown)
    }

    @Test
    fun repeatedJumpBackRebindingsReachAStableSetIncludingLaterTargetsAndClearing() {
        val source = node("Source", "Target", next = listOf(anchor.copy(isJumpBack = true)))
        val flow = PipelineReadingGraph(listOf(source, node("Target", "Second"), node("Second", "Third"), node("Third", ""))).anchorFlow
        val resolution = flow.resolve(anchor, flow.forBranch(source.next, "next", flow.afterNode(source, PipelineAnchorContext())))
        assertEquals(setOf("Target", "Second", "Third"), resolution.value.targets)
        assertTrue(resolution.value.mayBeUnset)
        assertNull(resolution.inlineTarget)
    }

    @Test
    fun errorBranchesIgnoreJumpBackReturnEffects() {
        val source = node("Source", "Target")
        val flow = PipelineReadingGraph(listOf(source, node("Target", ""))).anchorFlow
        val resolution = flow.resolve(anchor, flow.forBranch(listOf(anchor.copy(isJumpBack = true)), "on_error",
            flow.afterNode(source, PipelineAnchorContext())))
        assertEquals("Target", resolution.inlineTarget?.name)
        assertFalse(resolution.value.mayBeUnset)
    }

    @Test
    fun cyclesMergePossibleWritesBeforeApplyingTheCurrentNodeAssignment() {
        val loop = node("Loop", next = listOf(reference("Writer", cycle = true)))
        val writer = node("Writer", "Second", next = listOf(reference("Loop", cycle = true)))
        val nodes = listOf(loop, writer, node("Target"), node("Second"))
        val incoming = PipelineAnchorContext().bind("X", "Target")
        val flow = PipelineReadingGraph(nodes).anchorFlow
        assertEquals(setOf("Target", "Second"), flow.afterNode(loop, incoming).value("X").targets)
        val assignedLoop = loop.copy(anchorDefinitions = listOf(definition("Target")))
        val assigned = PipelineReadingGraph(nodes) { if (it == loop) assignedLoop else it }.anchorFlow
        assertEquals(setOf("Target"), assigned.afterNode(loop, incoming).value("X").targets)
    }

    @Test
    fun effectiveTaskAssignmentsAndAmbiguousNamesUseTheEffectiveGraph() {
        val source = node("Source", "Target")
        val overridden = source.copy(anchorDefinitions = listOf(definition("Second")))
        val graph = PipelineReadingGraph(listOf(source, node("Target"), node("Second"))) { if (it == source) overridden else it }
        val flow = graph.anchorFlow
        assertEquals("Second", flow.resolve(anchor, flow.afterNode(source, PipelineAnchorContext())).inlineTarget?.name)
        val unknown = flow.resolve(anchor, PipelineAnchorContext())
        assertNull(unknown.inlineTarget)
        assertEquals(listOf("Second"), unknown.targets.map { it.name })
        val ambiguous = PipelineReadingGraph(listOf(source, node("Target"), node("Target").copy(sourceFile = LightVirtualFile("Other.json")))).anchorFlow
        assertNull(ambiguous.resolve(anchor, ambiguous.afterNode(source, PipelineAnchorContext())).inlineTarget)
    }

    @Test
    fun customRecognitionAndUnresolvedReturnTargetsInvalidateEarlierBindings() {
        val source = node("Source", "Target")
        val custom = node("Custom").copy(recognition = PipelineFieldLocation("recognition", "Custom（识别）", range))
        val flow = PipelineReadingGraph(listOf(source, custom, node("Target"))).anchorFlow
        val after = flow.afterNode(source, PipelineAnchorContext())
        assertTrue(flow.forBranch(listOf(reference("Custom"), anchor), "next", after).value("X").unknown)
        assertTrue(flow.forBranch(listOf(reference("Missing", jump = true)), "next", after).value("X").unknown)
    }
}
