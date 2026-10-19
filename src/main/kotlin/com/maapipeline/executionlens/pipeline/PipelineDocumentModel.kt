package com.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFileFactory
import com.intellij.json.JsonFileType
import com.google.gson.JsonParser
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.ConcurrentHashMap

data class PipelineNodeLocation(
    val name: String,
    val nameRange: TextRange,
    val propertyRange: TextRange,
    val description: String? = null,
    val recognition: PipelineFieldLocation? = null,
    val action: PipelineFieldLocation? = null,
    val nextField: PipelineFieldLocation? = null,
    val next: List<PipelineReference> = emptyList(),
    val onErrorField: PipelineFieldLocation? = null,
    val onError: List<PipelineReference> = emptyList(),
    val anchor: PipelineFieldLocation? = null,
    val executionPolicy: PipelineExecutionPolicy = PipelineExecutionPolicy(),
    val anchorDefinitions: List<PipelineAnchorDefinition> = emptyList(),
    val embeddedReferences: List<PipelineReference> = emptyList(),
    val sourceFile: VirtualFile? = null,
    val enabled: Boolean = true,
    val rawJson: String = "{}",
)

data class PipelineFieldLocation(
    val name: String,
    val summary: String,
    val range: TextRange,
    val valueRange: TextRange = range,
    val isString: Boolean = false,
    val references: List<PipelineReference> = emptyList(),
    val sourceFile: VirtualFile? = null,
    val isDerived: Boolean = false,
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
    val sourceFile: VirtualFile? = null,
)

enum class PipelineReferenceKind(
    val label: String,
) {
    NEXT("后继"),
    ON_ERROR("错误后继"),
    TARGET("目标"),
    BEGIN("滑动起点"),
    END("滑动终点"),
    SUBTASK("子任务"),
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
    val propertyRange: TextRange = range,
    val valueRange: TextRange = range,
    val targetRange: TextRange? = null,
    val isScalar: Boolean = false,
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
    val valueRange: TextRange = range,
    val version: Long = -1L,
    val contextPath: String = "",
    val rawJson: String = "{}",
)

data class PipelineEntryLocation(
    val file: VirtualFile,
    val entryName: String,
    val range: TextRange,
    val targetFile: VirtualFile? = null,
    val taskId: String = entryName,
    val label: String = taskId,
    val description: String = "",
    val searchText: String = "",
    val contextPath: String = "",
    val optionNames: List<String> = emptyList(),
    val taskOptions: List<PipelineTaskOption> = emptyList(),
    val issue: String? = null,
) {
    val stableId: String get() = "${file.url}#task:$taskId"
}

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
    val projectNodes: List<PipelineNodeLocation> = emptyList(),
    val indexedText: String = "",
    val indexedVersions: Map<VirtualFile, Long> = emptyMap(),
    val generatedFiles: Set<VirtualFile> = emptySet(),
    val pipelineFiles: Set<VirtualFile> = emptySet(),
    val graphIndex: PipelineGraphIndex? = null,
)

data class PipelineGraphIndex(
    val edges: Map<String, List<String>>,
    val components: Map<String, Int>,
    val inboundCounts: Map<String, Int>,
)

internal object PipelineDocumentIndexer {
    private val parsedFileCache = ConcurrentHashMap<String, ParsedPipelineFile>()
    private val taskRelationsCache = ConcurrentHashMap<String, TaskFileRelations>()

    fun diagnostics(project: Project, file: VirtualFile): List<PipelineDiagnostic> {
        val parsed = parseFile(project, file)
        val entries = PipelineProjectFiles.pipelineFiles(project, file).flatMap { candidateFile ->
            ProgressManager.checkCanceled()
            parseFile(project, candidateFile).nodes.map { PipelineNodeEntry(candidateFile, it) }
        }
        val candidatesByName = entries.groupBy { it.node.name }
        val anchorsByName = entries.flatMap { entry ->
            entry.node.anchorDefinitions.map { PipelineAnchorEntry(entry.file, entry.node, it) }
        }.groupBy { it.definition.name }
        // Inspection 不需要循环和共享关系，避免在后台高亮时反复遍历整个控制流图。
        val nodes = parsed.nodes.map { node ->
            ProgressManager.checkCanceled()
            val source = PipelineNodeEntry(file, node)
            node.copy(
                next = resolveReferences(source, node.next, candidatesByName, anchorsByName, emptyMap(), emptyMap()),
                onError = resolveReferences(source, node.onError, candidatesByName, anchorsByName, emptyMap(), emptyMap()),
                embeddedReferences = resolveReferences(source, node.embeddedReferences, candidatesByName, anchorsByName, emptyMap(), emptyMap()),
            )
        }
        return buildDiagnostics(file, nodes)
    }

    fun index(project: Project, file: VirtualFile, previous: PipelineDocumentModel? = null,
        changedFiles: Set<VirtualFile> = emptySet()): PipelineDocumentModel {
        val parsedFile = parseFile(project, file)
        val knownContentUpdate = previous != null && previous.file == file && previous.pipelineFiles.isNotEmpty() &&
            changedFiles.isNotEmpty() && changedFiles.all { it.isValid && it in previous.pipelineFiles }
        val files = if (knownContentUpdate) previous.pipelineFiles.toList() else PipelineProjectFiles.pipelineFiles(project, file)
        val parsedFiles = files.map { source ->
            // 未变文件直接复用已解析快照，不重新加载 Document 或等待它的 PSI 提交。
            if (knownContentUpdate && source !in changedFiles) parsedFileCache[cacheKey(project, source)]
                ?.takeIf { it.version == previous.indexedVersions[source] } ?: parseFile(project, source)
            else parseFile(project, source)
        }
        val entries = parsedFiles.flatMap { parsed ->
            ProgressManager.checkCanceled()
            parsed.nodes.map { node -> PipelineNodeEntry(parsed.file, node) }
        }
        val previousByKey = previous?.projectNodes.orEmpty().associateBy { PipelineReadingGraph.key(it) }
        val reuseCatalog = knownContentUpdate && entries.mapTo(hashSetOf()) { it.key } == previousByKey.keys
        val relations = if (reuseCatalog) emptyList() else findTaskRelations(project, file)
        val candidatesByName = entries.groupBy { it.node.name }
        val anchorEntries = entries.flatMap { entry ->
            entry.node.anchorDefinitions.map { definition ->
                PipelineAnchorEntry(entry.file, entry.node, definition)
            }
        }
        val anchorsByName = anchorEntries.groupBy { it.definition.name }
        val graph = entries.associate { entry ->
            ProgressManager.checkCanceled()
            entry.key to flowReferences(entry.node)
                .mapNotNull { candidatesByName[it.targetName].orEmpty().singleOrNull()?.key }
        }
        val graphIndex = previous?.graphIndex?.takeIf { it.edges == graph } ?: PipelineGraphIndex(
            graph, PipelineGraphCycles.components(graph), graph.values.flatten().groupingBy { it }.eachCount())
        val inboundCounts = graphIndex.inboundCounts
        val cycleComponents = graphIndex.components
        val changedNames = parsedFiles.filter { it.file in changedFiles }.flatMap { it.nodes }.mapTo(hashSetOf()) { it.name }
        val resolvedNodesByKey = entries.associate { entry ->
            ProgressManager.checkCanceled()
            val node = entry.node
            val retained = previousByKey[entry.key]?.takeIf {
                reuseCatalog && entry.file !in changedFiles && graphIndex === previous.graphIndex &&
                    (node.next + node.onError + node.embeddedReferences).none { it.isAnchor || it.targetName in changedNames }
            }
            entry.key to (retained ?: node.copy(
                sourceFile = entry.file,
                next = resolveReferences(entry, node.next, candidatesByName, anchorsByName, cycleComponents, inboundCounts),
                onError = resolveReferences(entry, node.onError, candidatesByName, anchorsByName, cycleComponents, inboundCounts),
                embeddedReferences = resolveReferences(
                    entry,
                    node.embeddedReferences,
                    candidatesByName,
                    anchorsByName,
                    cycleComponents,
                    inboundCounts,
                ),
            ))
        }
        val nodes = parsedFile.nodes.mapNotNull { node ->
            resolvedNodesByKey["${file.url}#${node.name}"]
        }
        val projectNodes = resolvedNodesByKey.values.toList()
        val nodeNames = candidatesByName.keys
        val overrides = if (reuseCatalog) previous.pipelineOverrides else relations.flatMap { it.overrides }
            .filter { it.targetName in nodeNames }
        val resolvedOverrides = overrides.map { override ->
            ProgressManager.checkCanceled()
            val sourceNode = resolvedNodesByKey["${file.url}#${override.targetName}"]
                ?: candidatesByName[override.targetName]?.singleOrNull()?.let { resolvedNodesByKey[it.key] }
            val sourceFile = sourceNode?.sourceFile ?: file
            if (sourceNode == null) {
                override
            } else {
                override.copy(
                    fields = override.fields.map { field ->
                        when (field.name) {
                            "next" -> field.copy(
                                references = resolveReferences(
                                    PipelineNodeEntry(sourceFile, sourceNode),
                                    field.references,
                                    candidatesByName,
                                    anchorsByName,
                                    cycleComponents,
                                    inboundCounts,
                                ),
                            )

                            "on_error" -> field.copy(
                                references = resolveReferences(
                                    PipelineNodeEntry(sourceFile, sourceNode),
                                    field.references,
                                    candidatesByName,
                                    anchorsByName,
                                    cycleComponents,
                                    inboundCounts,
                                ),
                            )

                            else -> field
                        }
                    },
                )
            }
        }
        val previousDiagnostics = previous?.diagnostics.orEmpty().groupBy { it.file }
        val diagnostics = projectNodes.groupBy { it.sourceFile ?: file }.flatMap { (source, locations) ->
            if (reuseCatalog && source !in changedFiles && locations.all { previousByKey[PipelineReadingGraph.key(it)] === it })
                previousDiagnostics[source].orEmpty()
            else buildDiagnostics(source, locations)
        }
        return PipelineDocumentModel(
            file = file,
            version = parsedFile.version,
            nodes = nodes,
            hasSyntaxErrors = parsedFile.hasSyntaxErrors,
            isGenerated = parsedFile.isGenerated,
            pipelineOverrides = resolvedOverrides,
            pipelineEntries = if (reuseCatalog) resolveEntryTargets(previous.pipelineEntries, projectNodes)
                else findPipelineEntries(relations, projectNodes),
            diagnostics = diagnostics,
            projectNodes = projectNodes,
            indexedText = FileDocumentManager.getInstance().getDocument(file)?.text.orEmpty(),
            indexedVersions = parsedFiles.associate { it.file to it.version } + if (reuseCatalog) {
                previous.indexedVersions.filterKeys { it !in previous.pipelineFiles }
            } else relations.associate { it.file to it.version } +
                PipelineProjectFiles.translationFiles(project, file).associateWith(::fileVersion),
            generatedFiles = parsedFiles.filter { it.isGenerated }.map { it.file }.toSet(),
            pipelineFiles = files.toSet(),
            graphIndex = graphIndex,
        )
    }

    fun effectiveNodes(project: Project, model: PipelineDocumentModel, overrides: List<PipelineOverrideLocation>,
        inputs: Map<String, String>, options: List<PipelineTaskOption> = emptyList()): Map<String, PipelineNodeLocation> {
        val byName = model.projectNodes.groupBy { it.name }
        val patches = overrides.groupBy { it.targetName }
        return model.projectNodes.mapNotNull { node ->
            ProgressManager.checkCanceled()
            val changes = patches[node.name] ?: return@mapNotNull null
            if (byName[node.name].orEmpty().size != 1) return@mapNotNull null
            var json = try { JsonParser.parseString(node.rawJson) }
                catch (_: com.google.gson.JsonParseException) { return@mapNotNull null }
            changes.forEach { override ->
                val option = options.firstOrNull { it.path == override.contextPath }
                val raw = try { JsonParser.parseString(override.rawJson) }
                    catch (_: com.google.gson.JsonParseException) { return@mapNotNull null }
                val patch = PipelineTaskContext.substituteInputs(raw, option, inputs)
                json = PipelineTaskContext.merge(json, patch)
            }
            val psi = PsiFileFactory.getInstance(project).createFileFromText("effective.json", JsonFileType.INSTANCE,
                json.toString()) as JsonFile
            val effective = node.withDetails(psi.topLevelValue as? JsonObject)
            val changedFields = changes.flatMap { it.fields }.associateBy { it.name }
            fun field(name: String, parsed: PipelineFieldLocation?, original: PipelineFieldLocation?): PipelineFieldLocation? {
                if (parsed == null) return null
                val source = changedFields[name] ?: original ?: changedFields.values.lastOrNull() ?: return parsed
                return source.copy(name = name, summary = parsed.summary, isDerived = true)
            }
            fun references(name: String, original: List<PipelineReference>): List<PipelineReference> =
                changedFields[name]?.references ?: original
            val embedded = effective.embeddedReferences.map { reference ->
                val original = node.embeddedReferences.firstOrNull { it.kind == reference.kind && it.targetName == reference.targetName }
                val origin = changedFields["custom_action_param"] ?: changedFields["action"]
                val target = byName[reference.targetName].orEmpty().singleOrNull()
                if (original != null && origin == null) original else reference.copy(
                    range = origin?.valueRange ?: node.nameRange,
                    sourceFile = origin?.sourceFile ?: node.sourceFile,
                    targetExists = byName[reference.targetName].orEmpty().isNotEmpty(),
                    target = target?.let { PipelineNodeEntry(it.sourceFile ?: model.file, it).toTarget() },
                    isAmbiguousTarget = byName[reference.targetName].orEmpty().size > 1,
                )
            }
            val anchorField = changedFields["anchor"]
            "${node.sourceFile?.url ?: model.file.url}#${node.name}" to effective.copy(
                recognition = field("recognition", effective.recognition, node.recognition),
                action = field("action", effective.action, node.action),
                nextField = changedFields["next"] ?: node.nextField,
                next = references("next", node.next),
                onErrorField = changedFields["on_error"] ?: node.onErrorField,
                onError = references("on_error", node.onError),
                embeddedReferences = embedded,
                anchor = field("anchor", effective.anchor, node.anchor),
                anchorDefinitions = if (anchorField == null) node.anchorDefinitions else effective.anchorDefinitions.map {
                    it.copy(range = anchorField.valueRange, propertyRange = anchorField.range, valueRange = anchorField.valueRange)
                },
                executionPolicy = PipelineExecutionPolicy(
                    field("timeout", effective.executionPolicy.timeout, node.executionPolicy.timeout),
                    field("rate_limit", effective.executionPolicy.rateLimit, node.executionPolicy.rateLimit),
                    field("max_hit", effective.executionPolicy.maxHit, node.executionPolicy.maxHit),
                ),
            )
        }.toMap()
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
        val options: List<PipelineTaskOption>,
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
            hasSyntaxErrors = jsonFile == null || rootObject == null || PsiTreeUtil.hasErrorElements(jsonFile),
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
            ProgressManager.checkCanceled()
            if (current.isDirectory) {
                current.children.orEmpty().forEach(::visit)
            } else if (current.extension.equals("json", ignoreCase = true) || current.extension.equals("jsonc", ignoreCase = true)) {
                files += current
            }
        }
        visit(root)
        return files
    }

    private fun flowReferences(node: PipelineNodeLocation): List<PipelineReference> =
        (node.next + node.onError + node.embeddedReferences.filter { it.kind == PipelineReferenceKind.SUBTASK })
            .filterNot(PipelineReference::isAnchor)

    private fun resolveReferences(
        source: PipelineNodeEntry,
        references: List<PipelineReference>,
        candidatesByName: Map<String, List<PipelineNodeEntry>>,
        anchorsByName: Map<String, List<PipelineAnchorEntry>>,
        cycleComponents: Map<String, Int>,
        inboundCounts: Map<String, Int>,
    ): List<PipelineReference> = references.map { reference ->
        ProgressManager.checkCanceled()
        if (reference.isAnchor) {
            val anchorCandidates = anchorsByName[reference.targetName].orEmpty()
            val anchor = anchorCandidates.singleOrNull()
            val targetName = anchor?.takeUnless { it.definition.isCleared }?.let {
                it.definition.targetNodeName ?: it.node.name
            }
            val targetNode = targetName?.let { candidatesByName[it].orEmpty().singleOrNull() }
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
            isCycle = targetKey != null && cycleComponents[targetKey] == cycleComponents[source.key],
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
            val issue = PipelineReferenceDiagnostics.issue(reference) ?: return@mapNotNull null
            PipelineDiagnostic(
                file = file,
                range = reference.range,
                lineNumber = document?.let { currentDocument ->
                    reference.range.startOffset
                        .takeIf { it in 0..currentDocument.textLength }
                        ?.let { currentDocument.getLineNumber(it) + 1 }
                },
                severity = issue.severity,
                kind = reference.kind,
                targetName = reference.targetName,
                message = issue.message,
                suggestion = issue.suggestion,
            )
        }
        }
    }

    private fun findPipelineEntries(
        relations: List<TaskFileRelations>,
        projectNodes: List<PipelineNodeLocation>,
    ): List<PipelineEntryLocation> {
        val sharedOptions = relations.flatMap { it.options }
        val entries = relations.flatMap { it.entries }.map { entry ->
            val options = (entry.taskOptions + sharedOptions)
                .distinctBy { it.name }
            entry.copy(
                taskOptions = options,
                searchText = entry.searchText + "\n" + PipelineTaskContext.optionSearchText(entry.optionNames, options))
        }
        return resolveEntryTargets(entries, projectNodes)
    }

    private fun resolveEntryTargets(entries: List<PipelineEntryLocation>, nodes: List<PipelineNodeLocation>): List<PipelineEntryLocation> {
        val nodesByName = nodes.groupBy { it.name }
        return entries.map { entry ->
            val candidates = nodesByName[entry.entryName].orEmpty()
            val target = candidates.singleOrNull()?.sourceFile
            val issue = when {
                candidates.isEmpty() -> "入口未在当前资源中找到：${entry.entryName}"
                candidates.size > 1 -> "入口存在重复定义：${entry.entryName}"
                else -> null
            }
            if (entry.targetFile == target && entry.issue == issue) entry else entry.copy(targetFile = target, issue = issue)
        }
    }

    private fun findTaskRelations(project: Project, file: VirtualFile): List<TaskFileRelations> {
        val translations = PipelineProjectFiles.translations(project, file)
        val translationHash = translations.hashCode()
        return PipelineProjectFiles.taskFiles(project, file).mapNotNull { taskFile ->
            val cacheKey = "${cacheKey(project, taskFile)}|$translationHash"
            val version = fileVersion(taskFile)
            taskRelationsCache[cacheKey]?.takeIf { it.version == version }?.let { return@mapNotNull it }

            val jsonFile = PsiManager.getInstance(project).findFile(taskFile) as? JsonFile
                ?: return@mapNotNull null
            val overrides = mutableListOf<PipelineOverrideLocation>()
            val entries = mutableListOf<PipelineEntryLocation>()
            val definitions = runCatching { PipelineTaskContext.parse(PipelineProjectFiles.text(taskFile)) }
                .getOrNull() ?: return@mapNotNull null
            fun localize(value: String): String = translations[value] ?: value
            fun searchMetadata(values: List<String>): String = values.flatMap {
                listOf(it, localize(it), translations["$it#search"].orEmpty())
            }.filter(String::isNotBlank).distinct().joinToString("\n")
            val options = definitions.options.map { option ->
                val optionObject = ((jsonFile.topLevelValue as? JsonObject)?.findProperty("option")?.value as? JsonObject)
                    ?.findProperty(option.name)?.value as? JsonObject
                val comments = optionObject?.children?.filterIsInstance<PsiComment>().orEmpty().map { it.text }
                val metadata = listOf(option.name, option.label, option.description) + comments +
                    option.cases.flatMap { listOf(it.name, it.label, it.description) } +
                    option.inputs.flatMap { listOf(it.name, it.label, it.description) }
                option.copy(path = "${taskFile.url}#${option.path}", label = localize(option.label).ifEmpty { option.name },
                    description = localize(option.description), searchText = searchMetadata(metadata),
                    cases = option.cases.map { it.copy(path = "${taskFile.url}#${it.path}",
                        label = localize(it.label).ifEmpty { it.name }, description = localize(it.description)) },
                    inputs = option.inputs.map { it.copy(label = localize(it.label).ifEmpty { it.name },
                        description = localize(it.description)) })
            }

            fun visit(element: com.intellij.json.psi.JsonValue, path: String) {
                if (element is JsonArray) {
                    element.valueList.forEachIndexed { index, value -> visit(value, "$path/$index") }
                    return
                }
                if (element !is JsonObject) return
                definitions.tasks.firstOrNull { it.path == path }?.let { task ->
                    val entryProperty = element.findProperty("entry") ?: return@let
                    val comments = element.children.filterIsInstance<PsiComment>().joinToString(" ") { it.text }
                    entries += PipelineEntryLocation(taskFile, task.entry,
                        entryProperty.value?.textRange ?: entryProperty.textRange,
                        taskId = task.id, label = localize(task.label).ifEmpty { task.id },
                        description = localize(task.description), contextPath = "${taskFile.url}#$path",
                        searchText = listOf(task.id, task.label, localize(task.label), task.description,
                            localize(task.description), translations["${task.label}#search"].orEmpty(),
                            translations["${task.description}#search"].orEmpty(), comments, task.entry, taskFile.path).joinToString(" "),
                        optionNames = task.options, taskOptions = options)
                }
                element.findProperty("pipeline_override")?.let { property ->
                    val overrideObject = property.value as? JsonObject
                    overrideObject?.propertyList.orEmpty().forEach { targetProperty ->
                        val range = targetProperty.nameElement.textRange ?: targetProperty.textRange ?: return@forEach
                        val fields = (targetProperty.value as? JsonObject)
                            ?.propertyList
                            ?.mapNotNull { property ->
                                property.toContextualField(targetProperty.value as? JsonObject)?.let { field ->
                                    // 覆盖字段的范围属于任务文件，供有效控制流回跳到正确的 JSON 来源。
                                    field.copy(
                                        references = field.references.map { it.copy(sourceFile = taskFile) },
                                        sourceFile = taskFile,
                                    )
                                }
                            }
                            .orEmpty()
                        overrides += PipelineOverrideLocation(
                            file = taskFile,
                            targetName = targetProperty.name,
                            range = range,
                            fields = fields,
                            valueRange = targetProperty.value?.textRange ?: targetProperty.textRange ?: range,
                            version = version,
                            contextPath = "${taskFile.url}#$path",
                            rawJson = targetProperty.value?.text ?: "{}",
                        )
                    }
                }
                element.propertyList.forEach { property ->
                    if (property.name != "pipeline_override") property.value?.let {
                        visit(it, "$path/${PipelineTaskContext.escape(property.name)}")
                    }
                }
            }

            jsonFile.topLevelValue?.let { visit(it, "") }
            TaskFileRelations(taskFile, version, overrides, entries, options).also {
                taskRelationsCache[cacheKey] = it
            }
        }
    }

    private fun findTasksRoot(file: VirtualFile): VirtualFile? {
        val pipelineRoot = generateSequence(file.parent) { it.parent }
            .firstOrNull { it.name.equals("pipeline", ignoreCase = true) }
            ?: return null
        // MaaEnd 项目中任务目录可能位于 resource/tasks、resource/task 或更高一级目录；
        // 沿 pipeline 的祖先逐级查找，避免把某一种本机目录布局写死。
        return generateSequence(pipelineRoot.parent) { it.parent }
            .flatMap { ancestor ->
                sequenceOf("tasks", "task").mapNotNull { name -> ancestor.findChild(name) }
            }
            .firstOrNull { it.isDirectory }
    }

    private fun PipelineNodeLocation.withDetails(valueObject: JsonObject?): PipelineNodeLocation {
        if (valueObject == null) return this
        return copy(
            rawJson = valueObject.text,
            enabled = valueObject.findProperty("enabled")?.value?.text != "false",
            description = valueObject.findProperty("desc")?.value?.let(::scalarText),
            recognition = valueObject.findProperty("recognition")?.toContextualField(valueObject),
            action = valueObject.findProperty("action")?.toContextualField(valueObject),
            nextField = valueObject.findProperty("next")?.toFieldLocation(),
            next = valueObject.findProperty("next")?.toReferences(PipelineReferenceKind.NEXT).orEmpty(),
            onErrorField = valueObject.findProperty("on_error")?.toFieldLocation(),
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
                PipelineAnchorDefinition(
                    name = name,
                    range = range,
                    targetNodeName = null,
                    isCleared = false,
                    propertyRange = range,
                    valueRange = range,
                )
            }

            is JsonObject -> propertyValue.propertyList.mapNotNull { anchorProperty ->
                val range = anchorProperty.nameElement.textRange ?: anchorProperty.textRange ?: return@mapNotNull null
                val targetName = anchorProperty.value?.let(::scalarText)
                PipelineAnchorDefinition(
                    name = anchorProperty.name,
                    range = range,
                    targetNodeName = targetName?.takeIf { it.isNotEmpty() },
                    isCleared = targetName != null && targetName.isEmpty(),
                    propertyRange = anchorProperty.textRange ?: range,
                    valueRange = anchorProperty.value?.textRange ?: range,
                    targetRange = anchorProperty.value?.textRange,
                )
            }

            else -> {
                val range = propertyValue.textRange ?: return emptyList()
                val name = scalarText(propertyValue)
                if (name.isEmpty()) emptyList() else listOf(
                    PipelineAnchorDefinition(
                        name = name,
                        range = range,
                        targetNodeName = null,
                        isCleared = false,
                        propertyRange = range,
                        valueRange = range,
                        isScalar = true,
                    ),
                )
            }
        }
    }

    private fun JsonProperty.toContextualField(owner: JsonObject?): PipelineFieldLocation? {
        val summary = when (name) {
            "recognition" -> summarizeRecognition(value, owner)
            "action" -> summarizeAction(value, owner)
            else -> null
        }
        return toFieldLocation(summary)
    }

    private fun JsonProperty.toFieldLocation(summaryOverride: String? = null): PipelineFieldLocation? {
        val value = value ?: return null
        val range = textRange ?: return null
        val valueRange = value.textRange ?: range
        return PipelineFieldLocation(
            name = name,
            summary = summaryOverride ?: summarizeValue(value),
            range = range,
            valueRange = valueRange,
            isString = value.text.trim().let { it.startsWith("\"") && it.endsWith("\"") },
            references = when (name) {
                "next" -> toReferences(PipelineReferenceKind.NEXT)
                "on_error" -> toReferences(PipelineReferenceKind.ON_ERROR)
                else -> emptyList()
            },
        )
    }

    private fun summarizeRecognition(value: JsonValue?, owner: JsonObject?): String? =
        summarizeTypedCondition(value, owner, isAction = false)

    private fun summarizeAction(value: JsonValue?, owner: JsonObject?): String? =
        summarizeTypedCondition(value, owner, isAction = true)

    private fun summarizeTypedCondition(value: JsonValue?, owner: JsonObject?, isAction: Boolean): String? {
        value ?: return null
        val valueObject = value as? JsonObject
        val type = valueObject?.findProperty("type")?.value?.let(::scalarText)
            ?: scalarText(value.text)
        if (type.isEmpty()) return null

        val parameterObject = valueObject?.findProperty("param")?.value as? JsonObject
        val detailOwner = parameterObject ?: owner
        val details = mutableListOf<String>()
        fun ownerFor(propertyName: String): JsonObject? = when {
            detailOwner?.findProperty(propertyName) != null -> detailOwner
            else -> owner
        }
        if (isAction) {
            addSummaryDetail(details, ownerFor("custom_action"), "custom_action", "实现")
            addSummaryDetail(details, ownerFor("custom_action_param"), "custom_action_param", "参数")
            listOf("target", "begin", "end", "duration", "index", "contact").forEach { key ->
                addSummaryDetail(details, ownerFor(key), key, key)
            }
        } else {
            val allOf = ownerFor("all_of")?.findProperty("all_of")?.value as? JsonArray
            val anyOf = ownerFor("any_of")?.findProperty("any_of")?.value as? JsonArray
            when {
                allOf != null -> details += "全部满足：${summarizeConditionList(allOf)}"
                anyOf != null -> details += "任一满足：${summarizeConditionList(anyOf)}"
                else -> {
                    addSummaryDetail(details, ownerFor("expected"), "expected", "文本")
                    addSummaryDetail(details, ownerFor("template"), "template", "模板")
                    addSummaryDetail(details, ownerFor("roi"), "roi", "区域")
                    addSummaryDetail(details, ownerFor("roi_offset"), "roi_offset", "区域偏移")
                    addSummaryDetail(details, ownerFor("custom_recognition"), "custom_recognition", "实现")
                    addSummaryDetail(details, ownerFor("custom_recognition_param"), "custom_recognition_param", "参数")
                    addSummaryDetail(details, ownerFor("threshold"), "threshold", "阈值")
                    addSummaryDetail(details, ownerFor("box_index"), "box_index", "框")
                }
            }
        }
        return if (details.isEmpty()) type else compactSummary("$type（${details.joinToString("，")}）")
    }

    private fun summarizeConditionList(array: JsonArray): String = array.valueList
        .take(4)
        .joinToString("；") { item ->
            val objectValue = item as? JsonObject
            val nested = objectValue?.findProperty("recognition")
            if (nested != null) {
                summarizeTypedCondition(nested.value, objectValue, isAction = false) ?: summarizeInlineValue(item)
            } else {
                summarizeInlineValue(item)
            }
        }
        .let { summary -> if (array.valueList.size > 4) "$summary；…" else summary }

    private fun addSummaryDetail(
        details: MutableList<String>,
        owner: JsonObject?,
        propertyName: String,
        label: String,
    ) {
        val value = owner?.findProperty(propertyName)?.value ?: return
        details += "$label=${summarizeInlineValue(value)}"
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
        val action = findProperty("action")?.value as? JsonObject
        val parameters = action?.findProperty("param")?.value as? JsonObject
        val actionOwner = parameters ?: this
        val customName = actionOwner.findProperty("custom_action")?.value?.let(::scalarText)
        if (customName == "SubTask") {
            val customParameters = actionOwner.findProperty("custom_action_param")?.value as? JsonObject
            references += customParameters?.findProperty("sub")?.value
                ?.toEmbeddedReferences(PipelineReferenceKind.SUBTASK, "custom_action_param.sub").orEmpty()
        }
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
        if (objectValue != null) {
            val properties = objectValue.propertyList
                .filterNot { it.name == "type" }
                .take(4)
                .joinToString(", ") { property ->
                    "${property.name}=${summarizeInlineValue(property.value)}"
                }
            return compactSummary(when {
                type == null && properties.isEmpty() -> "{}"
                type == null -> properties
                properties.isEmpty() -> type
                else -> "$type（$properties）"
            })
        }
        return compactSummary(scalarText(value.text))
    }

    private fun summarizeInlineValue(value: JsonValue?, depth: Int = 0): String = when (value) {
        null -> ""
        is JsonObject -> {
            val type = value.findProperty("type")?.value?.let(::scalarText)
            if (depth >= 2) {
                if (type.isNullOrEmpty()) "{…}" else type
            } else {
                val visibleProperties = value.propertyList.filterNot { it.name == "type" }.take(3)
                val properties = visibleProperties.joinToString(", ") { property ->
                    "${property.name}=${summarizeInlineValue(property.value, depth + 1)}"
                }
                val suffix = if (value.propertyList.count { it.name != "type" } > visibleProperties.size) ", …" else ""
                when {
                    type != null && properties.isNotEmpty() -> "$type{$properties$suffix}"
                    type != null -> type
                    properties.isNotEmpty() -> "{$properties$suffix}"
                    else -> "{}"
                }
            }
        }

        is JsonArray -> {
            val values = value.valueList.take(3).joinToString(", ") { item -> summarizeInlineValue(item, depth + 1) }
            if (value.valueList.size > 3) "[$values, …]" else "[$values]"
        }

        else -> scalarText(value.text)
    }

    private fun compactSummary(value: String): String =
        value.take(180).let { if (it.length < value.length) "$it…" else it }

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

}
