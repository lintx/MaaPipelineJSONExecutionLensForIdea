package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.json.JsonFileType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.net.URI
import java.nio.file.Path

internal data class PipelineProjectSchemaBundle(val file: VirtualFile, val dependencies: Map<VirtualFile, Long>) {
    fun isCurrent(): Boolean = dependencies.all { (source, stamp) -> source.isValid && modificationStamp(source) == stamp }

    companion object {
        fun modificationStamp(source: VirtualFile): Long =
            FileDocumentManager.getInstance().getCachedDocument(source)?.modificationStamp ?: source.modificationStamp

        fun build(source: VirtualFile): PipelineProjectSchemaBundle? {
            val root = PipelineVisualModel.parse(PipelineProjectFiles.text(source)) ?: return null
            val documents = linkedMapOf(source to root)
            val prefixes = linkedMapOf(source to "#")
            val dependencies = linkedMapOf(source to modificationStamp(source))
            val existing = root.get("\$defs")?.takeIf { it.isJsonObject }?.asJsonObject
            var namespace = "MaaPipelineLocalSchemas"
            while (existing?.has(namespace) == true) namespace += "_"
            val bundled = JsonObject()

            fun localFile(from: VirtualFile, reference: String): VirtualFile? {
                val uri = runCatching { URI(reference.replace(" ", "%20")) }.getOrNull() ?: return null
                if (uri.isAbsolute) return if (uri.scheme == "file")
                    runCatching { LocalFileSystem.getInstance().findFileByNioFile(Path.of(uri)) }.getOrNull() else null
                if (uri.rawAuthority != null) return null
                return from.parent?.findFileByRelativePath(uri.path)
            }

            fun fragment(prefix: String, value: String): String = when {
                value.isEmpty() || value.startsWith("/") -> prefix + value
                prefix == "#" -> "#$value"
                else -> "#maa_lens_${prefix.substringAfterLast('/')}__$value"
            }

            fun rewrite(schema: JsonObject, origin: VirtualFile) {
                ProgressManager.checkCanceled()
                val prefix = prefixes.getValue(origin)
                schema.get("\$anchor")?.takeIf { it.isJsonPrimitive }?.asString?.let { anchor ->
                    if (prefix != "#") schema.addProperty("\$anchor", fragment(prefix, anchor).removePrefix("#"))
                }
                schema.get("\$ref")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { reference ->
                    val path = reference.substringBefore('#')
                    val target = if (path.isEmpty()) origin else localFile(origin, path)
                    if (target != null) {
                        if (target !in documents) {
                            val content = PipelineVisualModel.parse(PipelineProjectFiles.text(target))
                            if (content != null) {
                                val key = documents.size.toString()
                                prefixes[target] = "#/\$defs/$namespace/\$defs/$key"
                                documents[target] = content
                                dependencies[target] = modificationStamp(target)
                                // 本地子文档合并后共享根基址，避免子文档的 $id 将片段引用重新引向磁盘。
                                content.remove("\$id")
                                rewrite(content, target)
                                bundled.add(key, content)
                            }
                        }
                        prefixes[target]?.let { schema.addProperty("\$ref", fragment(it, reference.substringAfter('#', ""))) }
                    }
                }
                // 仅遍历 Schema 关键字，不改动 default、examples 或枚举中的业务 JSON。
                for (key in listOf("\$defs", "definitions", "properties", "patternProperties", "dependentSchemas", "dependencies")) {
                    schema.get(key)?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.toList()?.forEach { entry ->
                        if (entry.value.isJsonObject) rewrite(entry.value.asJsonObject, origin)
                    }
                }
                for (key in listOf("allOf", "anyOf", "oneOf", "prefixItems", "items", "additionalItems", "additionalProperties",
                    "unevaluatedItems", "unevaluatedProperties", "contains", "propertyNames", "not", "if", "then", "else", "contentSchema")) {
                    val value: JsonElement = schema.get(key) ?: continue
                    if (value.isJsonObject) rewrite(value.asJsonObject, origin)
                    else if (value.isJsonArray) value.asJsonArray.forEach { if (it.isJsonObject) rewrite(it.asJsonObject, origin) }
                }
            }
            // SDK 的跨文件解析在测试模式自动启用，正常运行受内部开关限制；本地依赖使用内部引用，保持两者行为一致。
            rewrite(root, source)
            if (!bundled.isEmpty) {
                val definitions = existing ?: JsonObject().also { root.add("\$defs", it) }
                definitions.add(namespace, JsonObject().apply { add("\$defs", bundled) })
            }
            if (!root.has("\$id")) {
                val uri = if (source.fileSystem.protocol == "file") source.toNioPath().toUri() else URI(source.url.replace(" ", "%20"))
                root.addProperty("\$id", uri.toASCIIString())
            }
            val file = object : LightVirtualFile("MaaPipeline.project.schema.json", JsonFileType.INSTANCE, root.toString()) {
                override fun getParent(): VirtualFile? = source.parent
            }
            return PipelineProjectSchemaBundle(file, dependencies)
        }
    }
}
