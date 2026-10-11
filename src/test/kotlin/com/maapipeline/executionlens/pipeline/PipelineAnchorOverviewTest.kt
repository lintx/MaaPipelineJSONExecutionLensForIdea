package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineAnchorOverviewTest {
    private val firstFile = LightVirtualFile("First.json")
    private val secondFile = LightVirtualFile("Second.json")
    private val range = TextRange(0, 3)

    private fun node(name: String, definitions: List<PipelineAnchorDefinition> = emptyList()) = PipelineNodeLocation(
        name, range, range, anchorDefinitions = definitions, sourceFile = firstFile,
    )

    private fun rows(vararg nodes: PipelineNodeLocation, generated: Boolean = false): List<PipelineAnchorManagerRow> =
        PipelineAnchorOverview.build(PipelineDocumentModel(
            firstFile, 1, nodes.toList(), false, projectNodes = nodes.toList(),
            generatedFiles = if (generated) setOf(firstFile) else emptySet(),
        ))

    @Test
    fun distinguishesMappedSelfAndClearedBindings() {
        val definitions = listOf(
            PipelineAnchorDefinition("Self", range, null, false),
            PipelineAnchorDefinition("Mapped", range, "Target", false),
            PipelineAnchorDefinition("Cleared", range, null, true),
        )
        val result = rows(node("Owner", definitions), node("Target").copy(sourceFile = secondFile)).associateBy { it.name }
        assertEquals("绑定到 Owner", result.getValue("Self").targetText)
        assertEquals(secondFile, result.getValue("Mapped").targetFile)
        assertEquals("清除绑定", result.getValue("Cleared").targetText)
        assertNull(result.getValue("Cleared").targetFile)
    }

    @Test
    fun missingMappingNeverFallsBackToOwnerNode() {
        val result = rows(node("Owner", listOf(PipelineAnchorDefinition("Anchor", range, "Missing", false)))).single()
        assertEquals("绑定到 Missing", result.targetText)
        assertNull(result.targetFile)
        assertTrue(result.warningText.orEmpty().contains("不存在"))
    }

    @Test
    fun multipleRuntimeSettersRemainSeparateAndAreNotReportedAsProtocolErrors() {
        val definition = PipelineAnchorDefinition("Anchor", range, null, false)
        val result = rows(node("First", listOf(definition)), node("Second", listOf(definition)).copy(sourceFile = secondFile))
        assertEquals(2, result.size)
        assertEquals(setOf("First", "Second"), result.map { it.ownerNodeName }.toSet())
        assertTrue(result.all { it.warningText.orEmpty().contains("运行时最后设置生效") })
    }

    @Test
    fun undefinedAnchorStillListsCrossFileAndEmbeddedReferences() {
        val reference = PipelineReference("[Anchor]Missing", "Missing", range, false, true)
        val result = rows(node("Owner").copy(
            onError = listOf(reference),
            embeddedReferences = listOf(reference.copy(kind = PipelineReferenceKind.TARGET, sourceFile = secondFile)),
        )).single()
        assertNull(result.definitionFile)
        assertFalse(result.canEdit)
        assertEquals(2, result.references.size)
        assertEquals(secondFile, result.references.last().file)
    }

    @Test
    fun generatedDefinitionsRemainVisibleButCannotBeEdited() {
        val result = rows(node("Owner", listOf(PipelineAnchorDefinition("Anchor", range, null, false))), generated = true).single()
        assertFalse(result.canEdit)
        assertEquals(firstFile, result.definitionFile)
        assertTrue(result.warningText.orEmpty().contains("生成文件"))
    }
}
