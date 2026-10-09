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
)

data class PipelineReference(
    val displayName: String,
    val targetName: String,
    val range: TextRange,
    val isJumpBack: Boolean,
    val isAnchor: Boolean,
    val targetExists: Boolean = false,
    val isCycle: Boolean = false,
    val isSharedTarget: Boolean = false,
)

data class PipelineDocumentModel(
    val file: VirtualFile,
    val version: Long,
    val nodes: List<PipelineNodeLocation>,
    val hasSyntaxErrors: Boolean,
)

internal object PipelineDocumentIndexer {
    fun index(project: Project, file: VirtualFile): PipelineDocumentModel {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val psiFile = PsiManager.getInstance(project).findFile(file)
        val jsonFile = psiFile as? JsonFile
        val rootObject = jsonFile?.topLevelValue as? JsonObject
        val parsedNodes = rootObject?.propertyList.orEmpty().mapNotNull { property ->
            val nameRange = property.nameElement.textRange ?: return@mapNotNull null
            val propertyRange = property.textRange ?: nameRange
            val valueObject = property.value as? JsonObject
            PipelineNodeLocation(property.name, nameRange, propertyRange)
                .withDetails(valueObject)
        }
        val nodeNames = parsedNodes.mapTo(linkedSetOf()) { it.name }
        val graph = parsedNodes.associate { node ->
            node.name to (node.next + node.onError).map(PipelineReference::targetName)
        }
        val inboundCounts = (parsedNodes.flatMap { it.next + it.onError })
            .filter { it.targetName in nodeNames }
            .groupingBy(PipelineReference::targetName)
            .eachCount()
        val nodes = parsedNodes.map { node ->
            node.copy(
                next = node.next.map { it.withGraphState(node.name, nodeNames, graph, inboundCounts) },
                onError = node.onError.map { it.withGraphState(node.name, nodeNames, graph, inboundCounts) },
            )
        }
        return PipelineDocumentModel(
            file = file,
            version = document?.modificationStamp ?: -1L,
            nodes = nodes,
            hasSyntaxErrors = jsonFile == null || rootObject == null,
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
        return PipelineFieldLocation(name, summarizeValue(value), range)
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

    private fun PipelineReference.withGraphState(
        sourceName: String,
        nodeNames: Set<String>,
        graph: Map<String, List<String>>,
        inboundCounts: Map<String, Int>,
    ): PipelineReference {
        val targetExists = targetName in nodeNames
        val isCycle = targetExists && canReach(graph, targetName, sourceName)
        return copy(
            targetExists = targetExists,
            isCycle = isCycle,
            isSharedTarget = targetExists && (inboundCounts[targetName] ?: 0) > 1,
        )
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
