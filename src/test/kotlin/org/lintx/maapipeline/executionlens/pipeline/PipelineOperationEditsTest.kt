package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonObject
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle

class PipelineOperationEditsTest : BasePlatformTestCase() {
    fun testPreviewHintsKeepTheirScopeAndEditingAHintNeverReplacesRuntimeParameters() {
        val text = """
            {"A"
              // @preview images/example.png
              : {
                // @roi [1, 2, 3, 4]
                // @roi target [40, 50, 60, 70]
                "recognition":"And", "all_of":[
                  {
                    // @roi [10, 20, 30, 40]
                    "recognition":"OCR", "roi":"PreviousResult"
                  },
                  {"recognition":"OCR", "roi":[/* @roi [90, 90, 90, 90] */ 5,6,7,8]}
                ], "action":"Click", "target":"PreviousResult"
              }}
        """.trimIndent()
        val owner = PipelineOperationEdits.objectValue(project, text)!!.findProperty("A")!!.value as JsonObject
        val raw = PipelinePreviewHints.sourceText(owner.parent as com.intellij.json.psi.JsonProperty)
        val node = PipelineNodeLocation("A", TextRange(0, 1), TextRange(0, text.length), rawJson = raw)
        assertEquals("images/example.png", PipelineVisualModel.preview(raw))
        val recognition = PipelineVisualModel.operation(node)
        assertEquals(listOf(10, 20, 30, 40), PipelineVisualModel.region(recognition.children[0]).coordinates)
        assertEquals(listOf(5, 6, 7, 8), PipelineVisualModel.region(recognition.children[1]).coordinates)
        val action = PipelineVisualModel.operation(node, true)
        assertEquals(listOf(40, 50, 60, 70), PipelineVisualModel.region(action).coordinates)
        val edit = PipelineOperationEdits.roiEdit(project, owner, recognition.children[0], "roi", Rectangle(21,22,23,24))!!
        assertEquals(text.replace("@roi [10, 20, 30, 40]", "@roi [21, 22, 23, 24]"), edit.applyTo(text))
        val target = PipelineOperationEdits.roiEdit(project, owner, action, "target", Rectangle(31,32,33,34))!!
        assertEquals(text.replace("@roi target [40, 50, 60, 70]", "@roi target [31, 32, 33, 34]"), target.applyTo(text))
        val unkeyed = PipelineVisualModel.operation(node.copy(rawJson = "{\n// @roi [1,2,3,4]\n\"action\":\"Click\"}"), true)
        assertNull(PipelineVisualModel.region(unkeyed).coordinates)
    }

    fun testV1EditorIncludesParametersPreservesCommentsAndDoesNotChangeAction() {
        val text = """
            {"A": {
              // 节点说明
              "recognition": "And",
              "all_of": [
                {"recognition": "OCR", /* 子条件注释 */ "roi": [10,20,30,40], "expected": "确认"},
                {"recognition": "TemplateMatch", "roi": [1,2,3,4], "template": "Confirm.png"}
              ],
              "box_index": 1,
              // 动作不能被条件编辑覆盖
              "action": "Click", "target": [5,6,7,8], "next": ["B"]
            }}
        """.trimIndent()
        val owner = PipelineOperationEdits.objectValue(project, text)!!.findProperty("A")!!.value as JsonObject
        val fragment = PipelineOperationEdits.fragment(owner, false)
        assertTrue(fragment.contains("all_of")); assertTrue(fragment.contains("expected")); assertTrue(fragment.contains("box_index"))
        assertFalse(fragment.contains("target"))
        assertNull(PipelineOperationEdits.operationEdit(project, owner, fragment, false))
        val edit = PipelineOperationEdits.operationEdit(project, owner, fragment.replace("10,20,30,40", "20,30,40,50"), false)!!
        val result = edit.applyTo(text)
        assertTrue(result.contains("/* 子条件注释 */")); assertTrue(result.contains("// 节点说明"))
        assertTrue(result.contains("\"action\": \"Click\", \"target\": [5,6,7,8], \"next\": [\"B\"]"))
        assertTrue(result.contains("20")); assertNotNull(PipelineOperationEdits.objectValue(project, result))
        val action = PipelineOperationEdits.fragment(owner, true)
        assertTrue(action.contains("Click")); assertTrue(action.contains("target")); assertFalse(action.contains("all_of"))
    }

    fun testV2TypeAndParamRoundTripAndFieldRemovalInsertionAreValidJsonc() {
        val text = """{"A":{"recognition":{"type":"OCR","param":{"expected":"OK",/* 保留 */"roi":[1,2,3,4]}},"action":"Click","target":[5,6,7,8]}}"""
        val owner = PipelineOperationEdits.objectValue(project, text)!!.findProperty("A")!!.value as JsonObject
        val fragment = PipelineOperationEdits.fragment(owner, false)
        assertTrue(fragment.contains("param")); assertTrue(fragment.contains("expected"))
        val edit = PipelineOperationEdits.operationEdit(project, owner,
            """{"recognition":{"type":"OCR","param":{"expected":"Cancel","threshold":0.9}}}""", false)!!
        val result = edit.applyTo(text)
        assertTrue(result.contains("/* 保留 */")); assertTrue(result.contains("Cancel")); assertTrue(result.contains("threshold"))
        assertFalse(result.contains("\"roi\"")); assertTrue(result.contains("\"target\":[5,6,7,8]"))
        assertNotNull(PipelineOperationEdits.objectValue(project, result))
        assertNotNull(PipelineOperationEdits.validateFragment(project, """{"next":["Wrong"]}""", false))
    }

    fun testRoiWritesTheSecondNestedConditionAndReversesOffset() {
        val text = """{"A":{"recognition":{"type":"And","param":{"all_of":[{"recognition":"OCR","roi":[1,2,3,4]}, {"type":"TemplateMatch","param":{"roi":[10,20,30,40],"roi_offset":[2,3,4,5]}}]}},"action":"Click"}}"""
        val owner = PipelineOperationEdits.objectValue(project, text)!!.findProperty("A")!!.value as JsonObject
        val node = PipelineNodeLocation("A", TextRange(0, 1), TextRange(0, text.length), rawJson = owner.text)
        val operation = PipelineVisualModel.operation(node).children[1]
        assertEquals(listOf("recognition", "param", "all_of", "1", "param", "roi"), operation.parameterPaths["roi"])
        val edit = PipelineOperationEdits.roiEdit(project, owner, operation, "roi", Rectangle(50,60,70,80))!!
        val result = edit.applyTo(text)
        assertTrue(result.contains("\"roi\":[1,2,3,4]"))
        assertTrue(result.contains("[48, 57, 66, 75]"))
        assertTrue(result.contains("\"roi_offset\":[2,3,4,5]"))
        val action = PipelineVisualModel.operation(node, true)
        assertNull(PipelineOperationEdits.roiEdit(project, owner, action, "target", Rectangle(1,2,3,4)))
    }

    fun testMissingV2RoiIsInsertedInsideParamAndReferenceUsesItsOwnDefinition() {
        val text = """{"A":{"recognition":{"type":"OCR"}}, "B":{"recognition":"OCR","roi":[1,2,3,4]}}"""
        val root = PipelineOperationEdits.objectValue(project, text)!!
        val owner = root.findProperty("A")!!.value as JsonObject
        val node = PipelineNodeLocation("A", TextRange(0,1), TextRange(0,text.length), rawJson = owner.text)
        val edit = PipelineOperationEdits.roiEdit(project, owner, PipelineVisualModel.operation(node), "roi", Rectangle(1,2,3,4))!!
        val result = PipelineOperationEdits.objectValue(project, edit.applyTo(text))!!
        val value = PipelineOperationEdits.at(result.findProperty("A")!!.value!!, listOf("recognition","param","roi"))!!
        assertEquals("[1, 2, 3, 4]", value.text)
        val reference = PipelineVisualModel.operation(node.copy(rawJson = """{"recognition":"And","all_of":["B"]}"""), resolve = {
            node.copy(name = "B", rawJson = root.findProperty("B")!!.value!!.text)
        }).children.single()
        assertEquals("B", reference.source!!.name); assertTrue(reference.ownerPath.isEmpty())
        assertEquals(listOf("roi"), reference.parameterPaths["roi"])
    }
}
