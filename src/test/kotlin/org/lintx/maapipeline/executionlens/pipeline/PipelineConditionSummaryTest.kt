package org.lintx.maapipeline.executionlens.pipeline

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
        assertEquals("next 均未命中时重试，最多等待 20000ms，每轮识别至少耗时 1000ms", PipelineConditionSummary.successorLoop(node))
        fun withTimeout(timeout: String) = node.copy(executionPolicy = PipelineExecutionPolicy(
            timeout = PipelineFieldLocation("timeout", timeout, TextRange(0, 1)),
            rateLimit = PipelineFieldLocation("rate_limit", "100", TextRange(0, 1))))
        assertEquals("next 均未命中时重试，不设等待上限，每轮识别至少耗时 100ms", PipelineConditionSummary.successorLoop(withTimeout("-1")))
        assertEquals("next 仅检查一轮，不重试", PipelineConditionSummary.successorLoop(withTimeout("0")))
        val unlimitedRate = node.copy(executionPolicy = PipelineExecutionPolicy(
            rateLimit = PipelineFieldLocation("rate_limit", "0", TextRange(0, 1))))
        assertEquals("next 均未命中时重试，最多等待 20000ms", PipelineConditionSummary.successorLoop(unlimitedRate))
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

        assertEquals("无条件执行", PipelineConditionSummary.candidateCondition(reference))
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
        assertEquals("if", PipelineConditionSummary.candidatePrefix(0, "next"))
        assertEquals("else if", PipelineConditionSummary.candidatePrefix(1, "next"))
        assertEquals("on_error: if", PipelineConditionSummary.candidatePrefix(0, "on_error"))
        assertEquals("on_error: else if", PipelineConditionSummary.candidatePrefix(1, "on_error"))
    }

    @Test
    fun flowConditionsAreCompactAndUnconditionalBranchesDoNotPretendToTestAnything() {
        val node = PipelineNodeLocation("Move", TextRange(0, 1), TextRange(0, 1))
        assertEquals("无条件执行", PipelineConditionSummary.flowCondition(node))
        val direct = node.copy(recognition = PipelineFieldLocation("recognition", "DirectHit", TextRange(0, 1)))
        assertEquals("无条件执行", PipelineConditionSummary.flowCondition(direct))
        assertEquals("无条件执行", PipelineConditionSummary.entryCondition(direct))
        val conditional = node.copy(recognition = PipelineFieldLocation("recognition", "And（很长的完整参数）", TextRange(0, 1),
            readingSummary = "And(OCR, TemplateMatch)"))
        assertEquals("And(OCR, TemplateMatch)", PipelineConditionSummary.flowCondition(conditional))
        assertEquals("if And(OCR, TemplateMatch)", PipelineConditionSummary.flowBranch(0, "next", PipelineConditionSummary.flowCondition(conditional)))
        assertEquals("无条件执行", PipelineConditionSummary.flowBranch(0, "next", "无条件执行"))
        assertEquals("else 无条件执行", PipelineConditionSummary.flowBranch(1, "next", "无条件执行"))
        assertEquals("on_error: else if OCR", PipelineConditionSummary.flowBranch(1, "on_error", "OCR"))
    }

    @Test
    fun jumpBackExplainsRestartingTheParentsListAndDoesNotApplyToAnErrorChain() {
        val hint = PipelineConditionSummary.jumpBackHint("Parent", "next")
        kotlin.test.assertTrue(hint.contains("Parent 的 next 第一项重新尝试"))
        kotlin.test.assertTrue(hint.contains("不重新执行父节点动作"))
        kotlin.test.assertTrue(PipelineConditionSummary.jumpBackHint("Parent", "on_error").contains("不触发回跳"))
        val reference = PipelineReference("Target", "Target", TextRange(0, 1), true, false, targetExists = true)
        kotlin.test.assertFalse(PipelineConditionSummary.candidateLabel(reference, fieldName = "on_error").contains("重试"))
    }

    @Test
    fun optionalAnchorAddsAnEntryGuardAndCertainBindingsStayCompact() {
        assertEquals("Resume 已绑定", PipelineConditionSummary.anchorCondition("无条件执行", "Resume", true))
        assertEquals("Resume 已绑定 && And(OCR, TemplateMatch)",
            PipelineConditionSummary.anchorCondition("And(OCR, TemplateMatch)", "Resume", true))
        assertEquals("无条件执行", PipelineConditionSummary.anchorCondition("无条件执行", "Resume", false))
        assertEquals("OCR", PipelineConditionSummary.anchorCondition("OCR", "Resume", false))
    }

    @Test
    fun anchorStatementsDistinguishBindingSelfBindingAndClearing() {
        val range = TextRange(0, 1)
        val definition = PipelineAnchorDefinition("Resume", range, "Target", false)
        assertEquals("绑定 Anchor：Resume → Target", PipelineConditionSummary.anchorChange(definition, "Owner"))
        assertEquals("绑定 Anchor：Resume → Owner", PipelineConditionSummary.anchorChange(definition.copy(targetNodeName = null), "Owner"))
        assertEquals("清除 Anchor：Resume", PipelineConditionSummary.anchorChange(definition.copy(targetNodeName = null, isCleared = true), "Owner"))
    }
}
