package org.lintx.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelinePlaybackTest {
    @Test
    fun contextSubtasksPlayRecursivelyAndResumeTheirCallerWithoutBorrowingConcurrentTasks() {
        val parsed = PipelineTraceParser.parseText("""
            [t][Px1][Tx10][MaaNS::Tasker::post_task] [entry=Main] [pipeline_override={"Caller":{"enabled":true}}]
            [t][Px1][Tx10][MaaNS::TaskNS::Context::create] [id=1]
            [t][Px1][Tx10] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":1}]
            [t1][Px1][Tx10][MaaNS::AgentNS::ClientNS::AgentClient::tasker_event_sink] [message=Tasker.Task.Starting] [details_json={"entry":"Main","task_id":1}]
            [t2][Px2][Tx20] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":1}]
            [t][Px1][Tx10][MaaNS::TaskNS::PipelineTask::run] [entry_=Main] [task_id_=1] | enter
            [t][Px1][Tx10] [msg=Node.PipelineNode.Starting] [details={"name":"Main","task_id":1}]
            [t][Px1][Tx10] [msg=Node.Action.Starting] [details={"name":"Caller","task_id":1}]
            [t][Px1][Tx10][MaaNS::TaskNS::Context::run_task] [entry=Child] [pipeline_override={}]
            [t][Px1][Tx10][MaaNS::TaskNS::PipelineTask::run] [entry_=Child] [task_id_=2] | enter
            [t][Px1][Tx10] [msg=Node.PipelineNode.Starting] [details={"name":"Child","task_id":2}]
            [t1][Px1][Tx10][MaaNS::AgentNS::ClientNS::AgentClient::ctx_event_sink] [message=Node.PipelineNode.Starting] [details_json={"name":"Child","task_id":2}]
            [t][Px1][Tx11][MaaNS::TaskNS::PipelineTask::run] [entry_=Child] [task_id_=9] | enter
            [t][Px1][Tx11] [msg=Node.PipelineNode.Starting] [details={"name":"Unrelated","task_id":9}]
            [t][Px2][Tx10] [msg=Node.PipelineNode.Starting] [details={"name":"OtherProcess","task_id":2}]
            [t][Px1][Tx10] [msg=Node.Action.Starting] [details={"name":"ChildCaller","task_id":2}]
            [t][Px1][Tx10][MaaNS::TaskNS::Context::run_task] [entry=Grandchild] [pipeline_override={}]
            [t][Px1][Tx10][MaaNS::TaskNS::PipelineTask::run] [entry_=Grandchild] [task_id_=3] | enter
            [t][Px1][Tx10] [msg=Node.PipelineNode.Starting] [details={"name":"Grandchild","task_id":3}]
            [t][Px1][Tx10][MaaNS::TaskNS::PipelineTask::run] | leave, 2ms
            [t][Px1][Tx10] [msg=Node.Action.Succeeded] [details={"name":"ChildCaller","task_id":2}]
            [t][Px1][Tx10][MaaNS::TaskNS::PipelineTask::run] | leave, 5ms
            [t][Px1][Tx10] [msg=Node.Action.Succeeded] [details={"name":"Caller","task_id":1}]
            [t][Px1][Tx10] [msg=Tasker.Task.Succeeded] [details={"entry":"Main","task_id":1}]
            [t][Px1][Tx10] [msg=Node.PipelineNode.Starting] [details={"name":"OutsideCall","task_id":2}]
        """.trimIndent())
        val trace = PipelineExecutionTrace(com.intellij.testFramework.LightVirtualFile("trace.log"), parsed.events,
            parsed.taskEntries, parsed.taskOverrides, parsed.taskCalls, parsed.taskRuns)
        val events = trace.eventsForEntry("Main")
        assertEquals(parsed.taskOverrides.single(), trace.overridesForEntry("Main"))
        assertEquals(1, events.count { it.kind == PipelineTraceEventKind.TASK && it.status == PipelineTraceStatus.STARTING })
        assertEquals(1, events.count { it.nodeName == "Child" && it.kind == PipelineTraceEventKind.PIPELINE_NODE })
        assertEquals(setOf(1L, 2L, 3L), events.map { it.taskId }.toSet())
        assertFalse(events.any { it.nodeName in setOf("Unrelated", "OtherProcess", "OutsideCall") })
        assertEquals(listOf(1L, 2L), parsed.taskCalls.map { it.parentTaskId })
        assertEquals(listOf("Caller", "ChildCaller"), parsed.taskCalls.map { it.caller })
        val source = trace.playbackForEntry("Main")
        assertFalse(trace.replayForEntry("Main").warnings.any { it.contains("缺少任务开始") })
        val cursor = PipelinePlaybackCursor(source)
        val frames = cursor.positions.map { source.frame(it)!! }
        assertEquals(listOf(null, "Caller", "ChildCaller", "Grandchild", "ChildCaller", "Caller", null), frames.map { it.nodeName })
        assertTrue(frames[4].text.contains("Grandchild 结束"))
        assertTrue(frames[5].text.contains("Child 结束"))
        cursor.seek(3)
        assertNull(source.nodeState("Grandchild", cursor.eventIndex)?.takeIf { it.status != PipelineTraceStatus.STARTING })
        cursor.seek(1)
        assertNull(source.nodeState("Child", cursor.eventIndex))
    }

    @Test
    fun actualActionTargetIsPlayedAndCandidateSelectionDoesNotLeakBeforeExecution() {
        val source = source(
            """Tasker.Task.Starting] [details={"entry":"Main","task_id":1}]""",
            """Node.PipelineNode.Starting] [details={"name":"Main","task_id":1}]""",
            """Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["Target"]}]""",
            """Node.Recognition.Succeeded] [details={"name":"Target","task_id":1}]""",
            """Node.Action.Starting] [details={"name":"Target","task_id":1}]""",
            """Node.Action.Succeeded] [details={"name":"Target","task_id":1}]""",
            """Node.PipelineNode.Succeeded] [details={"name":"Main","task_id":1}]""",
            """Tasker.Task.Succeeded] [details={"entry":"Main","task_id":1}]""",
        )
        assertEquals(listOf(0, 4, 7), source.positions(PipelinePlaybackGranularity.NODE))
        assertEquals("Target", source.frame(4)?.nodeName)
        assertEquals(PipelineTraceCandidateOutcome.MATCHED, source.outcome("Main", "Target", 3))
        assertEquals(PipelineTraceCandidateOutcome.SELECTED, source.outcome("Main", "Target", 4))
        assertEquals(PipelineTraceStatus.SUCCEEDED, source.nodeState("Target", 5)?.status)
        assertNull(source.nodeState("Target", 3))
    }

    @Test
    fun nestedTaskWithoutContextEvidenceIsNotAssumedToBeASubtask() {
        val parsed = PipelineTraceParser.parseText("""
            [t][Px1][Tx10] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":1}]
            [t][Px1][Tx10][MaaNS::TaskNS::PipelineTask::run] [entry_=Main] [task_id_=1] | enter
            [t][Px1][Tx10] [msg=Node.Action.Starting] [details={"name":"Caller","task_id":1}]
            [t][Px1][Tx10][MaaNS::TaskNS::PipelineTask::run] [entry_=Other] [task_id_=2] | enter
            [t][Px1][Tx10] [msg=Node.PipelineNode.Starting] [details={"name":"Other","task_id":2}]
        """.trimIndent())
        assertTrue(parsed.taskCalls.isEmpty())
        assertEquals(setOf(1L), pipelineTraceEventsForEntry(parsed.events, parsed.taskEntries, "Main", parsed.taskCalls).map { it.taskId }.toSet())
    }

    private fun source(vararg messages: String): PipelineLogPlaybackSource {
        val events = PipelineTraceParser.parseText(messages.mapIndexed { index, message ->
            "[t$index] [msg=$message"
        }.joinToString("\n")).events
        return PipelineLogPlaybackSource(events, pipelineTraceRounds(events), PipelineTraceReplayBuilder.build(events))
    }

    @Test
    fun rewindRemovesFutureNodeAndCandidateResultsAndKeepsRepeatedVisits() {
        val source = source(
            """Tasker.Task.Starting] [details={"entry":"Main","task_id":1}]""",
            """Node.PipelineNode.Starting] [details={"name":"Main","task_id":1}]""",
            """Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["Child","Other"]}]""",
            """Node.Recognition.Succeeded] [details={"name":"Child","task_id":1}]""",
            """Node.PipelineNode.Starting] [details={"name":"Child","task_id":1}]""",
            """Node.PipelineNode.Succeeded] [details={"name":"Child","task_id":1}]""",
            """Node.PipelineNode.Starting] [details={"name":"Main","task_id":1}]""",
            """Tasker.Task.Succeeded] [details={"entry":"Main","task_id":1}]""",
        )
        val cursor = PipelinePlaybackCursor(source)
        assertFalse(cursor.canPrevious)
        assertEquals(listOf(0, 1, 4, 6, 7), cursor.positions)
        cursor.seek(cursor.size - 1)
        assertEquals(PipelineTraceStatus.SUCCEEDED, source.nodeState("Child", cursor.eventIndex)?.status)
        assertEquals(PipelineTraceCandidateOutcome.SELECTED, source.outcome("Main", "Child", cursor.eventIndex))
        assertEquals(PipelineTraceCandidateOutcome.SKIPPED, source.outcome("Main", "Other", cursor.eventIndex))
        assertTrue(cursor.seek(1))
        assertNull(source.nodeState("Child", cursor.eventIndex))
        assertEquals(PipelineTraceCandidateOutcome.UNOBSERVED, source.outcome("Main", "Child", cursor.eventIndex))
        cursor.changeGranularity(PipelinePlaybackGranularity.EVENT)
        cursor.seek(3)
        assertEquals(PipelineTraceCandidateOutcome.MATCHED, source.outcome("Main", "Child", cursor.eventIndex))
        assertEquals(PipelineTraceCandidateOutcome.UNOBSERVED, source.outcome("Main", "Other", cursor.eventIndex))
        cursor.seek(2)
        assertEquals(PipelineTraceCandidateOutcome.UNOBSERVED, source.outcome("Main", "Child", cursor.eventIndex))
        assertEquals(1, source.roundsThrough("Main", cursor.eventIndex).size)
    }

    @Test
    fun inferredJumpBackReturnIsNotVisibleBeforeItsEvidence() {
        val source = source(
            """Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["[JumpBack]Child"]}]""",
            """Node.PipelineNode.Starting] [details={"name":"Child","task_id":1}]""",
            """Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":[]}]""",
        )
        assertTrue(source.callsThrough("Main", 0).isEmpty())
        assertNull(source.callsThrough("Main", 1).single().resumed)
        assertEquals(3, source.callsThrough("Main", 2).single().resumed?.lineNumber)
        assertNull(source.callsThrough("Main", 1).single().resumed)
        assertEquals(1, source.frame(1)!!.text.split("执行 Child").size - 1)
    }

    @Test
    fun switchingGranularityPreservesTimeAndEmptySourcesAreSafe() {
        val source = source(
            """Node.PipelineNode.Starting] [details={"name":"Main","task_id":1}]""",
            """Node.Action.Succeeded] [details={"name":"Main","task_id":1}]""",
            """Node.PipelineNode.Starting] [details={"name":"Child","task_id":1}]""",
        )
        val cursor = PipelinePlaybackCursor(source)
        cursor.changeGranularity(PipelinePlaybackGranularity.EVENT)
        cursor.seek(1)
        cursor.changeGranularity(PipelinePlaybackGranularity.NODE)
        assertEquals(1, cursor.eventIndex)
        assertEquals("Main", cursor.frame?.nodeName)
        assertTrue(cursor.seek(cursor.position + 1))
        assertEquals("Child", cursor.frame?.nodeName)
        val empty = PipelinePlaybackCursor(source())
        assertNull(empty.frame)
        assertFalse(empty.canNext)
        assertFalse(empty.canPrevious)
        assertFalse(empty.seek(0))
    }

    @Test
    fun capabilityFlagsPreventRewindForAFutureForwardOnlySource() {
        val source = object : PipelinePlaybackSource {
            override val canSeekBackward = false
            override val canAutoplay = false
            override fun positions(granularity: PipelinePlaybackGranularity) = listOf(0, 1, 2)
            override fun frame(eventIndex: Int) = PipelinePlaybackFrame("event $eventIndex", null, null, null)
        }
        val cursor = PipelinePlaybackCursor(source)
        assertTrue(cursor.seek(2))
        assertFalse(cursor.canPrevious)
        assertFalse(cursor.seek(0))
        assertEquals(2, cursor.eventIndex)
    }
}
