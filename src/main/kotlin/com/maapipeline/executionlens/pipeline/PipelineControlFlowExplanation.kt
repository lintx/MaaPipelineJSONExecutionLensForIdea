package com.maapipeline.executionlens.pipeline

internal enum class PipelineControlFlowLineKind {
    DESCRIPTION,
    ACTION,
    RETRY_POLICY,
    SUCCESS_CANDIDATE,
    ERROR_CANDIDATE,
    TERMINAL,
}

internal data class PipelineControlFlowLine(
    val kind: PipelineControlFlowLineKind,
    val text: String,
    val reference: PipelineReference? = null,
)

internal object PipelineControlFlowExplanation {
    fun explain(node: PipelineNodeLocation): List<PipelineControlFlowLine> = buildList {
        add(
            PipelineControlFlowLine(
                kind = PipelineControlFlowLineKind.DESCRIPTION,
                text = "进入条件：${PipelineConditionSummary.entryCondition(node)}。",
            ),
        )
        PipelineConditionSummary.action(node)?.let { action ->
            add(PipelineControlFlowLine(PipelineControlFlowLineKind.ACTION, action))
        }
        if (!node.executionPolicy.isEmpty()) {
            add(
                PipelineControlFlowLine(
                    kind = PipelineControlFlowLineKind.RETRY_POLICY,
                    text = "执行策略：${node.executionPolicy.summary()}；无成功候选时由运行策略决定重试或结束。",
                ),
            )
        }
        addCandidates(
            references = node.onError,
            kind = PipelineControlFlowLineKind.ERROR_CANDIDATE,
            description = "错误分支（识别超时或动作失败）",
        )
        addCandidates(
            references = node.next,
            kind = PipelineControlFlowLineKind.SUCCESS_CANDIDATE,
            description = "成功分支",
        )
        if (node.next.isEmpty() && node.onError.isEmpty()) {
            add(
                PipelineControlFlowLine(
                    kind = PipelineControlFlowLineKind.TERMINAL,
                    text = if (node.executionPolicy.isEmpty()) {
                        "没有声明后继，当前流程在此结束或由运行策略处理。"
                    } else {
                        "没有声明后继，按上述执行策略重试或结束。"
                    },
                ),
            )
        }
    }

    private fun MutableList<PipelineControlFlowLine>.addCandidates(
        references: List<PipelineReference>,
        kind: PipelineControlFlowLineKind,
        description: String,
    ) {
        if (references.isEmpty()) return
        references.forEachIndexed { index, reference ->
            val branch = PipelineConditionSummary.candidatePrefix(
                index = index,
                fieldName = if (kind == PipelineControlFlowLineKind.ERROR_CANDIDATE) "on_error" else "next",
            )
            add(
                PipelineControlFlowLine(
                    kind = kind,
                    text = "${branch}${PipelineConditionSummary.candidateLabel(reference)} → ${reference.targetName}",
                    reference = reference,
                ),
            )
        }
        add(
            PipelineControlFlowLine(
                kind = PipelineControlFlowLineKind.TERMINAL,
                text = if (kind == PipelineControlFlowLineKind.SUCCESS_CANDIDATE) {
                    "否则：所有成功后继均未识别到，按当前执行策略重试或结束。"
                } else {
                    "否则：失败后继均未成功，按当前执行策略处理。"
                },
            ),
        )
    }

}
