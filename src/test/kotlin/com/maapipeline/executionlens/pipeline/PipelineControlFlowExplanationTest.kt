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
                PipelineControlFlowLineKind.SUCCESS_CANDIDATE,
                PipelineControlFlowLineKind.SUCCESS_CANDIDATE,
                PipelineControlFlowLineKind.TERMINAL,
                PipelineControlFlowLineKind.ERROR_CANDIDATE,
                PipelineControlFlowLineKind.TERMINAL,
            ),
            lines.map { it.kind },
        )
        assertEquals("如果识别到目标 → Check", lines[1].text)
        assertEquals("否则如果识别到目标 → Fallback", lines[2].text)
        assertEquals("否则：所有成功后继均未识别到，按当前执行策略重试或结束。", lines[3].text)
        assertEquals("如果识别到目标 → Recover", lines[4].text)
        assertSame(first, lines[1].reference)
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

        assertEquals("如果识别到 Anchor 动态目标，执行完成后返回父流程，跨文件 → Checkpoint", lines[1].text)
        assertEquals("否则：所有成功后继均未识别到，按当前执行策略重试或结束。", lines[2].text)
        assertEquals("错误分支（识别超时或动作失败）：未声明。", lines[3].text)
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
        assertEquals("执行策略：timeout=5000，rate_limit=100，max_hit=2；无成功候选时由运行策略决定重试或结束。", lines[1].text)
        assertEquals("没有声明后继，按上述执行策略重试或结束。", lines.last().text)
    }
}
