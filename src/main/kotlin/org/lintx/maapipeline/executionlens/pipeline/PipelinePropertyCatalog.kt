package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class PipelinePropertyValue(
    val name: String,
    val value: JsonElement?,
    val defaultValue: JsonElement?,
    val inherited: Boolean,
    val resourceDefault: Boolean,
    val explanation: String,
) {
    val isDefault: Boolean get() = inherited || value != null && value == defaultValue
    val suffix: String get() = when {
        inherited -> if (resourceDefault) "（默认 · 资源配置）" else "（默认）"
        value != null && value == defaultValue -> "（与默认值相同）"
        else -> ""
    }
    val text: String get() = "$name：${value?.let(PipelineVisualModel::display) ?: "未设置"}$suffix"
}

internal object PipelinePropertyCatalog {
    private data class Definition(val defaultJson: String?, val help: String)
    private fun definition(default: String?, help: String) = Definition(default, help)

    private val common = linkedMapOf(
        "enabled" to definition("true", "是否参与识别和执行；false 时在其他节点的 next 中直接跳过。"),
        "rate_limit" to definition("1000", "一轮 next 识别至少耗时多少毫秒。识别本身耗时不足时才补足等待；0 表示不额外限速，不是只识别一次。"),
        "timeout" to definition("20000", "本节点 next 未命中时最多重复识别多少毫秒；-1 无限等待。超时进入本节点 on_error。当前节点的进入条件等待由上一节点控制。"),
        "max_hit" to definition("4294967295", "本节点允许识别成功的最多次数；默认 UINT_MAX，相当于不限制。达到限制后作为候选时跳过。"),
        "pre_delay" to definition("200", "本节点识别成功后、执行动作前的等待时间，单位毫秒。"),
        "post_delay" to definition("200", "本节点动作完成后、开始识别 next 前的等待时间，单位毫秒。"),
        "pre_wait_freezes" to definition("0", "动作前等待画面连续静止的时间，单位毫秒；0 不等待。对象形式可配置区域、阈值和超时。"),
        "post_wait_freezes" to definition("0", "动作后等待画面连续静止的时间，单位毫秒；0 不等待。"),
        "repeat" to definition("1", "动作执行次数；1 只执行一次。重复过程中单次失败不提前中止，以最后一次动作的结果为准。"),
        "repeat_delay" to definition("0", "第二次及之后每次动作前的等待时间，单位毫秒；仅 repeat > 1 时生效。"),
        "repeat_wait_freezes" to definition("0", "第二次及之后每次动作前等待画面连续静止的时间，单位毫秒；仅 repeat > 1 时生效。"),
        "focus" to definition("null", "配置节点通知和回调消息；null 表示不额外产生关注消息。"),
        "attach" to definition("{}", "附加配置，不直接改变节点执行逻辑；与资源默认 attach 按键合并。"),
        "next" to definition("[]", "动作完成后按顺序识别，仅执行第一个命中的候选节点；未命中时按 rate_limit 和 timeout 重试。"),
        "on_error" to definition("[]", "本节点动作失败，或本节点 next 识别超时后进入的错误分支。"),
        "anchor" to definition("{}", "动作完成后设置或清除运行时 Anchor；动作成功或失败都会应用。"),
    )

    fun resourceDefaults(node: PipelineNodeLocation, model: PipelineDocumentModel): JsonObject {
        var result = JsonObject()
        val source = node.sourceFile ?: model.file
        // 资源按加载顺序继承；后加载 Bundle 的默认值不追溯修改先前 Bundle 的节点。
        for ((file, raw) in model.defaultPipelines) {
            val parsed = PipelineVisualModel.parse(raw) ?: continue
            result = PipelineTaskContext.merge(result, parsed).asJsonObject
            if (source.path.startsWith(file.parent.path + "/")) break
        }
        return result
    }

    private fun commonDefaults(resource: JsonObject): JsonObject =
        resource.get("Default")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

    fun properties(node: PipelineNodeLocation, resource: JsonObject): List<PipelinePropertyValue> {
        val raw = PipelineVisualModel.parse(node.rawJson) ?: JsonObject()
        val defaults = commonDefaults(resource)
        val result = common.map { (name, definition) ->
            val fallback = defaults.get(name) ?: definition.defaultJson?.let(JsonParser::parseString)
            val explicit = raw.get(name)
            val value = if (name == "attach" && explicit?.isJsonObject == true && fallback?.isJsonObject == true)
                PipelineTaskContext.merge(fallback, explicit) else explicit ?: fallback
            PipelinePropertyValue(name, value, fallback, explicit == null, defaults.has(name), definition.help)
        }.toMutableList()
        val handled = common.keys + PipelineVisualModel.operationKeys(false) + PipelineVisualModel.operationKeys(true) + "desc"
        raw.entrySet().filter { it.key !in handled }.forEach { (name, value) ->
            result += PipelinePropertyValue(name, value, null, false, false, PipelineVisualModel.fieldHelp(name))
        }
        return result
    }

    fun help(name: String): String = common[name]?.help ?: PipelineVisualModel.fieldHelp(name)

    fun policySummary(node: PipelineNodeLocation, resource: JsonObject): String? = properties(node, resource)
        .filter { it.name in setOf("timeout", "rate_limit", "max_hit") && !it.inherited && it.value != it.defaultValue }
        .joinToString("，") { "${it.name}=${it.value}" }.takeIf { it.isNotEmpty() }

    fun policyValue(node: PipelineNodeLocation, resource: JsonObject, name: String): String =
        properties(node, resource).first { it.name == name }.value.toString()

    fun operationProperties(operation: PipelineOperation, resource: JsonObject): List<PipelinePropertyValue> {
        val definitions = operationDefinitions(operation)
        val section = PipelineTaskContext.merge(commonDefaults(resource),
            resource.get(operation.type)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()).asJsonObject
        val resourceOperation = PipelineVisualModel.operation(PipelineNodeLocation(operation.type,
            com.intellij.openapi.util.TextRange.EMPTY_RANGE, com.intellij.openapi.util.TextRange.EMPTY_RANGE,
            rawJson = section.toString()), operation.isAction)
        val configured = resourceOperation.parameters
        return (definitions.keys + configured.keys + operation.parameters.keys).map { name ->
            val spec = definitions[name]
            val fallback = configured[name] ?: spec?.defaultJson?.let(JsonParser::parseString)
            val explicit = operation.parameters[name]
            PipelinePropertyValue(name, explicit ?: fallback, fallback, explicit == null && fallback != null,
                name in configured, spec?.help ?: PipelineVisualModel.fieldHelp(name))
        }
    }

    private fun operationDefinitions(operation: PipelineOperation): Map<String, Definition> = linkedMapOf<String, Definition>().apply {
        fun add(name: String, default: String?, help: String = PipelineVisualModel.fieldHelp(name)) {
            put(name, definition(default, help))
        }
        fun region(name: String, default: String) {
            add(name, default)
            add("${name}_offset", "[0,0,0,0]")
        }
        fun results() { add("order_by", "\"Horizontal\""); add("index", "0") }
        if (!operation.isAction) {
            if (operation.type != "Reference") add("inverse", "false", "反转识别成功与失败的结果；不是反转每个像素的颜色。")
            if (operation.type !in setOf("And", "Or", "Reference")) region("roi", "[0,0,0,0]")
            when (operation.type) {
                "TemplateMatch" -> {
                    add("template", null); add("threshold", "0.7"); add("method", "5"); add("green_mask", "false"); results()
                }
                "FeatureMatch" -> {
                    add("template", null); add("count", "4"); add("detector", "\"SIFT\""); add("ratio", "0.6")
                    add("green_mask", "false"); results()
                }
                "ColorMatch" -> {
                    add("method", "4"); add("lower", null); add("upper", null); add("count", "1")
                    add("connected", "false"); results()
                }
                "OCR" -> {
                    add("expected", "[]", "匹配识别文本的正则列表；默认匹配所有结果。")
                    add("threshold", "0.3"); add("replace", "[]"); add("only_rec", "false")
                    add("model", "\"\""); add("color_filter", "\"\""); results()
                }
                "NeuralNetworkClassify", "NeuralNetworkDetect" -> {
                    add("model", null); add("labels", null); add("expected", "[]"); results()
                    if (operation.type == "NeuralNetworkDetect") add("threshold", "0.3")
                }
                "And" -> { add("all_of", null); add("box_index", "0") }
                "Or" -> add("any_of", null)
                "Custom" -> { add("custom_recognition", null); add("custom_recognition_param", "null") }
            }
        } else {
            when (operation.type) {
                "Click", "LongPress", "TouchDown", "TouchMove", "Scroll", "Custom" -> region("target", "true")
                "Swipe" -> { region("begin", "true"); region("end", "true") }
            }
            when (operation.type) {
                "Click", "LongPress", "Swipe" -> { add("contact", "0"); add("pressure", "1") }
                "TouchDown", "TouchMove" -> { add("contact", "0"); add("pressure", "0"); add("auto_up", "false") }
                "TouchUp" -> add("contact", "0")
            }
            when (operation.type) {
                "LongPress", "LongPressKey" -> add("duration", "1000")
                "Swipe" -> { add("duration", "200"); add("end_hold", "0"); add("only_hover", "false") }
                "MultiSwipe" -> add("swipes", null)
                "Scroll" -> { add("dx", "0"); add("dy", "0") }
                "ClickKey", "LongPressKey", "KeyDown", "KeyUp" -> add("key", null)
                "InputText" -> add("input_text", null)
                "StartApp", "StopApp" -> add("package", null)
                "Command" -> { add("exec", null); add("args", "[]"); add("detach", "false") }
                "Shell" -> { add("cmd", null); add("shell_timeout", "20000") }
                "Screencap" -> { add("filename", null, "未设置时使用 时间戳_节点名。"); add("format", "\"png\""); add("quality", "100") }
                "Custom" -> { add("custom_action", null); add("custom_action_param", "null") }
            }
            if (operation.type == "KeyDown") add("auto_up", "false")
            if (operation.type == "LongPressKey") add("key", null)
        }
    }
}
