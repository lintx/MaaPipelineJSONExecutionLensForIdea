package com.maapipeline.executionlens.pipeline

internal object PipelineConditionSummary {
    fun successorLoop(node: PipelineNodeLocation): String? {
        if (node.next.isEmpty()) return null
        val policy = node.executionPolicy
        val timeout = policy.timeout?.summary ?: "20000"
        val interval = policy.rateLimit?.summary ?: "1000"
        val limit = when (timeout.toLongOrNull()) {
            -1L -> "不限时"
            0L -> return "匹配 next（不等待重试）"
            null -> "超时 $timeout"
            else -> "超时 ${timeout}ms"
        }
        return "重试匹配 next（$limit，轮次间隔至少 ${interval}ms）"
    }

    fun entryCondition(node: PipelineNodeLocation): String =
        if (!node.enabled) "已禁用（enabled=false）" else node.recognition?.summary?.let {
            if (it == "DirectHit") "无条件执行" else "识别 $it 成功"
        } ?: "无条件执行"

    fun flowCondition(node: PipelineNodeLocation): String =
        if (!node.enabled) "已禁用（enabled=false）" else node.recognition?.let {
            it.readingSummary ?: compactCondition(it.summary)
        } ?: "无条件执行"

    fun flowCandidateCondition(reference: PipelineReference, condition: String? = null): String =
        condition ?: reference.target?.recognitionCondition ?: reference.target?.recognitionSummary?.let(::compactCondition)
            ?: if (reference.targetExists) "无条件执行" else "目标未解析"

    private fun compactCondition(summary: String): String = when (val type = summary.substringBefore('（').substringBefore('(').trim()) {
        "DirectHit" -> "无条件执行"
        "Custom" -> "自定义"
        else -> type
    }

    fun flowBranch(index: Int, field: String, condition: String): String {
        val prefix = if (field == "on_error") "on_error: " else ""
        val branch = if (condition == "无条件执行") {
            if (index == 0) condition else "else $condition"
        } else "${if (index == 0) "if" else "else if"} $condition"
        return prefix + branch
    }

    fun anchorCondition(condition: String, name: String, mayBeUnset: Boolean): String = when {
        !mayBeUnset -> condition
        condition == "无条件执行" -> "$name 已绑定"
        else -> "$name 已绑定 && $condition"
    }

    fun anchorChange(definition: PipelineAnchorDefinition, ownerName: String): String =
        if (definition.isCleared) "清除 Anchor：${definition.name}"
        else "绑定 Anchor：${definition.name} → ${definition.targetNodeName ?: ownerName}"

    fun jumpBackHint(parentName: String?, field: String): String = if (field == "on_error") {
        "JumpBack：on_error 错误处理链不触发回跳。"
    } else {
        "JumpBack：此节点及其 next 链正常结束后，从${parentName?.let { "父节点 $it 的" } ?: "父节点的"} next 第一项重新尝试；不重新执行父节点动作。"
    }

    fun action(node: PipelineNodeLocation): String? =
        node.action?.summary?.let { "执行动作 $it" }

    fun anchor(node: PipelineNodeLocation): String? =
        node.anchor?.summary?.let { "设置 Anchor $it" }

    fun candidateCondition(reference: PipelineReference, recognitionSummary: String? = null): String {
        val target = reference.target
        val recognition = recognitionSummary ?: target?.recognitionSummary
        return when {
            recognition == "DirectHit" -> "无条件执行"
            recognition != null -> "识别 $recognition 成功"
            reference.isAnchor -> "运行时 Anchor ${reference.targetName} 已解析"
            !reference.targetExists -> "目标 ${reference.targetName}"
            else -> "无条件执行"
        }
    }

    fun candidateLabel(reference: PipelineReference, recognitionSummary: String? = null, fieldName: String = "next"): String = buildString {
        if (!reference.targetExists && !reference.isAnchor) {
            append("目标 ${reference.targetName} 未解析")
        } else {
            append(candidateCondition(reference, recognitionSummary))
        }
        if (reference.isJumpBack && fieldName != "on_error") append("，子链结束后从父 next 首项重试")
        if (reference.isCrossFile) append("，跨文件")
        if (reference.isAmbiguousTarget) append("，名称不唯一")
        if (reference.isAnchorUnresolved) append("，Anchor 未解析")
    }

    fun candidatePrefix(index: Int, fieldName: String): String = when (fieldName) {
        "on_error" -> if (index == 0) "on_error: if" else "on_error: else if"
        else -> if (index == 0) "if" else "else if"
    }
}
