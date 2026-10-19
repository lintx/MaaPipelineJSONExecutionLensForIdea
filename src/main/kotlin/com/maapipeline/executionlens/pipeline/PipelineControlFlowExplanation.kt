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
                    text = "执行策略：${node.executionPolicy.summary()}",
                ),
            )
        }
        addCandidates(
            references = node.onError,
            kind = PipelineControlFlowLineKind.ERROR_CANDIDATE,
        )
        PipelineConditionSummary.successorLoop(node)?.let {
            add(PipelineControlFlowLine(PipelineControlFlowLineKind.RETRY_POLICY, it))
        }
        addCandidates(
            references = node.next,
            kind = PipelineControlFlowLineKind.SUCCESS_CANDIDATE,
        )
    }

    private fun MutableList<PipelineControlFlowLine>.addCandidates(
        references: List<PipelineReference>,
        kind: PipelineControlFlowLineKind,
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
    }

}
