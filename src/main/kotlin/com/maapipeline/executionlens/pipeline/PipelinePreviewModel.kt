package com.maapipeline.executionlens.pipeline

internal data class PipelinePreviewAnnotation(
    val number: Int,
    val operation: PipelineOperation,
    val key: String,
    val region: PipelineRegionInfo,
    val sourceNumber: Int? = null,
) {
    val dynamic: Boolean get() = region.coordinates == null
    val title: String get() = "$number. ${operation.type} · $key" + when {
        !dynamic -> if (operation.isAction) " 固定目标" else " 搜索区域"
        sourceNumber != null -> " ← $sourceNumber. ${region.resultOperation?.type} 的识别结果"
        else -> " · 运行时结果"
    }
    val imageLabel: String get() = "$number ${operation.type} $key" + when {
        !dynamic -> ""
        sourceNumber != null -> " ← $sourceNumber 结果（标注来源区）"
        else -> " 来源搜索区"
    }

    fun imageLines(): List<String> = buildList {
        add(imageLabel)
        if (dynamic) {
            region.searchCoordinates?.let { add("来源 roi=$it") }
        } else add("$key=${region.coordinates}")
        if (!operation.isAction) {
            operation.parameters["expected"]?.let { add("匹配文本：${PipelineVisualModel.display(it)}") }
            operation.parameters["template"]?.let { add("模板：${PipelineVisualModel.display(it)}") }
            PipelineVisualModel.colors(operation)?.let { colors ->
                colors.ranges.forEach { add("${colors.space}：${it.lower} → ${it.upper}") }
                if (colors.ranges.isEmpty()) add("${colors.space}：lower=${operation.text("lower")}，upper=${operation.text("upper")}")
            }
            operation.parameters["custom_recognition"]?.let { add("自定义：${PipelineVisualModel.display(it)}") }
            operation.parameters["model"]?.let { add("模型：${PipelineVisualModel.display(it)}") }
        }
        operation.parameters["${key}_offset"]?.let { add("${key}_offset=${PipelineVisualModel.display(it)}") }
    }

    fun details(): List<String> = buildList {
        if (!dynamic) {
            add("${if (operation.isAction) "固定目标" else "搜索范围"}：${region.summary}")
            if (!operation.isAction) add("此框限定查找范围；识别得到的目标框可以只是其中一部分。")
            add(region.explanation)
        } else {
            add("结果来源：${region.summary}")
            if (region.resultSteps.isNotEmpty()) add("选择链：${region.resultSteps.joinToString(" → ")}")
            region.searchCoordinates?.let { add("来源搜索范围：$it；这是来源识别器查找目标的位置，整个范围不等于动作 target。") }
            add("实际${if (operation.isAction) "动作目标" else "识别区域"}框：运行时识别后确定，预览未绘制固定 $key 框。" +
                if (sourceNumber != null) "选择此项会高亮 $sourceNumber 的来源搜索区。" else "")
        }
        PipelineVisualModel.mode(operation)?.let { (mode, explanation) -> add("模式：$mode。$explanation") }
        operation.parameters.filterKeys { it != key && it !in setOf("all_of", "any_of") }.forEach { (name, value) ->
            add("$name：${PipelineVisualModel.integers(value)?.toString() ?: PipelineVisualModel.display(value)}")
            if (name.endsWith("_offset")) add(PipelineVisualModel.fieldHelp(name))
        }
        if (dynamic) region.resultOperation?.let { source ->
            PipelineVisualModel.mode(source)?.let { (mode, explanation) -> add("来源模式：$mode。$explanation") }
            source.parameters.filterKeys { it !in setOf("roi", "roi_offset", "all_of", "any_of") }.forEach { (name, value) ->
                add("来源 $name：${PipelineVisualModel.display(value)}")
            }
        }
    }
}

internal object PipelinePreviewModel {
    fun annotations(operations: List<PipelineOperation>, resolve: (String) -> PipelineNodeLocation? = { null }): List<PipelinePreviewAnnotation> {
        val annotations = mutableListOf<PipelinePreviewAnnotation>()
        operations.forEach { operation ->
            if (operation.isAction && operation.type !in setOf("Click", "LongPress", "Swipe", "TouchDown", "TouchMove", "Scroll", "Custom")) return@forEach
            if (!operation.isAction && operation.type in setOf("DirectHit", "Reference")) return@forEach
            val keys = if (operation.isAction && operation.type == "Swipe") listOf("begin", "end")
                else listOf(if (operation.isAction) "target" else "roi")
            keys.forEach { key ->
                annotations += PipelinePreviewAnnotation(annotations.size + 1, operation, key,
                    PipelineVisualModel.region(operation, key, resolve))
            }
        }
        return annotations.map { annotation ->
            // 编号对应语义项；相同几何范围可以合并描边，但动作来源不能因此消失。
            val source = annotation.region.resultOperation?.let { result ->
                annotations.filter { !it.operation.isAction && it.operation == result }.singleOrNull()
            }
            annotation.copy(sourceNumber = source?.number)
        }
    }
}
