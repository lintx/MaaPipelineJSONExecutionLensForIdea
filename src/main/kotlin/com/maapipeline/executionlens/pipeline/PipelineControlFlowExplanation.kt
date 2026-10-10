package com.maapipeline.executionlens.pipeline

internal enum class PipelineControlFlowLineKind {
    DESCRIPTION,
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
                text = "当前节点完成后，按后继列表顺序尝试第一个识别成功的候选。",
            ),
        )
        if (!node.executionPolicy.isEmpty()) {
            add(
                PipelineControlFlowLine(
                    kind = PipelineControlFlowLineKind.RETRY_POLICY,
                    text = "执行策略：${node.executionPolicy.summary()}；无成功候选时由运行策略决定重试或结束。",
                ),
            )
        }
        addCandidates(
            references = node.next,
            kind = PipelineControlFlowLineKind.SUCCESS_CANDIDATE,
            description = "成功分支",
        )
        addCandidates(
            references = node.onError,
            kind = PipelineControlFlowLineKind.ERROR_CANDIDATE,
            description = "错误分支（识别超时或动作失败）",
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
        if (references.isEmpty()) {
            add(
                PipelineControlFlowLine(
                    kind = PipelineControlFlowLineKind.TERMINAL,
                    text = "$description：未声明。",
                ),
            )
            return
        }
        references.forEachIndexed { index, reference ->
            val branch = when {
                index == 0 -> "如果"
                kind == PipelineControlFlowLineKind.SUCCESS_CANDIDATE -> "否则如果"
                else -> "否则如果"
            }
            add(
                PipelineControlFlowLine(
                    kind = kind,
                    text = "${branch}识别到${transitionDescription(reference)} → ${reference.targetName}",
                    reference = reference,
                ),
            )
        }
    }

    private fun transitionDescription(reference: PipelineReference): String = buildString {
        if (reference.isAnchor) append(" Anchor 动态目标") else append("目标")
        if (reference.isJumpBack) append("，执行完成后返回父流程")
        if (reference.isCrossFile) append("，跨文件")
        if (reference.isAmbiguousTarget) append("，名称不唯一")
        if (!reference.targetExists && !reference.isAnchor) append("，目标未解析")
        if (reference.isAnchorUnresolved) append("，Anchor 未解析")
    }
}
