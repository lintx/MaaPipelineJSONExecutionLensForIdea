package org.lintx.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTraceModelTest {
    @Test
    fun taskOverridesMergeInOrderAndAssociateByContextAcrossInterleavedStarts() {
        val parsed = PipelineTraceParser.parseText("""
            [t][Px1][Tx10][MaaTaskerPostTask] [entry=Main] [pipeline_override=[{"Check":{"enabled":true,"next":["x]y"]}},{"Check":{"enabled":false}}]] | enter
            [t][Px1][Tx10][MaaNS::Tasker::post_task] [entry=Main] [pipeline_override=[{"Check":{"enabled":true,"next":["x]y"]}},{"Check":{"enabled":false}}]]
            [t][Px1][Tx10][MaaNS::TaskNS::Context::create] [id=7]
            [t][Px1][Tx11][MaaNS::Tasker::post_task] [entry=Main] [pipeline_override={"Check":{"enabled":true}}]
            [t][Px1][Tx11][MaaNS::TaskNS::Context::create] [id=8]
            [t][Px1][Tx12] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":8}]
            [t][Px1][Tx13] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":7}]
        """.trimIndent())
        val trace = PipelineExecutionTrace(com.intellij.testFramework.LightVirtualFile("maafw.log"),
            parsed.events, parsed.taskEntries, parsed.taskOverrides)
        val snapshot = trace.overridesForEntry("Main")!!
        assertEquals(7L, snapshot.taskId)
        assertEquals(2, snapshot.sourceLineNumber)
        val check = PipelineVisualModel.parse(snapshot.rawJson)!!.getAsJsonObject("Check")
        assertEquals(false, check.get("enabled").asBoolean)
        assertEquals("x]y", check.getAsJsonArray("next")[0].asString)
        assertEquals(2, parsed.taskOverrides.size)
        assertTrue(trace.overridesForEntry("Other") == null)
    }

    @Test
    fun missingOrAmbiguousOverridesDoNotBorrowFromAnotherRunOrProcess() {
        val parsed = PipelineTraceParser.parseText("""
            [t][Px1][Tx1][MaaNS::Tasker::post_task] [entry=Main] [pipeline_override={"Check":{"enabled":true}}]
            [t][Px1][Tx1][MaaNS::TaskNS::Context::create] [id=7]
            [t][Px1] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":7}]
            [t][Px1] [msg=Node.PipelineNode.Succeeded] [details={"name":"Old","task_id":7}]
            [t][Px2] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":7}]
            [t][Px2] [msg=Node.PipelineNode.Succeeded] [details={"name":"New","task_id":7}]
            [t][Px1] [msg=Tasker.Task.Succeeded] [details={"entry":"Main","task_id":7}]
        """.trimIndent())
        val trace = PipelineExecutionTrace(com.intellij.testFramework.LightVirtualFile("maafw.log"),
            parsed.events, parsed.taskEntries, parsed.taskOverrides)
        assertTrue(trace.overridesForEntry("Main") == null)
        assertEquals(listOf("New"), trace.nodeStatesForEntry("Main").keys.toList())
        val ambiguous = PipelineTraceParser.parseText("""
            [t][Px1][Tx1][MaaNS::Tasker::post_task] [entry=Main] [pipeline_override={"A":{"enabled":true}}]
            [t][Px1][Tx2][MaaNS::Tasker::post_task] [entry=Main] [pipeline_override={"A":{"enabled":false}}]
            [t][Px1] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":9}]
        """.trimIndent())
        assertTrue(ambiguous.taskOverrides.isEmpty())
    }

    @Test
    fun parsesMaaFrameworkEventsAndDeduplicatesMirroredNotifications() {
        val parsed = PipelineTraceParser.parseText(
            """
            [2026-10-10 12:00:00.000][INF] [msg=Tasker.Task.Starting] [details={"entry":"Main","task_id":7}]
            [2026-10-10 12:00:00.001][INF] [msg=Node.PipelineNode.Starting] [details={"name":"Main","node_id":9,"task_id":7}]
            [2026-10-10 12:00:00.001][TRC] [message=Node.PipelineNode.Starting] [details_json={"name":"Main","node_id":9,"task_id":7}]
            [2026-10-10 12:00:00.002][INF] [msg=Node.NextList.Starting] [details={"name":"Main","task_id":7,"list":[{"name":"Jump","jump_back":true,"anchor":false},{"name":"AnchorTarget","jump_back":false,"anchor":true}]}]
            [2026-10-10 12:00:00.003][INF] [msg=Node.PipelineNode.Succeeded] [details={"name":"Main","node_id":9,"task_id":7}]
            [2026-10-10 12:00:00.004][INF] [msg=Node.PipelineNode.Succeeded] [details={"name":"Main","node_id":9,"task_id":7}]
            """.trimIndent(),
        )

        assertEquals("Main", parsed.taskEntries[7])
        assertEquals(5, parsed.events.size)
        assertEquals(PipelineTraceStatus.SUCCEEDED, parsed.events.last().status)
        assertEquals(4, parsed.events[2].lineNumber)
        assertTrue(parsed.events[2].candidates[0].isJumpBack)
        assertTrue(parsed.events[2].candidates[1].isAnchor)
    }

    @Test
    fun mapsObservedNextListToTheCandidateThatStarted() {
        val parsed = PipelineTraceParser.parseText(
            """
            [2026-10-10 12:00:00.000][INF] [msg=Node.NextList.Starting] [details={"name":"Main","list":[{"name":"First"},{"name":"Fallback"}]}]
            [2026-10-10 12:00:00.001][INF] [msg=Node.PipelineNode.Starting] [details={"name":"Fallback"}]
            """.trimIndent(),
        )
        assertEquals(
            PipelineTraceCandidateOutcome.SKIPPED,
            pipelineTraceCandidateOutcome(parsed.events, "Main", "First"),
        )
        assertEquals(
            PipelineTraceCandidateOutcome.SELECTED,
            pipelineTraceCandidateOutcome(parsed.events, "Main", "Fallback"),
        )
        assertEquals(
            PipelineTraceCandidateOutcome.UNOBSERVED,
            pipelineTraceCandidateOutcome(parsed.events, "Main", "Missing"),
        )
    }

    @Test
    fun parsesStringCandidatesAndKeepsNestedTaskEventsSeparate() {
        val parsed = PipelineTraceParser.parseText(
            """
            [t][INF] [msg=Tasker.Task.Starting] [details={"entry":"Daily","task_id":1}]
            [t][INF] [msg=Tasker.Task.Starting] [details={"entry":"Other","task_id":2}]
            [t][INF] [msg=Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["First","Fallback"]}]
            [t][INF] [msg=Node.PipelineNode.Starting] [details={"name":"Fallback","task_id":2}]
            """.trimIndent(),
        )

        assertEquals(listOf("First", "Fallback"), parsed.events[2].candidates.map { it.name })
        assertEquals("Daily", parsed.taskEntries[1])
        assertEquals(
            PipelineTraceCandidateOutcome.UNOBSERVED,
            pipelineTraceCandidateOutcome(parsed.events, "Main", "Fallback", taskId = 1),
        )
    }

    @Test
    fun repeatedEntryUsesLatestStartedTaskWithoutMixingAnOlderCompletion() {
        val parsed = PipelineTraceParser.parseText(
            """
            [t][INF] [msg=Tasker.Task.Starting] [details={"entry":"Daily","task_id":1}]
            [t][INF] [msg=Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["First","Fallback"]}]
            [t][INF] [msg=Node.PipelineNode.Starting] [details={"name":"First","task_id":1}]
            [t][INF] [msg=Tasker.Task.Starting] [details={"entry":"Daily","task_id":2}]
            [t][INF] [msg=Node.NextList.Starting] [details={"name":"Main","task_id":2,"list":["First","Fallback"]}]
            [t][INF] [msg=Node.PipelineNode.Starting] [details={"name":"Fallback","task_id":2}]
            [t][INF] [msg=Tasker.Task.Succeeded] [details={"entry":"Daily","task_id":1}]
            """.trimIndent(),
        )
        val events = pipelineTraceEventsForEntry(parsed.events, parsed.taskEntries, "Daily")
        assertTrue(events.all { it.taskId == 2L })
        assertEquals(PipelineTraceCandidateOutcome.SELECTED, pipelineTraceCandidateOutcome(events, "Main", "Fallback"))
        assertEquals(PipelineTraceCandidateOutcome.SKIPPED, pipelineTraceCandidateOutcome(events, "Main", "First"))
        assertTrue(pipelineTraceEventsForEntry(parsed.events, parsed.taskEntries, "Missing").isEmpty())
    }

    @Test
    fun unrelatedFollowingNodeDoesNotProveCandidatesWereSkipped() {
        val parsed = PipelineTraceParser.parseText(
            """
            [t][INF] [msg=Node.NextList.Starting] [details={"name":"Main","task_id":1,"list":["Candidate"]}]
            [t][INF] [msg=Node.PipelineNode.Starting] [details={"name":"ErrorHandler","task_id":1}]
            """.trimIndent(),
        )
        assertEquals(PipelineTraceCandidateOutcome.UNOBSERVED, pipelineTraceCandidateOutcome(parsed.events, "Main", "Candidate"))
    }
}
