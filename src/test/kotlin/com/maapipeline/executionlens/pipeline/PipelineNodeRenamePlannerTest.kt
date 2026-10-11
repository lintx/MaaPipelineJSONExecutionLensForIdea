package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineNodeRenamePlannerTest {
    @Test
    fun updatesDefinitionReferencesAnchorTaskEntryAndOverrideInOnePlan() {
        val pipelineFile = LightVirtualFile("Main.json")
        val taskFile = LightVirtualFile("Task.json")
        val pipelineText = """
            {
              "Old": {"next": ["[JumpBack]Old"], "anchor": {"slot": "Old"}},
              "Other": {}
            }
        """.trimIndent()
        val taskText = """
            {"entry":"Old","pipeline_override":{"Old":{"next":["Old"]}}}
        """.trimIndent()
        val definitionRange = rangeOf(pipelineText, "\"Old\":")
        val referenceRange = rangeOf(pipelineText, "\"[JumpBack]Old\"")
        val anchorTargetRange = rangeOf(pipelineText, "\"Old\"", start = referenceRange.endOffset)
        val taskEntryRange = rangeOf(taskText, "\"Old\"")
        val overrideRange = rangeOf(taskText, "\"Old\"", start = taskEntryRange.endOffset)
        val overrideReferenceRange = rangeOf(taskText, "\"Old\"", start = overrideRange.endOffset)
        val oldNode = PipelineNodeLocation(
            name = "Old",
            nameRange = TextRange(definitionRange.startOffset, definitionRange.endOffset - 1),
            propertyRange = definitionRange,
            next = listOf(
                PipelineReference(
                    displayName = "[JumpBack]Old",
                    targetName = "Old",
                    range = referenceRange,
                    isJumpBack = true,
                    isAnchor = false,
                    sourceFile = pipelineFile,
                ),
            ),
            anchorDefinitions = listOf(
                PipelineAnchorDefinition(
                    name = "slot",
                    range = anchorTargetRange,
                    targetNodeName = "Old",
                    isCleared = false,
                    targetRange = anchorTargetRange,
                ),
            ),
            sourceFile = pipelineFile,
        )
        val override = PipelineOverrideLocation(
            file = taskFile,
            targetName = "Old",
            range = overrideRange,
            fields = listOf(
                PipelineFieldLocation(
                    name = "next",
                    summary = "Old",
                    range = overrideReferenceRange,
                    references = listOf(
                        PipelineReference(
                            displayName = "Old",
                            targetName = "Old",
                            range = overrideReferenceRange,
                            isJumpBack = false,
                            isAnchor = false,
                            sourceFile = taskFile,
                        ),
                    ),
                ),
            ),
        )
        val model = PipelineDocumentModel(
            file = pipelineFile,
            version = 1,
            nodes = listOf(oldNode),
            hasSyntaxErrors = false,
            pipelineOverrides = listOf(override),
            pipelineEntries = listOf(PipelineEntryLocation(taskFile, "Old", taskEntryRange)),
            projectNodes = listOf(oldNode),
        )

        val plan = PipelineNodeRenamePlanner.build(
            model,
            oldNode,
            "New",
            mapOf(pipelineFile to pipelineText, taskFile to taskText),
        )

        assertTrue(plan.conflicts.isEmpty())
        assertEquals(2, plan.affectedFileCount)
        assertTrue(plan.editCount >= 6)
        val pipelineResult = apply(plan, pipelineFile, pipelineText)
        val taskResult = apply(plan, taskFile, taskText)
        assertTrue(pipelineResult.contains("\"New\""))
        assertTrue(pipelineResult.contains("\"[JumpBack]New\""))
        assertTrue(pipelineResult.contains("\"slot\": \"New\""))
        assertTrue(taskResult.contains("\"entry\":\"New\""))
        assertTrue(taskResult.contains("\"New\":{"))
        assertTrue(taskResult.contains("\"next\":[\"New\"]"))
    }

    @Test
    fun reportsMissingAnchorTargetRangeInsteadOfWritingAWholeObject() {
        val file = LightVirtualFile("Main.json")
        val text = "{\"Old\":{\"anchor\":{\"slot\":\"Old\"}}}"
        val nodeRange = rangeOf(text, "\"Old\":")
        val node = PipelineNodeLocation(
            name = "Old",
            nameRange = TextRange(nodeRange.startOffset, nodeRange.endOffset - 1),
            propertyRange = nodeRange,
            anchorDefinitions = listOf(
                PipelineAnchorDefinition("slot", nodeRange, "Old", false),
            ),
            sourceFile = file,
        )
        val plan = PipelineNodeRenamePlanner.build(
            PipelineDocumentModel(file, 1, listOf(node), false, projectNodes = listOf(node)),
            node,
            "New",
            mapOf(file to text),
        )
        assertTrue(plan.conflicts.any { it.contains("Anchor slot") })
        assertEquals(1, plan.files.size)
    }

    private fun apply(
        plan: PipelineNodeRenamePlan,
        file: LightVirtualFile,
        text: String,
    ): String = PipelineJsonTextEdits.applyEdits(
        text,
        plan.files.first { it.file == file }.edits,
    )

    private fun rangeOf(text: String, value: String, start: Int = 0): TextRange {
        val offset = text.indexOf(value, start)
        check(offset >= 0) { "Missing test value: $value" }
        return TextRange(offset, offset + value.length)
    }
}
