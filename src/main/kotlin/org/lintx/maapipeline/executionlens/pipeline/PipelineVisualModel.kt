package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.Color
import java.awt.Rectangle

internal data class PipelineOperation(
    val type: String,
    val parameters: Map<String, JsonElement>,
    val children: List<PipelineOperation> = emptyList(),
    val reference: String? = null,
    val source: PipelineNodeLocation? = null,
    val isAction: Boolean = false,
    val ownerPath: List<String> = emptyList(),
    val parameterPaths: Map<String, List<String>> = emptyMap(),
    val roiHints: Map<String, PipelineRoiHint> = emptyMap(),
) {
    val title: String get() {
        val base = when (type) {
        "DirectHit" -> if (text("inverse") == "true") "Not(DirectHit) · 不命中" else "无条件执行"
        "DoNothing" -> "不执行动作"
        "And" -> "And · 所有条件成立"
        "Or" -> "Or · 首个命中的条件"
        "Reference" -> "引用条件：$reference"
        else -> type
        }
        return if (!isAction && type != "DirectHit" && text("inverse") == "true") "Not($base)" else base
    }
    fun text(name: String): String? = parameters[name]?.let { PipelineVisualModel.display(it) }
}

internal data class PipelineRegionInfo(
    val summary: String,
    val explanation: String,
    val coordinates: List<Int>? = null,
    val searchCoordinates: List<Int>? = null,
    val resultSteps: List<String> = emptyList(),
    val resultOperation: PipelineOperation? = null,
    val fromComment: Boolean = false,
) {
    fun rectangle(width: Int, height: Int): Rectangle? = coordinates?.let { rectangle(it, width, height) }

    companion object {
        fun rectangle(values: List<Int>, width: Int, height: Int): Rectangle? {
            if (values.size !in setOf(2, 4)) return null
            var left = values[0].let { if (it < 0) width + it else it }
            var top = values[1].let { if (it < 0) height + it else it }
            var spanX = values.getOrElse(2) { 1 }
            var spanY = values.getOrElse(3) { 1 }
            if (spanX == 0) spanX = width - left
            if (spanY == 0) spanY = height - top
            if (spanX < 0) { left += spanX; spanX = -spanX }
            if (spanY < 0) { top += spanY; spanY = -spanY }
            return Rectangle(left, top, spanX, spanY).intersection(Rectangle(0, 0, width, height))
                .takeIf { it.width > 0 && it.height > 0 }
        }
    }
}

internal data class PipelineColorRange(val lower: List<Int>, val upper: List<Int>)

internal data class PipelineColorSpec(val method: Int, val ranges: List<PipelineColorRange>, val inverse: Boolean = false) {
    val space: String get() = when (method) { 4 -> "RGB"; 40 -> "HSV"; 6 -> "GRAY"; else -> "OpenCV $method" }
    val supported: Boolean get() = method in setOf(4, 40, 6)
    val explanation: String get() = when (method) {
        4 -> "method=4：BGR 转 RGB。R/G/B 各为 0–255；每个通道同时落在 lower 与 upper 之间才匹配。"
        40 -> "method=40：BGR 转 HSV。H 为 0–179，S/V 为 0–255。各通道取闭区间；跨红色色相需分成两组范围，不会自动环绕。"
        6 -> "method=6：BGR 转灰度。灰度为 0–255，按亮度范围匹配，无法据此判断原图色相。"
        else -> "method=$method：OpenCV 颜色空间转换。当前保留原始范围，不将此空间的通道值冒充 RGB。"
    } + if (inverse) " inverse=true 反转的是识别成功结果，不是逐像素颜色范围。" else ""

    fun samples(range: PipelineColorRange): List<Color> {
        if (!supported) return emptyList()
        val channels = if (method == 6) 1 else 3
        if (range.lower.size != channels || range.upper.size != channels) return emptyList()
        val maxima = if (method == 40) listOf(179, 255, 255) else List(channels) { 255 }
        if ((0 until channels).any { range.lower[it] > range.upper[it] || range.upper[it] < 0 || range.lower[it] > maxima[it] }) return emptyList()
        fun values(channel: Int): List<Int> {
            val low = range.lower[channel].coerceIn(0, maxima[channel])
            val high = range.upper[channel].coerceIn(0, maxima[channel])
            return (0..5).map { low + (high - low) * it / 5 }.distinct()
        }
        if (method == 6) return (0..35).map {
            val gray = range.lower[0].coerceIn(0, 255) +
                (range.upper[0].coerceIn(0, 255) - range.lower[0].coerceIn(0, 255)) * it / 35
            Color(gray, gray, gray)
        }
        return values(0).flatMap { first -> values(1).flatMap { second -> values(2).map { third ->
            if (method == 40) Color.getHSBColor(first / 180f, second / 255f, third / 255f)
            else Color(first, second, third)
        } } }
    }
}

internal object PipelineVisualModel {
    private val recognitionKeys = setOf("roi", "roi_offset", "template", "threshold", "method", "green_mask", "order_by", "index",
        "detector", "ratio", "count", "lower", "upper", "connected", "expected", "replace", "only_rec", "model", "labels",
        "color_filter", "custom_recognition", "custom_recognition_param", "all_of", "any_of", "box_index", "sub_name", "inverse")
    private val actionKeys = setOf("target", "target_offset", "begin", "begin_offset", "end", "end_offset", "duration", "swipes",
        "contact", "pressure", "key", "input_text", "package", "exec", "args", "detach", "cmd", "shell_timeout", "filename",
        "custom_action", "custom_action_param", "dx", "dy", "only_hover", "end_hold", "auto_up", "format", "quality")

    fun parse(raw: String): JsonObject? = try { JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject }
        catch (_: com.google.gson.JsonParseException) { null }

    fun operationKeys(isAction: Boolean): Set<String> =
        (if (isAction) actionKeys else recognitionKeys) + if (isAction) "action" else "recognition"

    fun operation(node: PipelineNodeLocation, isAction: Boolean = false,
        resolve: (String) -> PipelineNodeLocation? = { null }): PipelineOperation =
        operation(parse(node.rawJson) ?: JsonObject(), node, isAction, resolve, setOf(node.name), 0,
            hints = PipelinePreviewHints.read(node.previewSource ?: node.rawJson))

    private fun operation(owner: JsonObject, source: PipelineNodeLocation?, isAction: Boolean,
        resolve: (String) -> PipelineNodeLocation?, visited: Set<String>, depth: Int,
        ownerPath: List<String> = emptyList(), hints: Map<List<String>, PipelineObjectHints> = emptyMap()): PipelineOperation {
        val field = owner.get(if (isAction) "action" else "recognition")
        val typed = field?.takeIf { it.isJsonObject }?.asJsonObject
            ?: owner.takeIf { it.has("type") }
        val type = typed?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            ?: field?.takeIf { it.isJsonPrimitive }?.asString ?: if (isAction) "DoNothing" else "DirectHit"
        val parameterObject = typed?.get("param")?.takeIf { it.isJsonObject }?.asJsonObject
        val keys = if (isAction) actionKeys else recognitionKeys
        val parameters = linkedMapOf<String, JsonElement>()
        val paths = linkedMapOf<String, List<String>>()
        val typedPath = if (typed === owner) ownerPath else ownerPath + if (isAction) "action" else "recognition"
        owner.entrySet().filter { it.key in keys }.forEach { parameters[it.key] = it.value; paths[it.key] = ownerPath + it.key }
        typed?.entrySet()?.filter { it.key !in setOf("type", "param") }?.forEach {
            parameters[it.key] = it.value; paths[it.key] = typedPath + it.key
        }
        parameterObject?.entrySet()?.forEach { parameters[it.key] = it.value; paths[it.key] = typedPath + listOf("param", it.key) }
        val children = if (!isAction && depth < 16) {
            val items = parameters[if (type == "And") "all_of" else if (type == "Or") "any_of" else ""]
            val childrenKey = if (type == "And") "all_of" else "any_of"
            items?.takeIf { it.isJsonArray }?.asJsonArray?.mapIndexed { index, item ->
                if (item.isJsonObject) operation(item.asJsonObject, source, false, resolve, visited, depth + 1,
                    paths[childrenKey].orEmpty() + index.toString(), hints)
                else {
                    val name = item.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    val target = if (name !in visited) resolve(name) else null
                    if (target == null) PipelineOperation("Reference", emptyMap(), reference = name, source = source)
                    else operation(parse(target.rawJson) ?: JsonObject(), target, false, resolve, visited + name, depth + 1,
                        hints = PipelinePreviewHints.read(target.previewSource ?: target.rawJson)).copy(reference = name)
                }
            }.orEmpty()
        } else emptyList()
        val roiHints = listOf(ownerPath, typedPath, typedPath + "param").distinct().flatMap { hints[it]?.regions.orEmpty() }
            .associateBy { it.key ?: "roi" }
        return PipelineOperation(type, parameters, children, source = source, isAction = isAction,
            ownerPath = ownerPath, parameterPaths = paths, roiHints = roiHints)
    }

    fun preview(raw: String): String? {
        return PipelinePreviewHints.read(raw)[emptyList()]?.preview?.takeIf { it.isNotBlank() }
    }

    fun colors(operation: PipelineOperation): PipelineColorSpec? {
        if (operation.type != "ColorMatch") return null
        fun groups(element: JsonElement?): List<List<Int>> = when {
            element?.isJsonArray != true -> emptyList()
            element.asJsonArray.firstOrNull()?.isJsonArray == true -> element.asJsonArray.mapNotNull(::integers)
            else -> listOfNotNull(integers(element))
        }
        val lower = groups(operation.parameters["lower"])
        val upper = groups(operation.parameters["upper"])
        return PipelineColorSpec(operation.text("method")?.toIntOrNull() ?: 4,
            lower.zip(upper) { low, high -> PipelineColorRange(low, high) }, operation.text("inverse") == "true")
    }

    fun region(operation: PipelineOperation, key: String = if (operation.isAction) "target" else "roi",
        resolve: (String) -> PipelineNodeLocation? = { null }): PipelineRegionInfo {
        val value = operation.parameters[key]
        val offset = integers(operation.parameters["${key}_offset"])
        val actual = integers(value)
        val example = operation.roiHints[key]?.takeIf { actual == null }
        val fixed = actual ?: example?.coordinates
        fun shifted(coordinates: List<Int>): List<Int> = if (offset?.size == 4 && coordinates.size == 4)
            coordinates.zip(offset) { original, delta -> original + delta } else coordinates
        if (fixed != null && fixed.size in setOf(2, 4)) {
            val coordinates = shifted(fixed)
            return PipelineRegionInfo((if (example != null) "注释示意 " else "") + coordinates.toString(), "$key=$fixed" +
                (offset?.let { "；${key}_offset=$it，四个分量相加" } ?: "") +
                "。坐标在运行时截图坐标系中解释；负坐标从右/下边缘计算。" +
                if (example != null) " 该框来自 @roi 注释，仅为预览示意，不改变运行时参数。" else "", coordinates, fromComment = example != null)
        }
        if (value?.isJsonArray == true) return PipelineRegionInfo(value.toString(),
            "$key 是多个滑动途经点/区域；每项按照 target 规则解释，各段的偏移在运行时分别应用。")
        if (!operation.isAction && value == null) {
            val coordinates = shifted(listOf(0, 0, 0, 0))
            return PipelineRegionInfo(if (offset == null) "全屏" else coordinates.toString(),
                "roi 默认 [0, 0, 0, 0]，表示全屏；roi_offset 四个分量相加。", coordinates)
        }
        val name = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val own = name == null
        val source = if (own) operation.source else if (name?.startsWith("[Anchor]") == true) null else resolve(name.orEmpty())
        val recognized = source?.let { operation(it, resolve = resolve) }
        val resultSteps = mutableListOf<String>()
        fun resultOperation(condition: PipelineOperation?): PipelineOperation? {
            condition ?: return null
            if (condition.type == "And") {
                val index = condition.text("box_index")?.toIntOrNull() ?: 0
                resultSteps += "And.box_index=$index（第 ${index + 1} 项）"
                return resultOperation(condition.children.getOrNull(index))
            }
            if (condition.type == "Or") {
                resultSteps += "Or 首个成功子条件的结果（运行时确定）"
                return null
            }
            if (condition.type in setOf("Reference", "DirectHit")) {
                resultSteps += if (condition.type == "DirectHit") "DirectHit（无图像匹配框）" else "引用条件 ${condition.reference}（尚未解析）"
                return null
            }
            resultSteps += "${condition.type} 的识别结果框"
            return condition
        }
        fun searchRegion(condition: PipelineOperation?): List<Int>? {
            condition ?: return null
            val coordinates = integers(condition.parameters["roi"])
                ?: if (condition.parameters["roi"] == null) listOf(0, 0, 0, 0) else return null
            val delta = integers(condition.parameters["roi_offset"])
            return if (delta?.size == 4 && coordinates.size == 4) coordinates.zip(delta) { original, change -> original + change } else coordinates
        }
        val result = resultOperation(recognized)
        val search = searchRegion(result)
        val origin = when {
            name?.startsWith("[Anchor]") == true -> "Anchor ${name.removePrefix("[Anchor]")} 的识别结果"
            name != null -> "$name 的识别结果"
            else -> "本节点识别结果"
        }
        val searchText = search?.let { "；搜索区域 $it" }.orEmpty()
        val chain = resultSteps.takeIf { it.isNotEmpty() }?.joinToString(" → ").orEmpty()
        return PipelineRegionInfo(origin + searchText, "$key 来源：$origin。" +
            chain.takeIf { it.isNotEmpty() }?.let { "结果选择：$it。" }.orEmpty() + "实际 box 由运行时匹配决定，" +
            "搜索区域不能视为已知的匹配框；引用节点或 Anchor 没有识别结果时失败。" +
            (offset?.let { " ${key}_offset=$it，在结果框上逐分量偏移。" } ?: "") +
            if (recognized?.type == "DirectHit") " DirectHit 未进行图像匹配，请显式设置动作 target。" else "",
            searchCoordinates = search, resultSteps = resultSteps, resultOperation = result)
    }

    fun integers(element: JsonElement?): List<Int>? = if (element?.isJsonArray == true)
        try { element.asJsonArray.map { it.asInt } } catch (_: RuntimeException) { null } else null

    fun display(element: JsonElement): String = if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else element.toString()

    fun mode(operation: PipelineOperation): Pair<String, String>? = when (operation.type) {
        "TemplateMatch" -> {
            val method = operation.text("method")?.toIntOrNull() ?: 5
            val description = when (method) {
                0 -> "TM_SQDIFF：平方差，越低越匹配。"
                1 -> "TM_SQDIFF_NORMED：归一化平方差，越低越匹配。"
                2 -> "TM_CCORR：相关匹配，对亮度和颜色敏感。"
                3 -> "TM_CCORR_NORMED：归一化相关；颜色敏感，较亮模板适用。"
                4 -> "TM_CCOEFF：相关系数，对整体亮度变化较稳健。"
                5 -> "TM_CCOEFF_NORMED：归一化相关系数；对光照变化较稳健，默认模式。"
                10001 -> "反转的 TM_SQDIFF_NORMED：越高越匹配，颜色敏感，适合精确找图。"
                else -> "OpenCV TemplateMatchModes=$method。"
            }
            ("method=$method" + if (operation.text("method") == null) " · 协议默认" else "") to description
        }
        "ColorMatch" -> colors(operation)?.let { ("method=${it.method} · ${it.space}" +
            if (operation.text("method") == null) " · 协议默认" else "") to it.explanation }
        "FeatureMatch" -> {
            val detector = operation.text("detector") ?: "SIFT"
            detector to when (detector) {
                "ORB" -> "速度快，支持旋转，缺乏尺度不变性；适合同尺寸目标。"
                "BRISK" -> "支持尺度和旋转变化，适合实时识别。"
                "AKAZE" -> "支持尺度和旋转变化，平衡速度与精度。"
                "KAZE" -> "支持尺度和旋转变化，边缘保持较好。"
                else -> "SIFT 为默认特征检测器，支持尺度和旋转变化，需要足够纹理。"
            }
        }
        "OCR" -> if (operation.text("only_rec") == "true") "only_rec=true" to "仅识别：不先检测文字，ROI 必须精确包围目标文本。"
            else "检测并识别" to "先检测文字区域再识别，expected 用于筛选结果，支持正则表达式。"
        "Click", "LongPress" -> "结果框 / 固定目标" to "target 默认采用本节点识别框；固定 [x,y] 是点，[x,y,w,h] 在区域内随机选点，中心概率高。"
        "And" -> "全部命中" to "顺序执行全部子条件；box_index（默认 0）决定输出哪个子识别的框。"
        "Or" -> "首个命中" to "顺序尝试，首个成功即结束；输出该子识别的框。"
        else -> null
    }

    fun actionHelp(operation: PipelineOperation): String = "action · ${operation.type}：" + when (operation.type) {
        "DoNothing" -> "不执行操作，仍继续处理 Anchor 和 next。"
        "Click" -> "点击 target 指定的目标；默认使用本节点的识别结果。"
        "LongPress" -> "按住 target 指定的目标，持续 duration 毫秒后松开。"
        "Swipe" -> "从 begin 滑动到 end；duration 控制滑动时长，end_hold 控制终点停留。"
        "MultiSwipe" -> "执行 swipes 中配置的多段滑动。"
        "TouchDown" -> "在 target 处按下指定触点；后续 TouchMove / TouchUp 可继续控制该触点。"
        "TouchMove" -> "将指定触点移动到 target。"
        "TouchUp" -> "松开 contact 指定的触点。"
        "Scroll" -> "在 target 指定的位置滚动，dx / dy 控制水平和垂直位移。"
        "ClickKey" -> "按下并松开 key 指定的按键。"
        "LongPressKey" -> "按住 key 指定的按键，持续 duration 毫秒后松开。"
        "KeyDown" -> "按下 key 指定的按键；配合 KeyUp 或 auto_up 控制松开。"
        "KeyUp" -> "松开 key 指定的按键。"
        "InputText" -> "输入 input_text 中的文本。"
        "StartApp" -> "启动 package 指定的应用。"
        "StopApp" -> "停止 package 指定的应用。"
        "Command" -> "运行 exec 指定的程序并传入 args；detach 控制是否等待程序结束。插件只展示配置。"
        "Shell" -> "执行 cmd 指定的 Shell 命令；shell_timeout 控制等待上限。插件只展示配置。"
        "Screencap" -> "截图并按 filename、format 和 quality 保存。"
        "Custom" -> "调用注册的自定义动作 ${operation.text("custom_action") ?: "（未指定）"}；custom_action_param 原样传入，具体行为由实现决定。"
        else -> "识别成功后交给控制器执行；具体行为由该动作实现决定。"
    }

    fun fieldHelp(name: String): String = when (name) {
        "desc", "说明" -> "desc：节点用途与业务意图的说明，不影响执行。"
        "recognition", "条件" -> "recognition：进入节点前的识别条件；满足后执行动作。缺省为 DirectHit。"
        "action", "动作" -> "action：识别成功后执行的动作；缺省为 DoNothing。"
        "anchor", "锚点" -> "anchor：动作结束后绑定、覆盖或清除命名节点引用；空字符串清除绑定。"
        "roi" -> "roi：[x,y,w,h] 搜索区域，或之前节点的识别框；[0,0,0,0] 表示全屏。"
        "target", "begin", "end" -> "$name：动作目标；true 使用本节点识别结果，字符串引用之前节点结果，数组表示固定坐标。"
        "next" -> "next：按列表顺序尝试识别，执行首个成功的节点。"
        "on_error" -> "on_error：动作失败或 next 超时后尝试的错误处理分支；不触发 JumpBack 回跳。"
        "inverse" -> "inverse：反转识别成功/失败的结果，不是逐像素反选颜色。"
        "threshold" -> "threshold：匹配分数阈值；模板默认 0.7，多模板可以分别设置。"
        "count" -> "count：匹配所需的最少像素或特征点数量。"
        "order_by" -> "order_by：对匹配结果排序；结合 index 选取结果。"
        "index" -> "index：从排序后的结果选择一个；负数从末尾计数。"
        "box_index" -> "box_index：And 输出的子识别框序号，默认 0。"
        "green_mask" -> "green_mask：忽略模板中 RGB(0,255,0) 的纯绿色像素。"
        "connected" -> "connected：只统计颜色过滤后相连的最大块，否则统计所有满足范围的像素。"
        "roi_offset", "target_offset", "begin_offset", "end_offset" -> "$name：在原区域上逐分量相加的 [x,y,w,h] 偏移；不是新的绝对坐标。"
        "duration" -> "duration：动作持续时间，单位毫秒；多段滑动可分别指定。"
        "end_hold" -> "end_hold：滑动结束后继续按住的时长，单位毫秒。"
        "only_hover" -> "only_hover：只移动，不按下触点；具体效果取决于控制器。"
        "contact" -> "contact：触点编号；Android 原生控制器表示手指编号，其它控制器按各自协议解释。"
        "pressure" -> "pressure：触控压力值；支持情况取决于控制器。"
        "auto_up" -> "auto_up：动作结束后是否自动抬起相应触点或按键。"
        "custom_action" -> "custom_action：已注册的自定义动作名称；声明导航可打开其 Run/run 或注册的回调实现。"
        "custom_recognition" -> "custom_recognition：已注册的自定义识别器名称；声明导航可打开其 Run/run 或回调实现。"
        "custom_action_param", "custom_recognition_param" -> "$name：原样传给自定义实现的参数，其语义由该实现定义。"
        "expected" -> "expected：OCR 的目标文本/正则表达式，或神经网络识别的预期分类标签/序号。"
        "replace" -> "replace：OCR 识别结果的替换规则，在匹配 expected 之前应用。"
        "model" -> "model：OCR 或神经网络模型名称；结果还会受模型本身影响。"
        "color_filter" -> "color_filter：使用指定 ColorMatch 节点的颜色范围对图像二值化，再进行 OCR。"
        "input_text" -> "input_text：要输入的文本。"
        "key" -> "key：要按下的按键码；编码含义取决于所用控制器。"
        "dx", "dy" -> "$name：滚动的水平/垂直位移，方向及支持情况取决于控制器。"
        "exec", "args" -> "$name：Command 的可执行文件及参数，插件只显示，不执行。"
        "cmd", "shell_timeout" -> "$name：Shell 命令及超时设置；插件只显示，不执行。"
        "filename", "format", "quality" -> "$name：Screencap 的输出文件、编码格式或编码质量。"
        else -> "$name：Pipeline 原始参数。"
    }
}
