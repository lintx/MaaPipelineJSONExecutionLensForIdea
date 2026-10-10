package com.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap

data class PipelineNodeLocation(
    val name: String,
    val nameRange: TextRange,
    val propertyRange: TextRange,
    val description: String? = null,
    val recognition: PipelineFieldLocation? = null,
    val action: PipelineFieldLocation? = null,
    val next: List<PipelineReference> = emptyList(),
    val onError: List<PipelineReference> = emptyList(),
    val anchor: PipelineFieldLocation? = null,
    val executionPolicy: PipelineExecutionPolicy = PipelineExecutionPolicy(),
    val anchorDefinitions: List<PipelineAnchorDefinition> = emptyList(),
    val embeddedReferences: List<PipelineReference> = emptyList(),
)

data class PipelineFieldLocation(
    val name: String,
    val summary: String,
    val range: TextRange,
    val valueRange: TextRange = range,
    val isString: Boolean = false,
)

data class PipelineExecutionPolicy(
    val timeout: PipelineFieldLocation? = null,
    val rateLimit: PipelineFieldLocation? = null,
    val maxHit: PipelineFieldLocation? = null,
) {
    fun isEmpty(): Boolean = timeout == null && rateLimit == null && maxHit == null

    fun summary(): String = listOfNotNull(timeout, rateLimit, maxHit)
        .joinToString("，") { "${it.name}=${it.summary}" }
}

data class PipelineReference(
    val displayName: String,
    val targetName: String,
    val range: TextRange,
    val isJumpBack: Boolean,
    val isAnchor: Boolean,
    val isString: Boolean = true,
    val targetExists: Boolean = false,
    val isCycle: Boolean = false,
    val isSharedTarget: Boolean = false,
    val target: PipelineNodeTarget? = null,
    val anchorTarget: PipelineAnchorTarget? = null,
    val isAmbiguousTarget: Boolean = false,
    val isCrossFile: Boolean = false,
    val isAnchorUnresolved: Boolean = false,
    val kind: PipelineReferenceKind = PipelineReferenceKind.NEXT,
    val propertyPath: String? = null,
)

enum class PipelineReferenceKind(
    val label: String,
) {
    NEXT("后继"),
    ON_ERROR("错误后继"),
    TARGET("目标"),
    BEGIN("滑动起点"),
    END("滑动终点"),
}

data class PipelineNodeTarget(
    val file: VirtualFile,
    val name: String,
    val nameRange: TextRange,
    val description: String?,
    val recognitionSummary: String?,
    val actionSummary: String?,
    val nextCount: Int,
)

data class PipelineAnchorDefinition(
    val name: String,
    val range: TextRange,
    val targetNodeName: String?,
    val isCleared: Boolean,
)

data class PipelineAnchorTarget(
    val file: VirtualFile,
    val anchorName: String,
    val definitionRange: TextRange,
    val definingNodeName: String,
    val targetNodeName: String?,
    val targetNode: PipelineNodeTarget?,
    val isCleared: Boolean,
)

data class PipelineOverrideLocation(
    val file: VirtualFile,
    val targetName: String,
    val range: TextRange,
    val fields: List<PipelineFieldLocation>,
)

data class PipelineEntryLocation(
    val file: VirtualFile,
    val entryName: String,
    val range: TextRange,
)

enum class PipelineDiagnosticSeverity {
    ERROR,
    WARNING,
}

data class PipelineDiagnostic(
    val file: VirtualFile,
    val range: TextRange,
    val lineNumber: Int? = null,
    val severity: PipelineDiagnosticSeverity,
    val kind: PipelineReferenceKind,
    val targetName: String,
    val message: String,
    val suggestion: String,
)

data class PipelineDocumentModel(
    val file: VirtualFile,
    val version: Long,
    val nodes: List<PipelineNodeLocation>,
    val hasSyntaxErrors: Boolean,
    val isGenerated: Boolean = false,
    val pipelineOverrides: List<PipelineOverrideLocation> = emptyList(),
    val pipelineEntries: List<PipelineEntryLocation> = emptyList(),
    val diagnostics: List<PipelineDiagnostic> = emptyList(),
)

internal object PipelineDocumentIndexer {
    private val parsedFileCache = ConcurrentHashMap<String, ParsedPipelineFile>()
    private val taskRelationsCache = ConcurrentHashMap<String, TaskFileRelations>()

    fun index(project: Project, file: VirtualFile): PipelineDocumentModel {
        val parsedFile = parseFile(project, file)
        val parsedFiles = findPipelineJsonFiles(file).map { parseFile(project, it) }
        val entries = parsedFiles.flatMap { parsed ->
            parsed.nodes.map { node -> PipelineNodeEntry(parsed.file, node) }
        }
        val candidatesByName = entries.groupBy { it.node.name }
        val anchorEntries = entries.flatMap { entry ->
            entry.node.anchorDefinitions.map { definition ->
                PipelineAnchorEntry(entry.file, entry.node, definition)
            }
        }
        val anchorsByName = anchorEntries.groupBy { it.definition.name }
        val graph = entries.associate { entry ->
            entry.key to flowReferences(entry.node)
                .mapNotNull { candidatesByName[it.targetName].orEmpty().singleOrNull()?.key }
        }
        val inboundCounts = graph.values
            .flatten()
            .groupingBy { it }
            .eachCount()
        val nodes = parsedFile.nodes.map { node ->
            val entry = PipelineNodeEntry(file, node)
            node.copy(
                next = resolveReferences(entry, node.next, candidatesByName, anchorsByName, graph, inboundCounts),
                onError = resolveReferences(entry, node.onError, candidatesByName, anchorsByName, graph, inboundCounts),
                embeddedReferences = resolveReferences(
                    entry,
                    node.embeddedReferences,
                    candidatesByName,
                    anchorsByName,
                    graph,
                    inboundCounts,
                ),
            )
        }
        val diagnostics = buildDiagnostics(file, nodes)
        return PipelineDocumentModel(
            file = file,
            version = parsedFile.version,
            nodes = nodes,
            hasSyntaxErrors = parsedFile.hasSyntaxErrors,
            isGenerated = parsedFile.isGenerated,
            pipelineOverrides = findPipelineOverrides(project, file, nodes.map { it.name }.toSet()),
            pipelineEntries = findPipelineEntries(project, file, nodes.map { it.name }.toSet()),
            diagnostics = diagnostics,
        )
    }

    private data class ParsedPipelineFile(
        val file: VirtualFile,
        val version: Long,
        val nodes: List<PipelineNodeLocation>,
        val hasSyntaxErrors: Boolean,
        val isGenerated: Boolean,
    )

    private data class TaskFileRelations(
        val file: VirtualFile,
        val version: Long,
        val overrides: List<PipelineOverrideLocation>,
        val entries: List<PipelineEntryLocation>,
    )

    private data class PipelineNodeEntry(
        val file: VirtualFile,
        val node: PipelineNodeLocation,
    ) {
        val key: String get() = "${file.url}#${node.name}"

        fun toTarget(): PipelineNodeTarget = PipelineNodeTarget(
            file = file,
            name = node.name,
            nameRange = node.nameRange,
            description = node.description,
            recognitionSummary = node.recognition?.summary,
            actionSummary = node.action?.summary,
            nextCount = node.next.size,
        )
    }

    private data class PipelineAnchorEntry(
        val file: VirtualFile,
        val node: PipelineNodeLocation,
        val definition: PipelineAnchorDefinition,
    ) {
        fun toTarget(targetNode: PipelineNodeTarget?): PipelineAnchorTarget = PipelineAnchorTarget(
            file = file,
            anchorName = definition.name,
            definitionRange = definition.range,
            definingNodeName = node.name,
            targetNodeName = definition.targetNodeName,
            targetNode = targetNode,
            isCleared = definition.isCleared,
        )
    }

    private fun parseFile(project: Project, file: VirtualFile): ParsedPipelineFile {
        val cacheKey = cacheKey(project, file)
        val version = fileVersion(file)
        parsedFileCache[cacheKey]?.takeIf { it.version == version }?.let { return it }

        val document = FileDocumentManager.getInstance().getDocument(file)
        val jsonFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
        val rootObject = jsonFile?.topLevelValue as? JsonObject
        val nodes = rootObject?.propertyList.orEmpty().mapNotNull { property ->
            val nameRange = property.nameElement.textRange ?: return@mapNotNull null
            val propertyRange = property.textRange ?: nameRange
            val valueObject = property.value as? JsonObject
            PipelineNodeLocation(property.name, nameRange, propertyRange)
                .withDetails(valueObject)
        }
        val parsedFile = ParsedPipelineFile(
            file = file,
            version = version,
            nodes = nodes,
            hasSyntaxErrors = jsonFile == null || rootObject == null,
            isGenerated = hasGeneratedHeader(document?.text.orEmpty()),
        )
        parsedFileCache[cacheKey] = parsedFile
        return parsedFile
    }

    private fun cacheKey(project: Project, file: VirtualFile): String =
        "${project.basePath.orEmpty()}|${file.url}"

    private fun fileVersion(file: VirtualFile): Long =
        FileDocumentManager.getInstance().getDocument(file)?.modificationStamp ?: file.modificationStamp

    private fun hasGeneratedHeader(text: String): Boolean {
        val marker = Regex("(?i)generated|autogenerated|do\\s+not\\s+edit|由[^\\r\\n]*生成")
        return text.lineSequence()
            .take(40)
            .map(String::trim)
            .filter { line -> line.startsWith("//") || line.startsWith("/*") || line.startsWith("*") }
            .any(marker::containsMatchIn)
    }

    private fun findPipelineJsonFiles(file: VirtualFile): List<VirtualFile> {
        val pipelineRoot = generateSequence(file.parent) { it.parent }
            .firstOrNull { it.name.equals("pipeline", ignoreCase = true) }
            ?: return listOf(file)
        val files = collectJsonFiles(pipelineRoot)
        return files.ifEmpty { listOf(file) }
    }

    private fun collectJsonFiles(root: VirtualFile): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        fun visit(current: VirtualFile) {
            if (current.isDirectory) {
                current.children.orEmpty().forEach(::visit)
            } else if (current.extension.equals("json", ignoreCase = true)) {
                files += current
            }
        }
        visit(root)
        return files
    }

    private fun flowReferences(node: PipelineNodeLocation): List<PipelineReference> =
        (node.next + node.onError).filterNot(PipelineReference::isAnchor)

    private fun resolveReferences(
        source: PipelineNodeEntry,
        references: List<PipelineReference>,
        candidatesByName: Map<String, List<PipelineNodeEntry>>,
        anchorsByName: Map<String, List<PipelineAnchorEntry>>,
        graph: Map<String, List<String>>,
        inboundCounts: Map<String, Int>,
    ): List<PipelineReference> = references.map { reference ->
        if (reference.isAnchor) {
            val anchorCandidates = anchorsByName[reference.targetName].orEmpty()
            val anchor = anchorCandidates.singleOrNull()
            val targetNode = anchor?.definition?.targetNodeName?.let { targetName ->
                candidatesByName[targetName].orEmpty().singleOrNull()
            } ?: anchor?.takeUnless { it.definition.isCleared }?.let { candidatesByName[it.node.name].orEmpty().singleOrNull() }
            return@map reference.copy(
                targetExists = anchorCandidates.isNotEmpty(),
                anchorTarget = anchor?.toTarget(targetNode?.toTarget()),
                isAnchorUnresolved = anchor == null || anchorCandidates.size > 1 || anchor.definition.isCleared || targetNode == null,
                isAmbiguousTarget = anchorCandidates.size > 1,
                isCrossFile = anchor != null && (
                    anchor.file != source.file || targetNode?.file != source.file
                ),
            )
        }
        val candidates = candidatesByName[reference.targetName].orEmpty()
        val target = candidates.singleOrNull()
        val targetKey = target?.key
        reference.copy(
            targetExists = candidates.isNotEmpty(),
            isCycle = targetKey != null && canReach(graph, targetKey, source.key),
            isSharedTarget = targetKey != null && (inboundCounts[targetKey] ?: 0) > 1,
            target = target?.toTarget(),
            isAmbiguousTarget = candidates.size > 1,
            isCrossFile = target != null && target.file != source.file,
        )
    }

    private fun buildDiagnostics(
        file: VirtualFile,
        nodes: List<PipelineNodeLocation>,
    ): List<PipelineDiagnostic> {
        // 索引构建运行在后台 ReadAction 中，行号在这里计算后，EDT 只消费不可变模型。
        val document = FileDocumentManager.getInstance().getDocument(file)
        return nodes.flatMap { node ->
        (node.next + node.onError + node.embeddedReferences).mapNotNull { reference ->
            val message: String
            val suggestion: String
            val severity: PipelineDiagnosticSeverity
            when {
                reference.isAnchorUnresolved -> {
                    severity = PipelineDiagnosticSeverity.WARNING
                    message = when {
                        reference.isAmbiguousTarget -> "Anchor 名称对应多个定义"
                        reference.anchorTarget?.isCleared == true -> "Anchor 已被清除"
                        reference.anchorTarget?.targetNode == null -> "Anchor 的目标节点不存在"
                        else -> "Anchor 未定义"
                    }
                    suggestion = when {
                        reference.isAmbiguousTarget -> "保留唯一的 Anchor 定义，或改用明确的节点名。"
                        reference.anchorTarget?.isCleared == true -> "为 Anchor 设置已存在的目标节点，或移除 [Anchor] 前缀。"
                        reference.anchorTarget?.targetNode == null -> "将 Anchor 映射到已存在的节点。"
                        else -> "在项目中定义该 Anchor，或移除 [Anchor] 前缀。"
                    }
                }

                reference.isAmbiguousTarget -> {
                    severity = PipelineDiagnosticSeverity.WARNING
                    message = "目标名称不唯一"
                    suggestion = "保持节点名称唯一，或改用唯一的节点名称。"
                }

                !reference.targetExists -> {
                    severity = PipelineDiagnosticSeverity.ERROR
                    message = "目标节点不存在"
                    suggestion = when (reference.kind) {
                        PipelineReferenceKind.TARGET,
                        PipelineReferenceKind.BEGIN,
                        PipelineReferenceKind.END,
                        -> "检查字段中的节点名称，或先定义该节点。"

                        else -> "检查节点名称拼写，或先定义该节点。"
                    }
                }

                else -> return@mapNotNull null
            }
            PipelineDiagnostic(
                file = file,
                range = reference.range,
                lineNumber = document?.let { currentDocument ->
                    reference.range.startOffset
                        .takeIf { it in 0..currentDocument.textLength }
                        ?.let { currentDocument.getLineNumber(it) + 1 }
                },
                severity = severity,
                kind = reference.kind,
                targetName = reference.targetName,
                message = message,
                suggestion = suggestion,
            )
        }
        }
    }

    private fun findPipelineOverrides(
        project: Project,
        file: VirtualFile,
        currentNodeNames: Set<String>,
    ): List<PipelineOverrideLocation> = findTaskRelations(project, file)
        .flatMap { relations -> relations.overrides }
        .filter { it.targetName in currentNodeNames }

    private fun findPipelineEntries(
        project: Project,
        file: VirtualFile,
        currentNodeNames: Set<String>,
    ): List<PipelineEntryLocation> = findTaskRelations(project, file)
        .flatMap { relations -> relations.entries }
        .filter { it.entryName in currentNodeNames }

    private fun findTaskRelations(project: Project, file: VirtualFile): List<TaskFileRelations> {
        val tasksRoot = findTasksRoot(file) ?: return emptyList()
        return collectJsonFiles(tasksRoot).mapNotNull { taskFile ->
            val cacheKey = cacheKey(project, taskFile)
            val version = fileVersion(taskFile)
            taskRelationsCache[cacheKey]?.takeIf { it.version == version }?.let { return@mapNotNull it }

            val jsonFile = PsiManager.getInstance(project).findFile(taskFile) as? JsonFile
                ?: return@mapNotNull null
            val overrides = mutableListOf<PipelineOverrideLocation>()
            val entries = mutableListOf<PipelineEntryLocation>()

            fun visit(element: PsiElement) {
                if (element is JsonProperty && element.name == "pipeline_override") {
                    val overrideObject = element.value as? JsonObject
                    overrideObject?.propertyList.orEmpty().forEach { targetProperty ->
                        val range = targetProperty.nameElement.textRange ?: targetProperty.textRange ?: return@forEach
                        val fields = (targetProperty.value as? JsonObject)
                            ?.propertyList
                            ?.mapNotNull { property -> property.toFieldLocation() }
                            .orEmpty()
                        overrides += PipelineOverrideLocation(taskFile, targetProperty.name, range, fields)
                    }
                }
                if (element is JsonProperty && element.name == "entry") {
                    val entryName = element.value?.let(::scalarText)
                    val range = element.value?.textRange ?: element.textRange
                    if (entryName != null && range != null) {
                        entries += PipelineEntryLocation(taskFile, entryName, range)
                    }
                }
                element.children.forEach(::visit)
            }

            visit(jsonFile)
            TaskFileRelations(taskFile, version, overrides, entries).also {
                taskRelationsCache[cacheKey] = it
            }
        }
    }

    private fun findTasksRoot(file: VirtualFile): VirtualFile? {
        val pipelineRoot = generateSequence(file.parent) { it.parent }
            .firstOrNull { it.name.equals("pipeline", ignoreCase = true) }
            ?: return null
        return pipelineRoot.parent?.parent?.findChild("tasks")
    }

    private fun PipelineNodeLocation.withDetails(valueObject: JsonObject?): PipelineNodeLocation {
        if (valueObject == null) return this
        return copy(
            description = valueObject.findProperty("desc")?.value?.let(::scalarText),
            recognition = valueObject.findProperty("recognition")?.toFieldLocation(),
            action = valueObject.findProperty("action")?.toFieldLocation(),
            next = valueObject.findProperty("next")?.toReferences(PipelineReferenceKind.NEXT).orEmpty(),
            onError = valueObject.findProperty("on_error")?.toReferences(PipelineReferenceKind.ON_ERROR).orEmpty(),
            anchor = valueObject.findProperty("anchor")?.toFieldLocation(),
            executionPolicy = PipelineExecutionPolicy(
                timeout = valueObject.findProperty("timeout")?.toFieldLocation(),
                rateLimit = valueObject.findProperty("rate_limit")?.toFieldLocation(),
                maxHit = valueObject.findProperty("max_hit")?.toFieldLocation(),
            ),
            anchorDefinitions = valueObject.findProperty("anchor")?.toAnchorDefinitions().orEmpty(),
            embeddedReferences = valueObject.toEmbeddedReferences(),
        )
    }

    private fun JsonProperty.toAnchorDefinitions(): List<PipelineAnchorDefinition> {
        val propertyValue = value ?: return emptyList()
        return when (propertyValue) {
            is JsonArray -> propertyValue.valueList.mapNotNull { anchorValue ->
                val range = anchorValue.textRange ?: return@mapNotNull null
                val name = scalarText(anchorValue)
                if (name.isEmpty()) return@mapNotNull null
                PipelineAnchorDefinition(name, range, targetNodeName = null, isCleared = false)
            }

            is JsonObject -> propertyValue.propertyList.mapNotNull { anchorProperty ->
                val range = anchorProperty.nameElement.textRange ?: anchorProperty.textRange ?: return@mapNotNull null
                val targetName = anchorProperty.value?.let(::scalarText)
                PipelineAnchorDefinition(
                    name = anchorProperty.name,
                    range = range,
                    targetNodeName = targetName?.takeIf { it.isNotEmpty() },
                    isCleared = targetName != null && targetName.isEmpty(),
                )
            }

            else -> {
                val range = propertyValue.textRange ?: return emptyList()
                val name = scalarText(propertyValue)
                if (name.isEmpty()) emptyList() else listOf(
                    PipelineAnchorDefinition(name, range, targetNodeName = null, isCleared = false),
                )
            }
        }
    }

    private fun JsonProperty.toFieldLocation(): PipelineFieldLocation? {
        val value = value ?: return null
        val range = textRange ?: return null
        val valueRange = value.textRange ?: range
        return PipelineFieldLocation(
            name = name,
            summary = summarizeValue(value),
            range = range,
            valueRange = valueRange,
            isString = value.text.trim().let { it.startsWith("\"") && it.endsWith("\"") },
        )
    }

    private fun JsonProperty.toReferences(kind: PipelineReferenceKind): List<PipelineReference> {
        val values = when (val propertyValue = value) {
            is JsonArray -> propertyValue.valueList
            null -> emptyList()
            else -> listOf(propertyValue)
        }
        return values.mapNotNull { value -> value.toReference(kind) }
    }

    private fun JsonObject.toEmbeddedReferences(): List<PipelineReference> {
        val references = mutableListOf<PipelineReference>()

        fun visit(element: PsiElement, path: String) {
            if (element is JsonProperty) {
                val propertyPath = if (path.isEmpty()) element.name else "$path.${element.name}"
                val kind = when (element.name) {
                    "target" -> PipelineReferenceKind.TARGET
                    "begin" -> PipelineReferenceKind.BEGIN
                    "end" -> PipelineReferenceKind.END
                    else -> null
                }
                if (kind != null) {
                    references += element.value?.toEmbeddedReferences(kind, propertyPath).orEmpty()
                }
                if (element.name != "next" && element.name != "on_error") {
                    element.children.forEach { child -> visit(child, propertyPath) }
                }
                return
            }
            element.children.forEach { child -> visit(child, path) }
        }

        visit(this, "")
        return references
    }

    private fun JsonValue.toEmbeddedReferences(
        kind: PipelineReferenceKind,
        propertyPath: String,
    ): List<PipelineReference> = when (this) {
        is JsonArray -> valueList.flatMap { value -> value.toEmbeddedReferences(kind, propertyPath) }
        else -> listOfNotNull(toReference(kind, propertyPath))
    }

    private fun JsonValue.toReference(
        kind: PipelineReferenceKind,
        propertyPath: String? = null,
    ): PipelineReference? {
        val range = textRange ?: return null
        if (this is JsonObject) {
            if (kind != PipelineReferenceKind.NEXT && kind != PipelineReferenceKind.ON_ERROR) return null
            val nameValue = findProperty("name")?.value ?: return null
            val raw = stringText(nameValue) ?: return null
            val markerState = parseReferenceMarkers(raw)
            val jumpBack = findProperty("jump_back")?.value?.text?.trim() == "true"
            val anchor = findProperty("anchor")?.value?.text?.trim() == "true"
            return PipelineReference(
                displayName = raw,
                targetName = markerState.targetName,
                range = nameValue.textRange ?: range,
                isJumpBack = markerState.isJumpBack || jumpBack,
                isAnchor = markerState.isAnchor || anchor,
                isString = false,
                kind = kind,
                propertyPath = propertyPath,
            )
        }
        val raw = stringText(this) ?: return null
        val markerState = parseReferenceMarkers(raw)
        return PipelineReference(
            displayName = raw,
            targetName = markerState.targetName,
            range = range,
            isJumpBack = markerState.isJumpBack,
            isAnchor = markerState.isAnchor,
            isString = true,
            kind = kind,
            propertyPath = propertyPath,
        )
    }

    private fun stringText(value: JsonValue): String? {
        val text = value.text.trim()
        if (text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"")) return null
        return scalarText(text)
    }

    private fun summarizeValue(value: com.intellij.json.psi.JsonValue): String {
        val objectValue = value as? JsonObject
        val type = objectValue?.findProperty("type")?.value?.let(::scalarText)
        if (type != null) return type
        if (objectValue != null) {
            return objectValue.propertyList.joinToString(", ") { property ->
                val propertyValue = property.value?.let(::scalarText) ?: ""
                "${property.name} → $propertyValue"
            }.ifEmpty { "{}" }
        }
        return scalarText(value.text)
    }

    private fun scalarText(value: JsonValue): String = scalarText(value.text)

    private fun scalarText(text: String): String =
        text.trim().removePrefix("\"").removeSuffix("\"")

    private data class ReferenceMarkers(
        val targetName: String,
        val isJumpBack: Boolean,
        val isAnchor: Boolean,
    )

    private fun parseReferenceMarkers(raw: String): ReferenceMarkers {
        var target = raw
        var isJumpBack = false
        var isAnchor = false
        while (target.startsWith("[")) {
            when {
                target.startsWith("[JumpBack]") -> {
                    isJumpBack = true
                    target = target.removePrefix("[JumpBack]")
                }

                target.startsWith("[Anchor]") -> {
                    isAnchor = true
                    target = target.removePrefix("[Anchor]")
                }

                else -> break
            }
        }
        return ReferenceMarkers(targetName = target, isJumpBack = isJumpBack, isAnchor = isAnchor)
    }

    private fun canReach(
        graph: Map<String, List<String>>,
        current: String,
        target: String,
        visited: Set<String> = emptySet(),
    ): Boolean {
        if (current == target) return true
        if (current in visited) return false
        val nextVisited = visited + current
        return graph[current].orEmpty().any { next ->
            canReach(graph, next, target, nextVisited)
        }
    }
}
