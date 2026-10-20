package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class PipelineTaskDefinition(
    val id: String,
    val label: String,
    val description: String,
    val entry: String,
    val path: String,
    val options: List<String>,
)

data class PipelineOptionCase(
    val name: String,
    val label: String,
    val path: String,
    val options: List<String>,
    val description: String = "",
)

data class PipelineTaskOption(
    val name: String,
    val label: String,
    val path: String,
    val cases: List<PipelineOptionCase>,
    val defaults: List<String>,
    val options: List<String>,
    val inputs: List<PipelineTaskInput>,
    val type: String = "",
    val description: String = "",
    val searchText: String = "",
)

data class PipelineTaskInput(
    val name: String,
    val label: String,
    val defaultValue: String,
    val pipelineType: String,
    val verify: String,
    val description: String = "",
)

internal data class PipelineTaskDefinitions(
    val tasks: List<PipelineTaskDefinition>,
    val options: List<PipelineTaskOption>,
)

internal object PipelineTaskContext {
    fun parse(text: String): PipelineTaskDefinitions {
        val root = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
            ?: return PipelineTaskDefinitions(emptyList(), emptyList())
        val tasks = root.getAsJsonArrayOrNull("task")?.mapIndexedNotNull { index, value ->
            val task = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val entry = task.string("entry")
            if (entry.isEmpty()) return@mapIndexedNotNull null
            PipelineTaskDefinition(
                task.string("name").ifEmpty { task.string("id").ifEmpty { "entry:$entry:$index" } },
                task.string("label"), task.string("description"), entry, "/task/$index", task.strings("option"),
            )
        }.orEmpty().toMutableList()
        if (tasks.isEmpty() && root.string("entry").isNotEmpty()) {
            tasks += PipelineTaskDefinition(root.string("name").ifEmpty { root.string("entry") },
                root.string("label"), root.string("description"), root.string("entry"), "", root.strings("option"))
        }
        val options = root.get("option")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.mapNotNull { (name, value) ->
            val option = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val path = "/option/${escape(name)}"
            PipelineTaskOption(
                name, option.string("label"), path,
                option.getAsJsonArrayOrNull("cases")?.mapIndexedNotNull { index, item ->
                    val case = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
                    PipelineOptionCase(case.string("name"), case.string("label"), "$path/cases/$index", case.strings("option"),
                        case.string("description"))
                }.orEmpty(),
                option.strings("default_case"), option.strings("option"),
                option.getAsJsonArrayOrNull("inputs")?.mapNotNull { item ->
                    val input = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    PipelineTaskInput(input.string("name"), input.string("label"), input.string("default"),
                        input.string("pipeline_type"), input.string("verify"), input.string("description"))
                }.orEmpty(), option.string("type"), option.string("description"),
            )
        }.orEmpty()
        return PipelineTaskDefinitions(tasks, options)
    }

    fun activePaths(
        taskPath: String,
        roots: List<String>,
        options: List<PipelineTaskOption>,
        selections: Map<String, List<String>>,
    ): Set<String> {
        val result = linkedSetOf(taskPath)
        val byName = options.associateBy { it.name }
        val visited = mutableSetOf<String>()
        fun visit(name: String) {
            if (!visited.add(name)) return
            val option = byName[name] ?: return
            result += option.path
            option.options.forEach(::visit)
            val chosen = selections[name] ?: option.defaults
            option.cases.filter { it.name in chosen }.forEach { case ->
                result += case.path
                case.options.forEach(::visit)
            }
        }
        roots.forEach(::visit)
        return result
    }

    fun matches(query: String, vararg values: String): Boolean {
        val haystack = values.joinToString(" ").lowercase()
        return query.trim().split(Regex("\\s+")).all { it.lowercase() in haystack }
    }

    fun optionSearchText(roots: List<String>, options: List<PipelineTaskOption>): String {
        val byName = options.associateBy { it.name }
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(roots)
        val texts = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!visited.add(name)) continue
            val option = byName[name] ?: continue
            texts += option.searchText.ifEmpty {
                listOf(option.name, option.label, option.description).joinToString(" ") + " " +
                    option.cases.joinToString(" ") { "${it.name} ${it.label} ${it.description}" } + " " +
                    option.inputs.joinToString(" ") { "${it.name} ${it.label} ${it.description}" }
            }
            // 搜索覆盖所有可配置分支，不能只收录当前默认选中的子选项。
            pending.addAll(option.options)
            option.cases.forEach { pending.addAll(it.options) }
        }
        return texts.joinToString("\n")
    }

    fun validSelections(options: List<PipelineTaskOption>, selections: Map<String, List<String>>): Map<String, List<String>> {
        val byName = options.associateBy { it.name }
        return selections.mapNotNull { (name, chosen) ->
            val option = byName[name] ?: return@mapNotNull null
            name to if (chosen.any { value -> option.cases.none { it.name == value } }) option.defaults else chosen
        }.toMap()
    }

    fun activeOverrides(entry: PipelineEntryLocation, overrides: List<PipelineOverrideLocation>,
        selections: Map<String, List<String>>): List<PipelineOverrideLocation> {
        val paths = activePaths(entry.contextPath, entry.optionNames, entry.taskOptions, selections).toList()
        return paths.flatMap { path -> overrides.filter { it.contextPath == path } }
    }

    fun possibleOverrides(entry: PipelineEntryLocation, overrides: List<PipelineOverrideLocation>): List<PipelineOverrideLocation> {
        val paths = activePaths(entry.contextPath, entry.optionNames, entry.taskOptions,
            entry.taskOptions.associate { it.name to it.cases.map(PipelineOptionCase::name) })
        return overrides.filter { it.contextPath in paths }
    }

    fun overrideCondition(entry: PipelineEntryLocation, override: PipelineOverrideLocation): String {
        for (option in entry.taskOptions) {
            val case = option.cases.firstOrNull { it.path == override.contextPath }
            if (case != null) return "${option.label.ifEmpty { option.name }} = ${case.label.ifEmpty { case.name }}"
            if (option.path == override.contextPath) return option.label.ifEmpty { option.name }
        }
        return "任务 ${entry.label.ifEmpty { entry.taskId }}"
    }

    fun merge(base: JsonElement, patch: JsonElement): JsonElement {
        if (!base.isJsonObject || !patch.isJsonObject) return patch.deepCopy()
        val result = base.deepCopy().asJsonObject
        patch.asJsonObject.entrySet().forEach { (name, value) ->
            val previous = result.get(name)
            val merged = previous?.let { merge(it, value) } ?: value.deepCopy()
            if (name in setOf("recognition", "action") && previous?.isJsonPrimitive == true && merged.isJsonObject &&
                !merged.asJsonObject.has("type")) merged.asJsonObject.addProperty("type", previous.asString)
            result.add(name, merged)
        }
        return result
    }

    fun substituteInputs(value: JsonElement, option: PipelineTaskOption?, inputs: Map<String, String>): JsonElement {
        val parameters = option?.inputs.orEmpty().associateBy { it.name }
        fun visit(element: JsonElement): JsonElement = when {
            element.isJsonObject -> JsonObject().apply {
                element.asJsonObject.entrySet().forEach { (key, child) -> add(key, visit(child)) }
            }
            element.isJsonArray -> JsonArray().apply { element.asJsonArray.forEach { add(visit(it)) } }
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                val text = element.asString
                val input = parameters[text.removePrefix("{").removeSuffix("}")]
                if (input == null || text != "{${input.name}}") element.deepCopy() else {
                    val replacement = inputs["${option?.path}/${input.name}"] ?: input.defaultValue
                    when (input.pipelineType) {
                        "int" -> replacement.toLongOrNull()?.let { com.google.gson.JsonPrimitive(it) } ?: element.deepCopy()
                        "float" -> replacement.toDoubleOrNull()?.let { com.google.gson.JsonPrimitive(it) } ?: element.deepCopy()
                        "bool" -> replacement.toBooleanStrictOrNull()?.let { com.google.gson.JsonPrimitive(it) } ?: element.deepCopy()
                        else -> com.google.gson.JsonPrimitive(replacement)
                    }
                }
            }
            else -> element.deepCopy()
        }
        return visit(value)
    }

    fun escape(value: String): String = value.replace("~", "~0").replace("/", "~1")

    private fun JsonObject.getAsJsonArrayOrNull(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(name: String): String = get(name)?.let {
        if (it.isJsonPrimitive) it.asString else ""
    }.orEmpty()

    private fun JsonObject.strings(name: String): List<String> = when (val value = get(name)) {
        null -> emptyList()
        is JsonArray -> value.filter { it.isJsonPrimitive }.map { it.asString }
        else -> if (value.isJsonPrimitive) listOf(value.asString) else emptyList()
    }
}
