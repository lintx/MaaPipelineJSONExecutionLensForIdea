package com.maapipeline.executionlens.pipeline

import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineFileDetectorTest {
    @Test
    fun recognizesNodeStructureAndCommentedJsoncOutsidePipelineDirectory() {
        assertTrue(PipelineFileDetector.isPipelineFile(LightVirtualFile("Nodes.jsonc"), """// 注释
            {"Main": {"next": ["Other"]}}"""))
    }

    @Test
    fun leavesOrdinaryJsonTasksSchemaAndStringContentsAlone() {
        val file = LightVirtualFile("Other.json")
        listOf(
            """{"next": "page"}""",
            """{"tasks": [{"pipeline_override": {"Main": {"next": ["Other"]}}}]}""",
            """{"properties": {"next": {"type": "array"}}}""",
            """{"note": "\"next\": []"}""",
            """// "next": []
                {"settings": {"color": "blue"}}""",
        ).forEach { text -> assertFalse(PipelineFileDetector.isPipelineFile(file, text), text) }
    }

    @Test
    fun rejectsOtherExtensionsAndMalformedNonPipelineFiles() {
        assertFalse(PipelineFileDetector.isPipelineFile(LightVirtualFile("Notes.txt"), """{"Main":{"next":[]}}"""))
        assertFalse(PipelineFileDetector.isPipelineFile(LightVirtualFile("Broken.json"), """{"Main":{"next": """))
    }
}
