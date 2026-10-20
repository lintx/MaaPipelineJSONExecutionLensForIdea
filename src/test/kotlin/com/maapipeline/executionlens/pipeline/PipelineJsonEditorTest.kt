package com.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PipelineJsonEditorTest : BasePlatformTestCase() {
    fun testNativeJsonDocumentFormatsCommentsAndAlignsOnlyItsReplacement() {
        val editor = PipelineJsonEditor(project, "{\n// 保留注释\n\"target\":[1,2,3,4],\"duration\":200\n}")
        assertTrue(PsiDocumentManager.getInstance(project).getPsiFile(editor.field.document) is JsonFile)
        editor.format()
        assertTrue(editor.value.contains("// 保留注释"))
        WriteCommandAction.runWriteCommandAction(project, "编辑测试片段", null, Runnable {
            val document = editor.field.document
            val start = document.text.indexOf("200")
            document.replaceString(start, start + 3, "300")
        })
        val source = "{\n    \"A\": {\n        \"action\": {},\n        // 周围注释\n        \"next\": []\n    }\n}"
        val start = source.indexOf("{}")
        val replacement = PipelineJsonTextEdits.alignValue(source, start, editor.value)
        val result = source.replaceRange(start, start + 2, replacement)
        assertTrue(result.contains("\n        },\n        // 周围注释"))
        assertTrue(result.contains("300"))
        assertNotNull(PipelineVisualModel.parse(result))
    }
}
