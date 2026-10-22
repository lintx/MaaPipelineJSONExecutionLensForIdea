package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PipelineJsonSupportTest : BasePlatformTestCase() {
    fun testComplianceOnlyRelaxesMaaFilesAndCompletionInsertsValidPropertiesAndValues() {
        val pipeline = myFixture.addFileToProject("assets/resource/pipeline/Main.json", "{\"A\":{ // comment\n \"action\":\"Click\",},}")
        val ordinary = myFixture.addFileToProject("ordinary.json", "{ // comment\n \"a\":1,}")
        val provider = PipelineJsonCompliance()
        assertTrue(provider.isCommentAllowed(PsiTreeUtil.findChildOfType(pipeline, PsiComment::class.java)!!))
        assertFalse(provider.isCommentAllowed(PsiTreeUtil.findChildOfType(ordinary, PsiComment::class.java)!!))
        assertTrue(provider.isTrailingCommaAllowed((pipeline as JsonFile).topLevelValue!!))
        assertFalse(provider.isTrailingCommaAllowed((ordinary as JsonFile).topLevelValue!!))
        val parsed = PipelineVisualModel.parse("{\"recognition\":\"OCR\",\"expected\":[\"// text,}\",],/* tail */}")!!
        assertEquals("OCR", parsed.get("recognition").asString)
        assertEquals(1, parsed.getAsJsonArray("expected").size())
        assertEquals("// text,}", parsed.getAsJsonArray("expected")[0].asString)
        myFixture.configureByText("Completion.json", """{"A":{"action":"Click","recog<caret>"}}""")
        myFixture.complete(CompletionType.BASIC)
        val lookup = myFixture.lookupElements?.firstOrNull { it.lookupString == "recognition" }
        assertNotNull(lookup)
        myFixture.lookup.currentItem = lookup
        myFixture.finishLookup('\n')
        val root = (myFixture.file as JsonFile).topLevelValue as JsonObject
        assertEquals("\"DirectHit\"", (root.findProperty("A")!!.value as JsonObject).findProperty("recognition")!!.value!!.text)
        myFixture.configureByText("Completion.json", """{"A":{"action":<caret>}}""")
        myFixture.complete(CompletionType.BASIC)
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "Click" }
        myFixture.finishLookup('\n')
        assertTrue(myFixture.file.text.contains("\"action\":\"Click\""))
    }

    fun testOffsetIsAppliedToCommentBaseAndDefaultBackgroundRemainsAvailable() {
        val text = """{"recognition":"OCR","roi":[/* @roi [10,20,30,40] */1,2,3,4],"roi_offset":[2,3,4,5]}"""
        val node = PipelineNodeLocation("A", com.intellij.openapi.util.TextRange.EMPTY_RANGE, com.intellij.openapi.util.TextRange.EMPTY_RANGE, rawJson = text)
        val region = PipelineVisualModel.region(PipelineVisualModel.operation(node))
        assertEquals(listOf(12, 23, 34, 45), region.coordinates)
        assertTrue(region.fromComment)
        val settings = PipelinePreviewSettings.getInstance()
        val saved = settings.state
        try {
            settings.loadState(PipelinePreviewDefaults())
            val source = myFixture.addFileToProject("A.json", "{}").virtualFile
            val image = settings.background(source)!!.second
            assertEquals(1280, image.width)
            assertEquals(720, image.height)
            assertEquals(0xFFFFFF, image.getRGB(0, 0) and 0xFFFFFF)
        } finally { settings.loadState(saved) }
    }
}
