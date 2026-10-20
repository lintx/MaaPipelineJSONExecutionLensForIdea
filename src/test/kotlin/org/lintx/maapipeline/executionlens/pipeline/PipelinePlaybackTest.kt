package org.lintx.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelinePlaybackTest {
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
