package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PipelineSimulationTest : BasePlatformTestCase() {
    fun testJumpBackAnchorTargetsAndRollbackKeepIndependentSnapshots() {
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"A":{"anchor":{"Slot":"B"},"next":["[JumpBack][Anchor]Slot","C"]},
             "B":{
               // @roi [10,20,30,40]
               "recognition":"OCR","action":"Click","anchor":{"Inner":"B"}},
             "C":{"action":"Click","target":"B"}}
        """.trimIndent()).virtualFile
        val nodes = PipelineDocumentIndexer.index(project, source).projectNodes
        val execution = PipelineSimulation(nodes, PipelineEnvironment("Win32", "Win32", "Win32"), {
            PipelineVisualModel.parse("""{"Default":{"max_hit":1},"Click":{"target_offset":[1,2,3,4]}}""")!!
        })
        execution.start(nodes.single { it.name == "A" })
        assertTrue(execution.advance(0, execution.choices(0).first()))
        assertEquals(listOf(11,22,33,44), execution.frames[1].targets["target"])
        assertTrue(execution.finish(1))
        assertEquals("A", execution.frames[2].node.name)
        assertEquals("B", execution.frames[2].anchors["Inner"])
        assertNull(execution.choices(2).first().node)
        assertTrue(execution.choices(2).first().unavailable!!.contains("max_hit"))
        assertTrue(execution.advance(0, execution.choices(0).last()))
        assertEquals(2, execution.frames.size)
        assertFalse(execution.frames.last().anchors.containsKey("Inner"))
        assertNull(execution.frames.last().targets["target"])
    }

    fun testSubtaskReturnsRestoreParentBindingsAndHonorRandomSelectionCount() {
        myFixture.addFileToProject("assets/interface.json", """{"name":"MaaEnd","github":"https://github.com/MaaEnd/MaaEnd"}""")
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"A":{"anchor":{"Parent":"A"},"next":["Caller"]},
             "Caller":{"action":"Custom","custom_action":"SubTask","custom_action_param":{"sub":["B","C"],"random_choice":1}},
             "B":{"anchor":{"Child":"B"}},"C":{"anchor":{"Child":"C"}}}
        """.trimIndent()).virtualFile
        val nodes = PipelineDocumentIndexer.index(project, source).projectNodes
        val execution = PipelineSimulation(nodes, PipelineEnvironment("Adb", "ADB", "Adb"))
        execution.start(nodes.single { it.name == "A" })
        assertTrue(execution.advance(0, execution.choices(0).single()))
        assertEquals(2, execution.choices(1).size)
        assertTrue(execution.advance(1, execution.choices(1).last()))
        assertFalse(execution.frames.last().anchors.containsKey("Parent"))
        assertTrue(execution.finish(2))
        val resumed = execution.frames.last()
        assertEquals("Caller", resumed.node.name)
        assertEquals("A", resumed.anchors["Parent"])
        assertFalse(resumed.anchors.containsKey("Child"))
        assertEmpty(resumed.pending)
        assertTrue(execution.finish(3))
        assertTrue(execution.frames.last().ended)
        assertFalse(execution.finish(2, true))
        assertTrue(execution.requiresParentOutcome(2))
        assertTrue(execution.finish(2, true, PipelineSimulationParentOutcome.FAILED))
        assertEquals(4, execution.frames.size)
        assertEquals("Caller", execution.frames.last().node.name)
        assertTrue(execution.frames.last().failed)
        assertEquals("A", execution.frames.last().anchors["Parent"])
        assertTrue(execution.finish(3, true))
        assertTrue(execution.frames.last().ended)
    }

    fun testTemporaryOverrideResolvesNextAndKeepsPreviewComments() {
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"A":{
              // @roi [10,20,30,40]
              "recognition":"OCR"},"B":{"action":"Click"}}
        """.trimIndent()).virtualFile
        val model = PipelineDocumentIndexer.index(project, source)
        val patches = PipelineTemporaryOverrides.parse(project, """{"A":{"next":["B"],"roi_offset":[1,2,3,4]}}""")
        val effective = PipelineDocumentIndexer.effectiveNodes(project, model, patches, emptyMap()).values.single()
        assertEquals("B", effective.next.single().target!!.name)
        assertEquals(listOf(11,22,33,44), PipelineVisualModel.region(PipelineVisualModel.operation(effective)).coordinates)
        assertTrue(patches.single().fields.all { it.isDerived })
        assertNotNull(PipelineTemporaryOverrides.validate("{\"A\":1}"))
    }
}
