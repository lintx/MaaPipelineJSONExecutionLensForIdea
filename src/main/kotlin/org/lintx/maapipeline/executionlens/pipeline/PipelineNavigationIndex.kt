package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.EnumeratorStringDescriptor

class PipelineNavigationIndex : FileBasedIndexExtension<String, String>() {
    override fun getName() = NAME
    override fun getVersion() = 5
    override fun dependsOnFileContent() = true
    override fun getKeyDescriptor() = EnumeratorStringDescriptor()
    override fun getValueExternalizer() = EnumeratorStringDescriptor()
    override fun getInputFilter() = FileBasedIndex.InputFilter { it.extension?.lowercase() in EXTENSIONS }
    override fun getIndexer() = DataIndexer<String, String, FileContent> { input ->
        if (input.contentAsText.length > 2_000_000) emptyMap()
        else {
            val result = symbols(input.contentAsText.toString(), input.file.extension.orEmpty(), input.file).toMutableMap()
            if (input.file.extension?.lowercase() in setOf("json", "jsonc")) {
                val psi = input.psiFile as? com.intellij.json.psi.JsonFile
                if (psi != null && isMaaJson(psi)) {
                    val root = psi.topLevelValue as? com.intellij.json.psi.JsonObject
                    if (PipelineFileDetector.isPipelineFile(input.file, input.contentAsText.toString())) {
                        root?.propertyList?.forEach { property ->
                            PipelineJsonSymbols.description(property)?.let {
                                result["desc:${property.name}"] = com.google.gson.JsonPrimitive(it).toString()
                            }
                        }
                    }
                    // 仅检查当前被索引文件的 PSI；索引构建期间不解析目标、不访问其他文件。
                    com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psi, com.intellij.json.psi.JsonStringLiteral::class.java)
                        .mapNotNull { literal -> PipelineJsonSymbols.use(literal)?.takeUnless { it.anchor }?.let {
                            "use:${it.name}" to literal.textOffset.toString()
                        } }.groupBy({ it.first }, { it.second }).forEach { (key, positions) ->
                            result[key] = positions.joinToString("\n")
                        }
                }
            }
            result
        }
    }

    companion object {
        val NAME: ID<String, String> = ID.create("MaaPipeline.Navigation")
        private val EXTENSIONS = setOf("json", "jsonc", "go", "py", "cpp", "cc", "cxx", "c", "h", "hpp", "java", "kt")

        internal fun symbols(text: String, extension: String, file: VirtualFile? = null): Map<String, String> {
            val result = linkedMapOf<String, MutableList<String>>()
            fun put(key: String, value: String) { result.getOrPut(key) { mutableListOf() }.add(value) }
            if (extension.lowercase() in setOf("json", "jsonc")) {
                if (file != null && !PipelineFileDetector.isPipelineFile(file, text)) return emptyMap()
                val root = PipelineVisualModel.parse(text) ?: return emptyMap()
                root.entrySet().filter { it.value.isJsonObject }.forEach { (name, body) ->
                    put("node:$name", name)
                    val anchors = body.asJsonObject.get("anchor")
                    when {
                        anchors?.isJsonObject == true -> anchors.asJsonObject.entrySet().forEach { (anchor, target) ->
                            if (target.isJsonPrimitive) put("anchor:$anchor", "$name\t${target.asString}")
                        }
                        anchors?.isJsonArray == true -> anchors.asJsonArray.filter { it.isJsonPrimitive }.forEach { put("anchor:${it.asString}", "$name\t$name") }
                        anchors?.isJsonPrimitive == true -> put("anchor:${anchors.asString}", "$name\t$name")
                    }
                }
            } else {
                val code = maskComments(text, extension == "py")
                val constant = Regex("(?m)\\b([A-Za-z_]\\w*)\\s*(?::\\s*\\w+\\s*)?=\\s*\"([^\"\\n]+)\"")
                val constants = constant.findAll(code).associate { it.groupValues[1] to it.groupValues[2] }
                constants.forEach { (name, value) -> put("constant:$value", name) }
                val registrations = Regex("(?:AgentServerRegisterCustom|MaaResourceRegisterCustom|register_custom)(Action|Recognition|_action|_recognition)\\s*\\(\\s*(\"[^\"]+\"|[A-Za-z_]\\w*)\\s*,\\s*&?([A-Za-z_]\\w*(?:(?:::|\\.)[A-Za-z_]\\w*)*)")
                registrations.findAll(code).forEach { match ->
                    val kind = if (match.groupValues[1].lowercase().contains("action")) "action" else "recognition"
                    val token = match.groupValues[2]
                    val name = if (token.startsWith('"')) token.removeSurrounding("\"") else constants[token]
                    val receiver = match.groupValues[3]
                    put(if (name == null) "registration:$kind:$token" else "custom:$kind:$name", receiver)
                }
                if (extension == "go") {
                    Regex("func\\s*\\(\\s*\\w+\\s+\\*?(\\w+)\\s*\\)\\s+(Run)\\s*\\(").findAll(code).forEach { match ->
                        put("run:${match.groupValues[1]}", match.groups[2]!!.range.first.toString())
                    }
                } else if (extension == "py") {
                    val classes = Regex("(?m)^([ \\t]*)class\\s+(\\w+)[^\\n]*:").findAll(code).toList()
                    classes.forEachIndexed { index, match ->
                        val end = classes.getOrNull(index + 1)?.range?.first ?: code.length
                        val body = code.substring(match.range.last + 1, end)
                        Regex("(?m)^[ \\t]+(?:async\\s+)?def\\s+(run)\\s*\\(").findAll(body).forEach { run ->
                            put("run:${match.groupValues[2]}", (match.range.last + 1 + run.groups[1]!!.range.first).toString())
                        }
                        val before = code.substring((code.lastIndexOf("\n\n", match.range.first).takeIf { it >= 0 } ?: 0), match.range.first)
                        Regex("custom_(action|recognition)\\s*\\(\\s*[\"']([^\"']+)[\"']").findAll(before).forEach { decorator ->
                            put("custom:${decorator.groupValues[1]}:${decorator.groupValues[2]}", match.groupValues[2])
                        }
                    }
                } else {
                    // C/C++ 注册的是回调函数；只收集有函数体的定义，不能落到头文件声明或注册调用。
                    Regex("(?m)^[ \\t]*(?:[\\w:*&<>]+[ \\t]+)+([A-Za-z_]\\w*(?:::\\w+)*)\\s*\\([^;{}]*\\)\\s*(?:const\\s*)?(?:override\\s*)?\\{")
                        .findAll(code).forEach { match ->
                            val full = match.groupValues[1]
                            put("run:${full.substringAfterLast("::")}", match.groups[1]!!.range.first.toString())
                        }
                }
            }
            return result.mapValues { (_, values) -> values.distinct().joinToString("\n") }
        }

        private fun maskComments(text: String, python: Boolean): String {
            val result = text.toCharArray()
            var position = 0
            while (position < text.length) {
                val character = text[position]
                if (character == '"' || character == '\'' || character == '`') {
                    val delimiter = if (text.startsWith("$character$character$character", position)) "$character$character$character" else "$character"
                    position += delimiter.length
                    while (position < text.length && !text.startsWith(delimiter, position)) {
                        position += if (text[position] == '\\' && character != '`') 2 else 1
                    }
                    position += delimiter.length
                } else if (python && character == '#' || !python && text.startsWith("//", position)) {
                    while (position < text.length && text[position] != '\n') result[position++] = ' '
                } else if (!python && text.startsWith("/*", position)) {
                    val end = text.indexOf("*/", position + 2).takeIf { it >= 0 }?.plus(2) ?: text.length
                    while (position < end) { if (result[position] != '\n') result[position] = ' '; position++ }
                } else position++
            }
            return String(result)
        }

        internal fun values(project: Project, key: String): List<Pair<VirtualFile, String>> {
            if (DumbService.isDumb(project)) return emptyList()
            val result = mutableListOf<Pair<VirtualFile, String>>()
            FileBasedIndex.getInstance().processValues(NAME, key, null, { file, value ->
                ProgressManager.checkCanceled()
                value.lineSequence().filter { it.isNotEmpty() }.forEach { result += file to it }
                true
            }, GlobalSearchScope.projectScope(project))
            return result
        }

        internal fun implementations(project: Project, name: String, kind: String): List<Pair<VirtualFile, Int>> {
            val bindings = values(project, "custom:$kind:$name").toMutableList()
            values(project, "constant:$name").forEach { (constantFile, identifier) ->
                values(project, "registration:$kind:$identifier").filter { it.first.parent == constantFile.parent }.forEach(bindings::add)
            }
            return bindings.flatMap { (bindingFile, receiver) ->
                val symbol = receiver.substringAfterLast('.').substringAfterLast("::")
                values(project, "run:$symbol").filter { (targetFile, _) ->
                    if (bindingFile.extension == "go") targetFile.parent == bindingFile.parent else true
                }.mapNotNull { (targetFile, offset) -> offset.toIntOrNull()?.let { targetFile to it } }
            }.distinct()
        }

        internal fun customNames(project: Project, kind: String): Set<String> {
            if (DumbService.isDumb(project)) return emptySet()
            val keys = FileBasedIndex.getInstance().getAllKeys(NAME, project)
            val prefix = "custom:$kind:"
            val result = keys.filter { it.startsWith(prefix) }.mapTo(sortedSetOf()) { it.removePrefix(prefix) }
            val registrations = keys.filter { it.startsWith("registration:$kind:") }.associate { key ->
                key.substringAfterLast(':') to values(project, key).map { it.first.parent }.toSet()
            }
            // 注册名与注册调用可以分属同一 Go 包的不同文件，补全和源码导航必须使用同一绑定规则。
            if (registrations.isNotEmpty()) {
                keys.filter { it.startsWith("constant:") }.forEach { key ->
                    ProgressManager.checkCanceled()
                    if (values(project, key).any { (file, identifier) -> file.parent in registrations[identifier].orEmpty() }) {
                        result += key.removePrefix("constant:")
                    }
                }
            }
            return result
        }
    }
}
