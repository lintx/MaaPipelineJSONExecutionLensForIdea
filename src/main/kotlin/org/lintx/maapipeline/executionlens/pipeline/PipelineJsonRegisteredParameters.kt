package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonObject as JsonPsiObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import com.jetbrains.jsonSchema.extension.JsonLikePsiWalker
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.JsonSchemaCompletionContributor
import com.jetbrains.jsonSchema.impl.JsonSchemaObject
import com.jetbrains.jsonSchema.impl.JsonSchemaResolver

internal object PipelineJsonRegisteredParameters {
    fun complete(parameters: CompletionParameters, result: CompletionResultSet): Boolean {
        val candidates = variants(parameters.position, parameters.originalFile, parameters.originalPosition ?: parameters.position,
            parameters.completionType) ?: return false
        result.addAllElements(candidates)
        return true
    }

    fun variants(position: PsiElement, originalFile: PsiFile, originalPosition: PsiElement, completionType: CompletionType): List<LookupElement>? {
        val parameter = generateSequence(position) { it.parent }.filterIsInstance<JsonProperty>()
            .firstOrNull { it.name in setOf("custom_recognition_param", "custom_action_param") } ?: return null
        val owner = parameter.parent as? JsonPsiObject ?: return null
        val implementationKey = parameter.name.removeSuffix("_param")
        if (owner.findProperty(implementationKey)?.value !is JsonStringLiteral) return null
        val service = JsonSchemaService.Impl.get(position.project)
        val root = service.getSchemaObject(originalFile) ?: return null
        val walker = JsonLikePsiWalker.getWalker(owner) ?: return null
        val adapter = walker.createValueAdapter(owner) ?: return null
        val branches = mutableListOf<JsonSchemaObject>()
        val visited = mutableSetOf<Pair<String?, String>>()
        fun collect(schema: JsonSchemaObject) {
            ProgressManager.checkCanceled()
            if (!visited.add(schema.fileUrl to schema.pointer)) return
            schema.ifThenElse.orEmpty().forEach { condition ->
                if (condition.`if`.getPropertyByName(implementationKey) != null &&
                    JsonSchemaResolver.isCorrect(adapter, condition.`if`)) {
                    condition.then?.let(branches::add)
                }
            }
            schema.resolveRefSchema(service)?.let(::collect)
            schema.definitionNames.forEach { name -> schema.getDefinitionByName(name)?.let(::collect) }
            schema.allOf.orEmpty().forEach(::collect)
            schema.anyOf.orEmpty().forEach(::collect)
            schema.oneOf.orEmpty().forEach(::collect)
        }
        collect(root)
        if (branches.isEmpty()) return null
        val path = walker.findPosition(owner, true) ?: return null
        val sources = branches.mapNotNull { it.rawFile }.distinct()
        val base = sources.firstOrNull()?.parent ?: return null
        val definitions = JsonObject()
        sources.forEachIndexed { index, source ->
            val content = PipelineVisualModel.parse(PipelineProjectFiles.text(source)) ?: return null
            val prefix = "#/\$defs/registered_$index"
            fun relocate(value: JsonElement) {
                if (value.isJsonArray) value.asJsonArray.forEach(::relocate)
                else if (value.isJsonObject) {
                    val objectValue = value.asJsonObject
                    objectValue.get("\$ref")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { reference ->
                        if (reference == "#" || reference.startsWith("#/")) objectValue.addProperty("\$ref", prefix + reference.removePrefix("#"))
                        else if (!java.net.URI(reference).isAbsolute && source.parent != base) {
                            val target = java.nio.file.Path.of(source.parent.path).resolve(reference.substringBefore('#')).normalize()
                            val relative = java.nio.file.Path.of(base.path).relativize(target).toString().replace('\\', '/')
                            objectValue.addProperty("\$ref", relative + reference.substring(reference.substringBefore('#').length))
                        }
                    }
                    objectValue.entrySet().forEach { relocate(it.value) }
                }
            }
            content.remove("\$id")
            relocate(content)
            definitions.add("registered_$index", content)
        }
        val references = JsonArray()
        branches.forEach { branch ->
            val source = branch.rawFile ?: return@forEach
            references.add(JsonObject().apply {
                addProperty("\$ref", "#/\$defs/registered_${sources.indexOf(source)}" + branch.pointer.removePrefix("#"))
            })
        }
        if (references.isEmpty) return null
        // 输入中的必填参数还不完整，不能让外层 anyOf 的整体验证丢掉已经由注册名确定的参数分支。
        // 只投影匹配分支到当前对象；字段、枚举、嵌套引用及插入行为仍由原生 Schema 引擎处理。
        var projection = JsonObject().apply { add("allOf", references) }
        for (index in path.size() - 1 downTo 0) {
            val child = projection
            projection = if (path.isArray(index)) JsonObject().apply { add("items", child) }
                else JsonObject().apply { add("properties", JsonObject().apply { add(path.stepNames[index], child) }) }
        }
        projection.addProperty("\$schema", "https://json-schema.org/draft/2020-12/schema")
        projection.add("\$defs", definitions)
        val file = object : LightVirtualFile("MaaPipeline.parameters.schema.json", JsonFileType.INSTANCE, projection.toString()) {
            override fun getParent() = base
        }
        val schema = service.getSchemaObjectForSchemaFile(file) ?: return null
        return JsonSchemaCompletionContributor.getCompletionVariants(schema, position, originalPosition, completionType)
    }
}
