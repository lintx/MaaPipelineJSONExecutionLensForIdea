package com.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTraceModelTest {
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
        assertEquals(4, parsed.events.size)
        assertEquals(PipelineTraceStatus.SUCCEEDED, parsed.events.last().status)
        assertEquals(4, parsed.events[2].lineNumber)
        assertTrue(parsed.events[2].candidates[0].isJumpBack)
        assertTrue(parsed.events[2].candidates[1].isAnchor)
    }
}
