package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.codeInsight.daemon.impl.IntentionsUI
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

internal class PipelineWorkspaceSource(private val project: Project) : JPanel(BorderLayout()), Disposable, UiDataProvider {
    private val editors = linkedMapOf<VirtualFile, TextEditor>()
    private val pathLabel = JBLabel("选择流程节点查看源码").apply { border = JBUI.Borders.empty(4, 8) }
    private val body = JPanel(BorderLayout())
    var currentFile: VirtualFile? = null
        private set
    var onCaret: (VirtualFile, Int) -> Unit = { _, _ -> }
    var navigating = false
        private set
    private var generation = 0L
    private var disposed = false
    private var pinnedFile: VirtualFile? = null
    private var active = false
    private var workspaceActive = true
    private var selectedEditor: TextEditor? = null
    private val intentionBulbs = linkedMapOf<TextEditor, Boolean>()

    init { add(pathLabel, BorderLayout.NORTH); add(body, BorderLayout.CENTER) }

    override fun uiDataSnapshot(sink: DataSink) {
        val file = currentFile ?: return
        val hosted = editors[file] ?: return
        // 工作区标签是虚拟文件，撤销等原生动作必须使用当前真实 JSON 的 TextEditor。
        sink[CommonDataKeys.PROJECT] = project
        sink[CommonDataKeys.EDITOR] = hosted.editor
        sink[CommonDataKeys.VIRTUAL_FILE] = file
        sink[PlatformDataKeys.FILE_EDITOR] = hosted
    }

    fun editorFor(file: VirtualFile): TextEditor = editors.getOrPut(file) {
        if (pinnedFile == null) pinnedFile = file
        // 原生编辑器初始化还会设置高亮器，平台要求写意图读锁，不能嵌套在普通读锁内。
        val textEditor = WriteIntentReadAction.compute<TextEditor> {
            TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        }
        intentionBulbs[textEditor] = textEditor.editor.settings.isShowIntentionBulb
        textEditor.editor.settings.isShowIntentionBulb = false
        textEditor.editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (!navigating && currentFile == file) onCaret(file, event.caret.offset)
            }
        })
        textEditor
    }

    fun setActive(value: Boolean) {
        active = value
        updateActiveEditor()
    }

    fun setWorkspaceActive(value: Boolean) {
        workspaceActive = value
        updateActiveEditor()
    }

    private fun updateActiveEditor() {
        val next = editors[currentFile].takeIf { active && workspaceActive && !disposed }
        if (selectedEditor !== next) {
            selectedEditor?.deselectNotify()
            selectedEditor = next
            next?.selectNotify()
        }
        editors.values.forEach { hosted ->
            hosted.editor.settings.isShowIntentionBulb = hosted === next && intentionBulbs[hosted] == true
            // 灯泡位于窗口的浮层；仅隐藏 Swing 页签不会清除已经显示的原生提示。
            if (hosted !== next) IntentionsUI.getInstance(project).hideForEditor(hosted.editor)
        }
    }

    fun showFile(file: VirtualFile, range: TextRange, focus: Boolean) {
        if (disposed || !file.isValid) return
        val request = ++generation
        val textEditor = editorFor(file)
        currentFile = file
        pathLabel.text = file.path.removePrefix(project.basePath.orEmpty()).trimStart('/')
        pathLabel.toolTipText = file.path
        if (textEditor.component.parent != body) {
            body.removeAll()
            body.add(textEditor.component, BorderLayout.CENTER)
            body.revalidate()
            body.repaint()
        }
        updateActiveEditor()
        navigating = true
        try {
            textEditor.editor.caretModel.moveToOffset(range.startOffset.coerceIn(0, textEditor.editor.document.textLength))
        } finally { navigating = false }
        while (editors.size > 8) {
            val unused = editors.keys.firstOrNull { it != pinnedFile && it != file } ?: break
            editors.remove(unused)?.let {
                intentionBulbs.remove(it)
                TextEditorProvider.getInstance().disposeEditor(it)
            }
        }
        ApplicationManager.getApplication().invokeLater({
            if (disposed || request != generation || textEditor.editor.isDisposed) return@invokeLater
            navigating = true
            try {
                textEditor.editor.caretModel.moveToOffset(range.startOffset.coerceIn(0, textEditor.editor.document.textLength))
                textEditor.editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                if (focus && active && workspaceActive) textEditor.editor.contentComponent.requestFocusInWindow()
            } finally { navigating = false }
        }, ModalityState.any())
    }

    override fun dispose() {
        disposed = true
        updateActiveEditor()
        generation++
        editors.values.forEach { TextEditorProvider.getInstance().disposeEditor(it) }
        editors.clear()
        intentionBulbs.clear()
        body.removeAll()
    }
}
