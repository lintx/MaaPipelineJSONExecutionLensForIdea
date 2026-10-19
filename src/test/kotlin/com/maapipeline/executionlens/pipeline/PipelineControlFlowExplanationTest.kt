package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PipelineControlFlowExplanationTest {
    @Test
    fun explainsOrderedSuccessCandidatesAndErrorCandidates() {
        val first = PipelineReference(
            displayName = "Check",
            targetName = "Check",
            range = TextRange(0, 1),
            isJumpBack = false,
            isAnchor = false,
            targetExists = true,
        )
        val second = first.copy(displayName = "Fallback", targetName = "Fallback")
        val error = first.copy(displayName = "Recover", targetName = "Recover")
        val node = PipelineNodeLocation(
            name = "Main",
            nameRange = TextRange(0, 1),
            propertyRange = TextRange(0, 20),
            next = listOf(first, second),
            onError = listOf(error),
        )

        val lines = PipelineControlFlowExplanation.explain(node)

        assertEquals(
            listOf(
                PipelineControlFlowLineKind.DESCRIPTION,
                PipelineControlFlowLineKind.ERROR_CANDIDATE,
                PipelineControlFlowLineKind.RETRY_POLICY,
                PipelineControlFlowLineKind.SUCCESS_CANDIDATE,
                PipelineControlFlowLineKind.SUCCESS_CANDIDATE,
            ),
            lines.map { it.kind },
        )
        assertEquals("on_error: if 无条件执行 → Recover", lines[1].text)
        assertEquals("重试匹配后继（超时 20000ms，轮次间隔至少 1000ms）", lines[2].text)
        assertEquals("if 无条件执行 → Check", lines[3].text)
        assertEquals("else if 无条件执行 → Fallback", lines[4].text)
        assertSame(first, lines[3].reference)
    }

    @Test
    fun explainsDynamicAnchorJumpBackAndTerminalNode() {
        val anchor = PipelineReference(
            displayName = "[Anchor]Checkpoint",
            targetName = "Checkpoint",
            range = TextRange(0, 1),
            isJumpBack = true,
            isAnchor = true,
            targetExists = true,
            isCrossFile = true,
        )
        val node = PipelineNodeLocation(
            name = "Main",
            nameRange = TextRange(0, 1),
            propertyRange = TextRange(0, 20),
            next = listOf(anchor),
        )

        val lines = PipelineControlFlowExplanation.explain(node)

        assertEquals("if 运行时 Anchor Checkpoint 已解析，子链结束后从父 next 首项重试，跨文件 → Checkpoint", lines[2].text)
        assertEquals("重试匹配后继（超时 20000ms，轮次间隔至少 1000ms）", lines[1].text)
        assertEquals(3, lines.size)
    }

    @Test
    fun explainsExecutionPolicyBeforeRetryOrTerminalState() {
        val policy = PipelineExecutionPolicy(
            timeout = PipelineFieldLocation("timeout", "5000", TextRange(0, 1)),
            rateLimit = PipelineFieldLocation("rate_limit", "100", TextRange(0, 1)),
            maxHit = PipelineFieldLocation("max_hit", "2", TextRange(0, 1)),
        )
        val node = PipelineNodeLocation(
            name = "Main",
            nameRange = TextRange(0, 1),
            propertyRange = TextRange(0, 1),
            executionPolicy = policy,
        )

        val lines = PipelineControlFlowExplanation.explain(node)

        assertEquals(PipelineControlFlowLineKind.RETRY_POLICY, lines[1].kind)
        assertEquals("执行策略：timeout=5000，rate_limit=100，max_hit=2", lines[1].text)
        assertEquals(2, lines.size)
    }
}
