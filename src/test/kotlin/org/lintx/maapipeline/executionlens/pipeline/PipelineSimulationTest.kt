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
        execution.completeReturns()
        assertEquals("A", execution.frames[2].node.name)
        assertEquals("B", execution.frames[2].anchors["Inner"])
        assertNull(execution.choices(2).first().node)
        assertTrue(execution.choices(2).first().unavailable!!.contains("max_hit"))
        assertTrue(execution.advance(0, execution.choices(0).last()))
        assertEquals(2, execution.frames.size)
        assertFalse(execution.frames.last().anchors.containsKey("Inner"))
        assertNull(execution.frames.last().targets["target"])
    }

    fun testSubtaskReturnsShareBindingsAndHonorRandomSelectionCount() {
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
        assertEquals("A", execution.frames.last().anchors["Parent"])
        execution.completeReturns()
        val resumed = execution.frames.last()
        assertEquals("Caller", resumed.node.name)
        assertEquals("A", resumed.anchors["Parent"])
        assertEquals("C", resumed.anchors["Child"])
        assertEquals(mapOf("A" to 1L, "Caller" to 1L, "C" to 1L), resumed.hits)
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
        assertEquals("C", execution.frames.last().anchors["Child"])
        assertEquals(resumed.hits, execution.frames.last().hits)
        assertTrue(execution.finish(3, true))
        assertTrue(execution.frames.last().ended)
    }

    fun testSequentialSubtasksShareResultsClearsAndHitLimitsWithoutMutatingHistory() {
        myFixture.addFileToProject("assets/interface.json", """{"name":"MaaEnd","github":"https://github.com/MaaEnd/MaaEnd"}""")
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"A":{"anchor":{"Slot":"B"},"next":["Caller"]},
             "Caller":{"anchor":{"Slot":"Done"},"action":"Custom","custom_action":"SubTask",
               "custom_action_param":{"sub":["B","C"]},"next":["B","Done"]},
             "B":{
               // @roi [10,20,30,40]
               "recognition":"OCR","action":"Click","target":"[Anchor]Slot","max_hit":1,"anchor":{"Slot":"","Child":"B"}},
             "C":{"action":"Click","target":"B"},"Done":{"action":"Click","target":"B","next":["Retry"]},
             "Retry":{"action":"Custom","custom_action":"SubTask","custom_action_param":{"sub":["B"]}}}
        """.trimIndent()).virtualFile
        val nodes = PipelineDocumentIndexer.index(project, source).projectNodes
        val execution = PipelineSimulation(nodes, PipelineEnvironment("Win32", "Win32", "Win32"))
        execution.start(nodes.single { it.name == "A" })
        assertTrue(execution.advance(0, execution.choices(0).single()))
        val beforeCall = execution.frames.last()
        assertTrue(execution.advance(1, execution.choices(1).single()))
        assertEquals(listOf(10,20,30,40), execution.frames.last().targets["target"])
        assertFalse(execution.frames.last().anchors.containsKey("Slot"))
        execution.completeReturns()
        assertEquals("Caller", execution.frames.last().node.name)
        assertFalse(execution.frames.last().anchors.containsKey("Slot"))
        assertTrue(execution.advance(3, execution.choices(3).single()))
        assertEquals(listOf(10,20,30,40), execution.frames.last().targets["target"])
        assertEquals("B", execution.frames.last().anchors["Child"])
        execution.completeReturns()
        assertEquals("Done", execution.frames.last().anchors["Slot"])
        assertNull(execution.choices(5).first().node)
        assertTrue(execution.choices(5).first().unavailable!!.contains("max_hit"))
        assertTrue(execution.advance(5, execution.choices(5).last()))
        assertEquals(listOf(10,20,30,40), execution.frames.last().targets["target"])
        assertEquals(mapOf("A" to 1L, "Caller" to 1L, "B" to 1L, "C" to 1L, "Done" to 1L), execution.frames.last().hits)
        assertTrue(execution.advance(6, execution.choices(6).single()))
        assertNull(execution.choices(7).single().node)
        assertTrue(execution.choices(7).single().unavailable!!.contains("max_hit"))
        assertEquals("B", beforeCall.anchors["Slot"])
        assertTrue(beforeCall.results.isEmpty())
        assertEquals(mapOf("A" to 1L, "Caller" to 1L), beforeCall.hits)
        assertTrue(execution.advance(1, execution.choices(1).single()))
        assertEquals(3, execution.frames.size)
        assertEquals(1L, execution.frames.last().hits["B"])
        assertFalse(execution.frames.last().hits.containsKey("C"))
        assertFalse(execution.frames.last().hits.containsKey("Done"))
    }

    fun testFailedSubtaskKeepsSharedStateForEveryParentOutcomeAndLaterCalls() {
        myFixture.addFileToProject("assets/interface.json", """{"name":"MaaEnd","github":"https://github.com/MaaEnd/MaaEnd"}""")
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"Caller":{"anchor":{"Completed":"Caller"},"action":"Custom","custom_action":"SubTask",
              "custom_action_param":{"sub":["B","C"]},"next":["Done"],"on_error":["Error"]},
             "B":{
               // @roi [1,2,3,4]
               "recognition":"OCR","anchor":{"Child":"B"}},
             "C":{"action":"Click","target":"B"},"Done":{},"Error":{"action":"Click","target":"B"}}
        """.trimIndent()).virtualFile
        val nodes = PipelineDocumentIndexer.index(project, source).projectNodes
        for (outcome in PipelineSimulationParentOutcome.entries) {
            val execution = PipelineSimulation(nodes, PipelineEnvironment("Adb", "ADB", "Adb"))
            execution.start(nodes.single { it.name == "Caller" })
            assertTrue(execution.advance(0, execution.choices(0).single()))
            assertTrue(execution.finish(1, true, outcome))
            val resumed = execution.frames.last()
            assertEquals("B", resumed.anchors["Child"])
            assertEquals(listOf(1,2,3,4), resumed.results["B"])
            assertEquals(mapOf("Caller" to 1L, "B" to 1L), resumed.hits)
            if (outcome == PipelineSimulationParentOutcome.CONTINUE) {
                assertFalse(resumed.anchors.containsKey("Completed"))
                assertTrue(execution.advance(2, execution.choices(2).single()))
                assertEquals(listOf(1,2,3,4), execution.frames.last().targets["target"])
                execution.completeReturns()
                assertEquals("Caller", execution.frames.last().anchors["Completed"])
            } else {
                assertEquals("Caller", resumed.anchors["Completed"])
                assertEmpty(resumed.pending)
                if (outcome == PipelineSimulationParentOutcome.FAILED) {
                    assertTrue(resumed.failed)
                    assertTrue(execution.advance(2, execution.errors(2).single()))
                    assertEquals(listOf(1,2,3,4), execution.frames.last().targets["target"])
                } else {
                    assertFalse(resumed.failed)
                    assertEquals("Done", execution.choices(2).single().node?.name)
                }
            }
            assertTrue(execution.frames[0].anchors.isEmpty())
            assertEquals(mapOf("Caller" to 1L), execution.frames[0].hits)
        }
    }

    fun testNestedJumpBackReturnsStayWithinSubtaskBeforeReturningToTheOuterCaller() {
        myFixture.addFileToProject("assets/interface.json", """{"name":"MaaEnd","github":"https://github.com/MaaEnd/MaaEnd"}""")
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"Root":{"next":["[JumpBack]Caller","Tail"]},
             "Caller":{"action":"Custom","custom_action":"SubTask","custom_action_param":{"sub":["Child"]},"next":["After"]},
             "Child":{"next":["[JumpBack]Inner","ChildEnd"]},"Inner":{"max_hit":1,"anchor":{"InnerAnchor":"Inner"}},
             "ChildEnd":{},"After":{},"Tail":{}}
        """.trimIndent()).virtualFile
        val nodes = PipelineDocumentIndexer.index(project, source).projectNodes
        val execution = PipelineSimulation(nodes, PipelineEnvironment("Win32", "Win32", "Win32"))
        execution.start(nodes.single { it.name == "Root" })
        repeat(3) { index -> assertTrue(execution.advance(index, execution.choices(index).first())) }
        execution.completeReturns()
        assertEquals("Child", execution.frames.last().node.name)
        assertEquals(listOf("Root", "Caller"), execution.frames.last().returns.map { it.node.name })
        assertNull(execution.choices(4).first().node)
        assertTrue(execution.advance(4, execution.choices(4).last()))
        execution.completeReturns()
        assertEquals("Caller", execution.frames.last().node.name)
        assertEquals(listOf("Root"), execution.frames.last().returns.map { it.node.name })
        assertTrue(execution.advance(6, execution.choices(6).single()))
        execution.completeReturns()
        assertEquals("Root", execution.frames.last().node.name)
        assertEmpty(execution.frames.last().returns)
        assertEquals("Inner", execution.frames.last().anchors["InnerAnchor"])
        assertEquals(1L, execution.frames.last().hits["Caller"])
        assertEquals(1L, execution.frames.last().hits["Inner"])
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
