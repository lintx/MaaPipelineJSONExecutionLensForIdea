package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class PipelineConditionSummaryTest {
    @Test
    fun emptySuccessorsDoNotRetryEvenWithAnExecutionPolicy() {
        val node = PipelineNodeLocation("End", TextRange(0, 1), TextRange(0, 1),
            executionPolicy = PipelineExecutionPolicy(timeout = PipelineFieldLocation("timeout", "5000", TextRange(0, 1))))
        assertEquals(null, PipelineConditionSummary.successorLoop(node))
    }

    @Test
    fun successorLoopDistinguishesDefaultInfiniteAndImmediateTimeouts() {
        val reference = PipelineReference("Next", "Next", TextRange(0, 1), false, false)
        val node = PipelineNodeLocation("Start", TextRange(0, 1), TextRange(0, 1), next = listOf(reference))
        assertEquals("重试匹配后继（超时 20000ms，轮次间隔至少 1000ms）", PipelineConditionSummary.successorLoop(node))
        fun withTimeout(timeout: String) = node.copy(executionPolicy = PipelineExecutionPolicy(
            timeout = PipelineFieldLocation("timeout", timeout, TextRange(0, 1)),
            rateLimit = PipelineFieldLocation("rate_limit", "100", TextRange(0, 1))))
        assertEquals("重试匹配后继（不限时，轮次间隔至少 100ms）", PipelineConditionSummary.successorLoop(withTimeout("-1")))
        assertEquals("匹配后继（不等待重试）", PipelineConditionSummary.successorLoop(withTimeout("0")))
    }

    @Test
    fun describesNodeEntryAndActionBeforeSuccessBranches() {
        val node = PipelineNodeLocation(
            name = "Open",
            nameRange = TextRange(0, 1),
            propertyRange = TextRange(0, 10),
            recognition = PipelineFieldLocation("recognition", "TemplateMatch（template → button.png）", TextRange(0, 1)),
            action = PipelineFieldLocation("action", "Click（index → 0）", TextRange(0, 1)),
            anchor = PipelineFieldLocation("anchor", "[Scene]", TextRange(0, 1)),
        )

        assertEquals("识别 TemplateMatch（template → button.png） 成功", PipelineConditionSummary.entryCondition(node))
        assertEquals("执行动作 Click（index → 0）", PipelineConditionSummary.action(node))
        assertEquals("设置 Anchor [Scene]", PipelineConditionSummary.anchor(node))
    }

    @Test
    fun candidateUsesTargetRecognitionInsteadOfRepeatingOnlyTargetName() {
        val reference = PipelineReference(
            displayName = "Check",
            targetName = "Check",
            range = TextRange(0, 1),
            isJumpBack = false,
            isAnchor = false,
            targetExists = true,
            target = PipelineNodeTarget(
                file = com.intellij.testFramework.LightVirtualFile("Main.json"),
                name = "Check",
                nameRange = TextRange(0, 1),
                description = null,
                recognitionSummary = "OCR（text → Start）",
                actionSummary = null,
                nextCount = 0,
            ),
        )

        assertEquals("识别 OCR（text → Start） 成功", PipelineConditionSummary.candidateCondition(reference))
        assertEquals(
            "识别 OCR（text → Override） 成功",
            PipelineConditionSummary.candidateCondition(reference, "OCR（text → Override）"),
        )
    }

    @Test
    fun resolvedTargetWithoutRecognitionDoesNotRepeatTargetNameInCondition() {
        val reference = PipelineReference(
            displayName = "Main",
            targetName = "Main",
            range = TextRange(0, 1),
            isJumpBack = false,
            isAnchor = false,
            targetExists = true,
            target = PipelineNodeTarget(
                file = com.intellij.testFramework.LightVirtualFile("Main.json"),
                name = "Main",
                nameRange = TextRange(0, 1),
                description = null,
                recognitionSummary = null,
                actionSummary = null,
                nextCount = 0,
            ),
        )

        assertEquals("节点可执行", PipelineConditionSummary.candidateCondition(reference))
    }

    @Test
    fun unresolvedTargetIsClearWithoutContradictoryAvailabilityText() {
        val reference = PipelineReference(
            displayName = "Missing",
            targetName = "Missing",
            range = TextRange(0, 1),
            isJumpBack = false,
            isAnchor = false,
            targetExists = false,
        )

        assertEquals("目标 Missing 未解析", PipelineConditionSummary.candidateLabel(reference))
    }

    @Test
    fun distinguishesErrorCandidatesFromSuccessCandidates() {
        assertEquals("如果", PipelineConditionSummary.candidatePrefix(0, "next"))
        assertEquals("否则如果", PipelineConditionSummary.candidatePrefix(1, "next"))
        assertEquals("失败时如果", PipelineConditionSummary.candidatePrefix(0, "on_error"))
        assertEquals("失败时否则如果", PipelineConditionSummary.candidatePrefix(1, "on_error"))
    }
}
