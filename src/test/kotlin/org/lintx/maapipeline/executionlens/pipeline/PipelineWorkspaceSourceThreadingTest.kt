package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.SwingUtilities

class PipelineWorkspaceSourceThreadingTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testDeferredNavigationDoesNotOverrideTypingOrCaretMovement() {
        lateinit var source: PipelineWorkspaceSource
        lateinit var file: com.intellij.openapi.vfs.VirtualFile
        var expected = -1
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            file = myFixture.addFileToProject("assets/resource/pipeline/Editing.json",
                """{"First":{"next":["Second"]},"Second":{}}""").virtualFile
            source = PipelineWorkspaceSource(project)
            Disposer.register(testRootDisposable, source)
            source.showFile(file, TextRange(2, 2), false)
            val editor = source.editorFor(file).editor
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, Runnable {
                val offset = editor.document.text.indexOf(']') + 1
                editor.document.insertString(offset, ",")
                expected = offset + 1
                editor.caretModel.moveToOffset(expected)
            })
        }
        SwingUtilities.invokeAndWait {
            assertEquals("延后导航不能覆盖逗号输入后的光标", expected, source.editorFor(file).editor.caretModel.offset)
            source.showFile(file, TextRange(2, 2), false)
            source.editorFor(file).editor.caretModel.moveToOffset(expected)
        }
        SwingUtilities.invokeAndWait {
            assertEquals("延后导航不能覆盖主动移动的光标", expected, source.editorFor(file).editor.caretModel.offset)
        }
    }

    fun testColdSourceEditorCanBeCreatedFromAnEdtCallbackWithoutReadAccess() {
        lateinit var source: PipelineWorkspaceSource
        lateinit var file: com.intellij.openapi.vfs.VirtualFile
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            file = myFixture.addFileToProject("assets/resource/pipeline/Cold.json", "{\n  \"Target\":{}\n}").virtualFile
            source = PipelineWorkspaceSource(project)
            Disposer.register(testRootDisposable, source)
        }
        SwingUtilities.invokeAndWait {
            assertFalse("此回归必须覆盖普通 EDT 回调，不能依赖测试外层的读锁", ApplicationManager.getApplication().isReadAccessAllowed)
            source.showFile(file, TextRange(5, 5), false)
            assertEquals(file, source.currentFile)
            assertEquals(5, source.editorFor(file).editor.caretModel.offset)
            assertEquals(1, source.editorFor(file).editor.caretModel.logicalPosition.line)
        }
    }
}
