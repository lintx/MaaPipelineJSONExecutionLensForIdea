package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineTaskContextTest {
    private val definitions = PipelineTaskContext.parse("""
        {"task":[{"name":"Transfer", "label":"库存转移", "entry":"Start", "option":["Return"]},
                  {"name":"Other", "entry":"Start"}],
         "option":{"Return":{"default_case":"No", "cases":[
             {"name":"No"}, {"name":"Yes", "option":["Item"]}]},
             "Item":{"default_case":"Copper", "cases":[{"name":"Copper"},{"name":"Iron"}]}}}
    """.trimIndent())

    @Test fun keepsTaskIdentitySeparateFromSharedEntryAndPreservesMetadata() {
        assertEquals(listOf("Transfer", "Other"), definitions.tasks.map { it.id })
        assertEquals("库存转移", definitions.tasks[0].label)
        assertEquals(listOf("Return"), definitions.tasks[0].options)
    }

    @Test fun appliesOnlySelectedCasesAndTheirEnabledChildOptions() {
        val task = definitions.tasks[0]
        assertEquals(setOf("/task/0", "/option/Return", "/option/Return/cases/0"),
            PipelineTaskContext.activePaths(task.path, task.options, definitions.options, emptyMap()))
        val selected = PipelineTaskContext.activePaths(task.path, task.options, definitions.options,
            mapOf("Return" to listOf("Yes"), "Item" to listOf("Iron")))
        assertTrue("/option/Item/cases/1" in selected)
        assertFalse("/option/Item/cases/0" in selected)
        assertFalse("/task/1" in selected)
    }

    @Test fun mergesNestedRecognitionParametersButReplacesNextArrays() {
        val result = PipelineTaskContext.merge(
            JsonParser.parseString("""{"recognition":"Custom","next":["A","B"]}"""),
            JsonParser.parseString("""{"recognition":{"param":{"custom_recognition_param":{"item":"Iron"}}},"next":["C"]}"""),
        ).asJsonObject
        assertEquals("Custom", result.getAsJsonObject("recognition").get("type").asString)
        assertEquals("Iron", result.getAsJsonObject("recognition").getAsJsonObject("param")
            .getAsJsonObject("custom_recognition_param").get("item").asString)
        assertEquals("[\"C\"]", result.get("next").toString())
    }

    @Test fun findsTasksByMultipleKeywordsAcrossIdUiTextAndComment() {
        assertTrue(PipelineTaskContext.matches("transfer 库存", "ItemTransfer", "库存转移", "背包"))
        assertTrue(PipelineTaskContext.matches("返程", "ItemTransfer", "备注：支持返程"))
        assertFalse(PipelineTaskContext.matches("不存在", "ItemTransfer", "库存转移"))
    }

    @Test fun substitutesTypedInputsWithoutChangingLiteralTextOrOtherOptionInputs() {
        val option = PipelineTaskOption("Times", "次数", "file:///tasks.json#/option/Times", emptyList(),
            emptyList(), emptyList(), listOf(PipelineTaskInput("Count", "数量", "1", "int", "")))
        val patch = JsonParser.parseString("""{"max_hit":"{Count}","desc":"共 {Count} 次","next":["Done"]}""")
        val result = PipelineTaskContext.substituteInputs(patch, option, mapOf("${option.path}/Count" to "3")).asJsonObject
        assertEquals(3, result.get("max_hit").asInt)
        assertFalse(result.get("max_hit").asJsonPrimitive.isString)
        assertEquals("共 {Count} 次", result.get("desc").asString)
        assertEquals("{Count}", patch.asJsonObject.get("max_hit").asString)
        assertEquals(1, PipelineTaskContext.substituteInputs(patch, option, emptyMap()).asJsonObject.get("max_hit").asInt)
    }

    @Test fun removedSavedChoicesUseDeclaredDefaultsWithoutGuessingTheFirstCase() {
        assertEquals(mapOf("Return" to listOf("No")), PipelineTaskContext.validSelections(definitions.options,
            mapOf("Return" to listOf("Removed"), "RemovedOption" to listOf("Yes"))))
        assertEquals(mapOf("Return" to emptyList()), PipelineTaskContext.validSelections(definitions.options,
            mapOf("Return" to emptyList())))
    }
}
