package com.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineTraceReplayTest {
    private fun parse(vararg messages: String): List<PipelineTraceEvent> = PipelineTraceParser.parseText(
        messages.mapIndexed { index, message -> "[t$index][INF] $message" }.joinToString("\n"),
    ).events

    @Test
    fun replaysNestedJumpBackCallsAndInferredReturns() {
        val events = parse(
            """[msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":1}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["[JumpBack]Child"]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Child","task_id":1}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Child","task_id":1,"list":[{"name":"Leaf","jump_back":true}]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Leaf","task_id":1}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Child","task_id":1,"list":[]}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":[]}]""",
        )
        val replay = PipelineTraceReplayBuilder.build(events)
        assertEquals(listOf("Child", "Leaf"), replay.calls.map { it.target })
        assertEquals(listOf(0, 1), replay.calls.map { it.depth })
        assertEquals(listOf(7, 6), replay.calls.map { it.resumed?.lineNumber })
        assertEquals(2, replay.steps.single { it.text == "执行 Leaf" }.depth)
        assertTrue(replay.steps.any { it.text.contains("推断返回 Main") })
        assertTrue(replay.warnings.any { it.contains("并非完整运行时调用栈") })
    }

    @Test
    fun interleavedTasksAndUnassociatedEventsCannotCompleteAnotherTasksCall() {
        val events = parse(
            """[msg=Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":[{"name":"Child","jump_back":true}]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Child","task_id":1}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Main","task_id":2,"list":[]}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":[]}]""",
        )
        val replay = PipelineTraceReplayBuilder.build(events)
        assertNull(replay.calls.single().resumed)
        assertTrue(replay.warnings.any { it.contains("不能确认返回") })
        assertTrue(replay.warnings.any { it.contains("缺少任务开始") })
    }

    @Test
    fun failedRoundDoesNotBorrowALaterNodesSelection() {
        val events = parse(
            """[msg=Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["Child"]}]""",
            """[msg=Node.Recognition.Failed] [details={"name":"Child","task_id":1}]""",
            """[msg=Node.NextList.Failed] [details={"name":"Main","task_id":1}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Child","task_id":1}]""",
        )
        assertEquals(PipelineTraceCandidateOutcome.MISSED, pipelineTraceCandidateOutcome(events, "Main", "Child"))
        assertNull(pipelineTraceRounds(events).single().selected)
    }

    @Test
    fun preservesRecognitionMissesAndUnknownCandidatesWithinEachRetryRound() {
        val events = parse(
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":["First","Second"]}]""",
            """[msg=Node.Recognition.Failed] [details={"name":"First"}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Second"}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":["First","Second"]}]""",
            """[msg=Node.Recognition.Succeeded] [details={"name":"First"}]""",
        )
        val rounds = pipelineTraceRounds(events)
        assertEquals(2, rounds.size)
        assertEquals(PipelineTraceCandidateOutcome.MISSED, rounds.first().outcome("First"))
        assertEquals(PipelineTraceCandidateOutcome.SELECTED, rounds.first().outcome("Second"))
        assertEquals(PipelineTraceCandidateOutcome.MATCHED, rounds.last().outcome("First"))
        assertEquals(PipelineTraceCandidateOutcome.UNOBSERVED, rounds.last().outcome("Second"))
    }

    @Test
    fun ambiguousDuplicateCandidatesDoNotInventAJumpBackCall() {
        val events = parse(
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":["Child","[JumpBack]Child"]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Child"}]""",
        )
        assertTrue(PipelineTraceReplayBuilder.build(events).calls.isEmpty())
    }

    @Test
    fun timestampChangesPreserveRepeatedExecutionButMirroredMessagesAreDeduplicated() {
        val parsed = PipelineTraceParser.parseText(
            """
            [t1][INF] [msg=Node.PipelineNode.Starting] [details={"name":"Main"}]
            [t1][TRC] [message=Node.PipelineNode.Starting] [details_json={"name":"Main"}]
            [t2][INF] [msg=Node.PipelineNode.Starting] [details={"name":"Main"}]
            """.trimIndent(),
        )
        assertEquals(2, parsed.events.size)
        assertEquals(listOf(1, 3), parsed.events.map { it.lineNumber })
    }

    @Test
    fun ordinaryTransitionsDoNotBecomeCallsAndAnchorFlagsAreKept() {
        val events = parse(
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":["[Anchor]Target"]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Target","anchor":"Checkpoint"}]""",
        )
        assertTrue(events.first().candidates.single().isAnchor)
        assertEquals("Target", events.first().candidates.single().name)
        val replay = PipelineTraceReplayBuilder.build(events)
        assertTrue(replay.calls.isEmpty())
        assertTrue(replay.steps.any { it.text.contains("Anchor 观察：Checkpoint") })
    }

    @Test
    fun nodeIdsPreventMistakingAnotherInvocationForAReturn() {
        val events = parse(
            """[msg=Node.NextList.Starting] [details={"name":"Main","node_id":10,"list":["[JumpBack]Child"]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Child","node_id":20}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Main","node_id":11,"list":[]}]""",
        )
        assertNull(PipelineTraceReplayBuilder.build(events).calls.single().resumed)
    }

    @Test
    fun recursiveCallsWithoutInvocationIdsCannotProveAReturn() {
        val events = parse(
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":["[JumpBack]Main"]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Main"}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":[]}]""",
        )
        val replay = PipelineTraceReplayBuilder.build(events)
        assertNull(replay.calls.single().resumed)
        assertTrue(replay.steps.none { it.text.contains("推断返回") })
    }

    @Test
    fun anOuterResumeDoesNotInventMissingInnerReturns() {
        val events = parse(
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":["[JumpBack]Child"]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Child"}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Child","list":["[JumpBack]Leaf"]}]""",
            """[msg=Node.PipelineNode.Starting] [details={"name":"Leaf"}]""",
            """[msg=Node.NextList.Starting] [details={"name":"Main","list":[]}]""",
        )
        val replay = PipelineTraceReplayBuilder.build(events)
        assertEquals(5, replay.calls.first().resumed?.lineNumber)
        assertNull(replay.calls.last().resumed)
        assertTrue(replay.warnings.any { it.contains("1 个 JumpBack") })
    }

    @Test
    fun taskScopedCacheKeepsRepeatedEntriesAndMissingEntriesSeparate() {
        val parsed = PipelineTraceParser.parseText("""
            [t1][INF] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":1}]
            [t2][INF] [msg=Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["Old"]}]
            [t3][INF] [msg=Node.PipelineNode.Starting] [details={"name":"Old","task_id":1}]
            [t4][INF] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":2}]
            [t5][INF] [msg=Node.NextList.Starting] [details={"name":"Main","task_id":2,"list":["New"]}]
            [t6][INF] [msg=Node.PipelineNode.Starting] [details={"name":"New","task_id":2}]
            [t7][INF] [msg=Tasker.Task.Succeeded] [details={"entry":"Main","task_id":1}]
        """.trimIndent())
        val trace = PipelineExecutionTrace(com.intellij.testFramework.LightVirtualFile("trace.log"), parsed.events, parsed.taskEntries)
        trace.replayForEntry(null)
        assertEquals(listOf("New"), trace.roundsForEntry("Main").mapNotNull { it.selected?.nodeName })
        assertTrue(trace.replayForEntry("Main").steps.all { it.event.taskId == 2L })
        assertTrue(trace.replayForEntry("Missing").steps.isEmpty())
    }
}
