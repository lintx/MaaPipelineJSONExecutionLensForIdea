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
import com.intellij.psi.PsiManager

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
)

data class PipelineFieldLocation(
    val name: String,
    val summary: String,
    val range: TextRange,
    val valueRange: TextRange = range,
    val isString: Boolean = false,
)

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
    val isAmbiguousTarget: Boolean = false,
    val isCrossFile: Boolean = false,
)

data class PipelineNodeTarget(
    val file: VirtualFile,
    val name: String,
    val nameRange: TextRange,
    val description: String?,
    val recognitionSummary: String?,
    val actionSummary: String?,
    val nextCount: Int,
)

data class PipelineDocumentModel(
    val file: VirtualFile,
    val version: Long,
    val nodes: List<PipelineNodeLocation>,
    val hasSyntaxErrors: Boolean,
    val isGenerated: Boolean = false,
)

internal object PipelineDocumentIndexer {
    fun index(project: Project, file: VirtualFile): PipelineDocumentModel {
        val parsedFile = parseFile(project, file)
        val parsedFiles = findPipelineJsonFiles(file).map { parseFile(project, it) }
        val entries = parsedFiles.flatMap { parsed ->
            parsed.nodes.map { node -> PipelineNodeEntry(parsed.file, node) }
        }
        val candidatesByName = entries.groupBy { it.node.name }
        val graph = entries.associate { entry ->
            entry.key to allReferences(entry.node)
                .mapNotNull { candidatesByName[it.targetName].orEmpty().singleOrNull()?.key }
        }
        val inboundCounts = graph.values
            .flatten()
            .groupingBy { it }
            .eachCount()
        val nodes = parsedFile.nodes.map { node ->
            val entry = PipelineNodeEntry(file, node)
            node.copy(
                next = resolveReferences(entry, node.next, candidatesByName, graph, inboundCounts),
                onError = resolveReferences(entry, node.onError, candidatesByName, graph, inboundCounts),
            )
        }
        return PipelineDocumentModel(
            file = file,
            version = parsedFile.version,
            nodes = nodes,
            hasSyntaxErrors = parsedFile.hasSyntaxErrors,
            isGenerated = parsedFile.isGenerated,
        )
    }

    private data class ParsedPipelineFile(
        val file: VirtualFile,
        val version: Long,
        val nodes: List<PipelineNodeLocation>,
        val hasSyntaxErrors: Boolean,
        val isGenerated: Boolean,
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

    private fun parseFile(project: Project, file: VirtualFile): ParsedPipelineFile {
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
        return ParsedPipelineFile(
            file = file,
            version = document?.modificationStamp ?: -1L,
            nodes = nodes,
            hasSyntaxErrors = jsonFile == null || rootObject == null,
            isGenerated = hasGeneratedHeader(document?.text.orEmpty()),
        )
    }

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
        val files = mutableListOf<VirtualFile>()

        fun visit(current: VirtualFile) {
            if (current.isDirectory) {
                current.children.orEmpty().forEach(::visit)
            } else if (current.extension.equals("json", ignoreCase = true)) {
                files += current
            }
        }

        visit(pipelineRoot)
        return files.ifEmpty { listOf(file) }
    }

    private fun allReferences(node: PipelineNodeLocation): List<PipelineReference> =
        node.next + node.onError

    private fun resolveReferences(
        source: PipelineNodeEntry,
        references: List<PipelineReference>,
        candidatesByName: Map<String, List<PipelineNodeEntry>>,
        graph: Map<String, List<String>>,
        inboundCounts: Map<String, Int>,
    ): List<PipelineReference> = references.map { reference ->
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

    private fun PipelineNodeLocation.withDetails(valueObject: JsonObject?): PipelineNodeLocation {
        if (valueObject == null) return this
        return copy(
            description = valueObject.findProperty("desc")?.value?.let(::scalarText),
            recognition = valueObject.findProperty("recognition")?.toFieldLocation(),
            action = valueObject.findProperty("action")?.toFieldLocation(),
            next = valueObject.findProperty("next")?.toReferences().orEmpty(),
            onError = valueObject.findProperty("on_error")?.toReferences().orEmpty(),
            anchor = valueObject.findProperty("anchor")?.toFieldLocation(),
        )
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

    private fun JsonProperty.toReferences(): List<PipelineReference> {
        val values = when (val propertyValue = value) {
            is JsonArray -> propertyValue.valueList
            null -> emptyList()
            else -> listOf(propertyValue)
        }
        return values.mapNotNull { value ->
            val range = value.textRange ?: return@mapNotNull null
            val raw = scalarText(value.text)
            val markerState = parseReferenceMarkers(raw)
            PipelineReference(
                displayName = raw,
                targetName = markerState.targetName,
                range = range,
                isJumpBack = markerState.isJumpBack,
                isAnchor = markerState.isAnchor,
                isString = value.text.trim().let { it.startsWith("\"") && it.endsWith("\"") },
            )
        }
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
