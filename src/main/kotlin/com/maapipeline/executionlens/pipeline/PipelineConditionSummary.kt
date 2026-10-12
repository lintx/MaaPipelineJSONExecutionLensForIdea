package com.maapipeline.executionlens.pipeline

internal object PipelineConditionSummary {
    fun entryCondition(node: PipelineNodeLocation): String =
        node.recognition?.summary?.let { "识别 $it 成功" } ?: "节点可执行"

    fun action(node: PipelineNodeLocation): String? =
        node.action?.summary?.let { "执行动作 $it" }

    fun anchor(node: PipelineNodeLocation): String? =
        node.anchor?.summary?.let { "设置 Anchor $it" }

    fun candidateCondition(reference: PipelineReference, recognitionSummary: String? = null): String {
        val target = reference.target
        val recognition = recognitionSummary ?: target?.recognitionSummary
        return when {
            recognition != null -> "识别 $recognition 成功"
            reference.isAnchor -> "运行时 Anchor ${reference.targetName} 已解析"
            !reference.targetExists -> "目标 ${reference.targetName}"
            else -> "节点可执行"
        }
    }

    fun candidateLabel(reference: PipelineReference, recognitionSummary: String? = null): String = buildString {
        if (!reference.targetExists && !reference.isAnchor) {
            append("目标 ${reference.targetName} 未解析")
        } else {
            append(candidateCondition(reference, recognitionSummary))
        }
        if (reference.isJumpBack) append("，完成后返回父流程")
        if (reference.isCrossFile) append("，跨文件")
        if (reference.isAmbiguousTarget) append("，名称不唯一")
        if (reference.isAnchorUnresolved) append("，Anchor 未解析")
    }

    fun candidatePrefix(index: Int, fieldName: String): String = when (fieldName) {
        "on_error" -> if (index == 0) "失败时如果" else "失败时否则如果"
        else -> if (index == 0) "如果" else "否则如果"
    }
}
