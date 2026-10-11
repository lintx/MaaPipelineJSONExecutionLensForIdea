package com.maapipeline.executionlens.pipeline

import com.google.gson.JsonParser
import com.intellij.openapi.util.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineJsonTextEditsTest {
    @Test
    fun appendsToCommentOnlyContainersWithoutAddingLeadingComma() {
        assertEquals("{ // 保留\n  \"A\": {}\n}", PipelineJsonTextEdits.appendMember("{ // 保留\n}", "\"A\": {}"))
        assertEquals("[ /* 保留 */ \n  \"B\"\n]", PipelineJsonTextEdits.appendMember("[ /* 保留 */ ]", "\"B\""))
    }

    @Test
    fun insertsSeparatorBeforeTrailingLineComment() {
        val original = "[\n  \"A\" // 说明\n]"
        val result = PipelineJsonTextEdits.appendMember(original, "\"B\"")
        assertEquals("[\n  \"A\", // 说明\n  \"B\"\n]", result)
        assertEquals(listOf("A", "B"), JsonParser.parseString(result).asJsonArray.map { it.asString })
    }

    @Test
    fun reusesTrailingCommaAndPreservesItsComment() {
        val original = "{\n  \"A\": {}, // 尾逗号\n}"
        val result = PipelineJsonTextEdits.appendMember(original, "\"B\": {}")
        assertEquals("{\n  \"A\": {}, // 尾逗号\n  \"B\": {}\n}", result)
        assertEquals(listOf("A", "B"), PipelineNodeScanner.scanNodeNames(result))
    }

    @Test
    fun ignoresDelimitersAndCommentMarkersInsideEscapedStrings() {
        val original = "[\"url//x/*y*/}\\\"\"]"
        val result = PipelineJsonTextEdits.appendMember(original, "\"B\"")
        assertEquals(listOf("url//x/*y*/}\"", "B"), JsonParser.parseString(result).asJsonArray.map { it.asString })
    }

    @Test
    fun preservesWindowsNewlinesAndClosingIndent() {
        val original = "{\r\n    \"A\": {}\r\n  }"
        val result = PipelineJsonTextEdits.appendMember(original, "\"B\": {\n  \"next\": []\n}", "    ")
        assertEquals("{\r\n    \"A\": {},\r\n    \"B\": {\r\n      \"next\": []\r\n    }\r\n  }", result)
    }

    @Test
    fun keepsHeaderAndFooterComments() {
        val original = "// 文件头\n{} // 文件尾"
        val result = PipelineJsonTextEdits.appendMember(original, "\"A\": {}")
        assertEquals("// 文件头\n{\n  \"A\": {}\n} // 文件尾", result)
    }

    @Test
    fun removesFirstMiddleAndLastItemsWithoutRemovingComments() {
        val original = "[\"A\" /* 注释甲 */, // 注释乙\n \"B\", /* 注释丙 */ \"C\"]"
        for ((name, remaining) in listOf("A" to listOf("B", "C"), "B" to listOf("A", "C"), "C" to listOf("A", "B"))) {
            val start = original.indexOf("\"$name\"")
            val result = PipelineJsonTextEdits.applyEdits(original, PipelineJsonTextEdits.deletionEdits(original, TextRange(start, start + 3)))
            assertTrue(result.contains("/* 注释甲 */"))
            assertTrue(result.contains("// 注释乙"))
            assertTrue(result.contains("/* 注释丙 */"))
            assertEquals(remaining, JsonParser.parseString(result).asJsonArray.map { it.asString })
        }
    }

    @Test
    fun removesOnlyPropertyWithoutDestroyingItsComment() {
        val original = "{ /* 业务注释 */ \"A\": {} }"
        val start = original.indexOf("\"A\"")
        val end = original.indexOf("{}") + 2
        val result = PipelineJsonTextEdits.applyEdits(original, PipelineJsonTextEdits.deletionEdits(original, TextRange(start, end)))
        assertEquals("{ /* 业务注释 */  }", result)
        assertTrue(JsonParser.parseString(result).asJsonObject.isEmpty)
    }

    @Test
    fun trimsUnchangedPrefixAndSuffixFromReplacement() {
        val original = "{\"A\": {}}"
        val replacement = "{\"A\": {},\n  \"B\": {}\n}"
        val edit = PipelineJsonTextEdits.replacementEdit(TextRange(10, 10 + original.length), original, replacement)
        assertEquals(TextRange(18, 18), edit.range)
        assertEquals(",\n  \"B\": {}\n", edit.replacement)
        val wholeDocument = "0123456789$original // 原始尾部"
        assertEquals("0123456789$replacement // 原始尾部", edit.applyTo(wholeDocument))
    }

    @Test
    fun proposalAppliesMultipleEditsAgainstBaselineInDescendingOrder() {
        val original = "{\"A\": \"old\", \"B\": \"unchanged\", \"C\": \"old\"}"
        val first = original.indexOf("\"old\"")
        val last = original.lastIndexOf("\"old\"")
        val proposed = PipelineJsonTextEdits.applyEdits(original, listOf(
            PipelineTextEdit(TextRange(first, first + 5), "\"long new value\""),
            PipelineTextEdit(TextRange(last, last + 5), "\"new\""),
        ))
        assertEquals("{\"A\": \"long new value\", \"B\": \"unchanged\", \"C\": \"new\"}", proposed)
    }

    @Test
    fun snapshotRejectsChangedVersionEvenWhenTextHasBeenRestored() {
        val snapshot = PipelineEditSnapshot(12, "{\"A\": {}}")
        assertTrue(snapshot.matches(12, "{\"A\": {}}"))
        assertFalse(snapshot.matches(13, "{\"A\": {}}"))
        assertFalse(snapshot.matches(12, "{\"B\": {}}"))
    }
}
