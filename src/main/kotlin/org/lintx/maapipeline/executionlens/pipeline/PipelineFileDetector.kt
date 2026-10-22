package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

internal object PipelineFileDetector {
    private val pipelineExtensions = setOf("json", "jsonc")
    private val pipelineDirectoryMarker = "/pipeline/"
    private val pipelineFields = setOf("recognition", "action", "next", "on_error", "anchor")

    fun isPipelineFile(file: VirtualFile): Boolean {
        if (file.isDirectory || file.extension?.lowercase() !in pipelineExtensions) return false
        if (file.path.replace('\\', '/').contains(pipelineDirectoryMarker, ignoreCase = true)) return true
        return runCatching {
            val text = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
            isPipelineFile(file, text)
        }.getOrDefault(false)
    }

    fun isPipelineFile(file: VirtualFile, text: String): Boolean {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        if (file.isDirectory || extension !in pipelineExtensions) return false
        val normalizedPath = file.path.replace('\\', '/')
        if (normalizedPath.contains(pipelineDirectoryMarker, ignoreCase = true)) return true
        // 目录外只识别节点对象的直接字段，不能把任务覆盖、注释或普通字符串当成 Pipeline。
        return runCatching {
            val root = PipelineJsonc.parse(text).takeIf { it.isJsonObject }?.asJsonObject
                ?: return@runCatching false
            root.entrySet().any { (_, value) ->
                value.isJsonObject && value.asJsonObject.entrySet().any { (name, field) ->
                    name in pipelineFields && when (name) {
                        "next", "on_error" -> field.isJsonArray ||
                            (field.isJsonPrimitive && field.asJsonPrimitive.isString) ||
                            (field.isJsonObject && field.asJsonObject.has("name"))
                        "recognition", "action" -> (field.isJsonPrimitive && field.asJsonPrimitive.isString) ||
                            (field.isJsonObject && field.asJsonObject.has("type"))
                        else -> field.isJsonArray || field.isJsonObject ||
                            (field.isJsonPrimitive && field.asJsonPrimitive.isString)
                    }
                }
            }
        }.getOrDefault(false)
    }
}
