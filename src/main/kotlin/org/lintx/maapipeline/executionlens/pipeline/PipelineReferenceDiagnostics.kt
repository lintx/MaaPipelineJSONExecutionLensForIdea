package org.lintx.maapipeline.executionlens.pipeline

internal data class PipelineReferenceIssue(
    val severity: PipelineDiagnosticSeverity,
    val message: String,
    val suggestion: String,
)

internal object PipelineReferenceDiagnostics {
    fun issue(reference: PipelineReference): PipelineReferenceIssue? = when {
        reference.isAnchorUnresolved -> PipelineReferenceIssue(
            PipelineDiagnosticSeverity.WARNING,
            when {
                reference.isAmbiguousTarget -> "Anchor 有多个运行时设置点，静态目标不确定"
                reference.anchorTarget?.isCleared == true -> "Anchor 已被清除"
                reference.anchorTarget == null -> "Anchor 未定义"
                reference.anchorTarget.targetNode == null -> "Anchor 的目标节点不存在"
                else -> "Anchor 的运行时目标不确定"
            },
            when {
                reference.isAmbiguousTarget -> "运行时最后设置生效；核对设置顺序，或加载日志查看本次绑定。"
                reference.anchorTarget?.isCleared == true -> "为 Anchor 设置已存在的目标节点，或移除 [Anchor] 前缀。"
                reference.anchorTarget == null -> "在项目中定义该 Anchor，或移除 [Anchor] 前缀。"
                reference.anchorTarget.targetNode == null -> "将 Anchor 映射到已存在的节点。"
                else -> "加载日志核对本次运行的 Anchor 绑定。"
            },
        )
        reference.isAmbiguousTarget -> PipelineReferenceIssue(
            PipelineDiagnosticSeverity.WARNING,
            "目标名称不唯一",
            "保持节点名称唯一，或改用唯一的节点名称。",
        )
        !reference.targetExists && !reference.isAnchor -> PipelineReferenceIssue(
            PipelineDiagnosticSeverity.ERROR,
            "目标节点不存在",
            when (reference.kind) {
                PipelineReferenceKind.TARGET, PipelineReferenceKind.BEGIN, PipelineReferenceKind.END ->
                    "检查字段中的节点名称，或先定义该节点。"
                else -> "检查节点名称拼写，或先定义该节点。"
            },
        )
        else -> null
    }
}
