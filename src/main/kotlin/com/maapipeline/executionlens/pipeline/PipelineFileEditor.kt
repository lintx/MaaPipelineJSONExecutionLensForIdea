package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

internal class PipelineFileEditor(private val project: Project, private val file: VirtualFile) :
    UserDataHolderBase(), FileEditor, Disposable {
    private val component = JPanel(BorderLayout()).apply {
        add(JBLabel("正在读取项目任务与 Pipeline…"), BorderLayout.CENTER)
    }
    private val workspace = PipelineWorkspaceState.getInstance(project)
    private val source = PipelineWorkspaceSource(project)
    private var flow: PipelineFlowPanel? = null
    private var disposed = false

    init {
        Disposer.register(this, source)
        ReadAction.nonBlocking<VirtualFile?> {
            workspace.requestedFile
                ?: VirtualFileManager.getInstance().findFileByUrl(workspace.data.lastSourceUrl)
                    ?.takeIf { it.isValid && PipelineFileDetector.isPipelineFile(it) }
                ?: PipelineProjectFiles.pipelineFiles(project).firstOrNull()
        }.expireWith(this).finishOnUiThread(ModalityState.any()) { seed ->
            if (disposed) return@finishOnUiThread
            component.removeAll()
            if (seed == null) {
                component.add(JBLabel("未找到 Pipeline。可在 Pipeline JSON 中右键选择“从此处查看流程”。"))
            } else {
                val panel = PipelineFlowPanel(project, seed, source.editorFor(seed).editor, source, workspace)
                flow = panel
                Disposer.register(this, panel)
                component.add(panel, BorderLayout.CENTER)
                acceptSourceRequest()
            }
            component.revalidate()
            component.repaint()
        }.submit(AppExecutorUtil.getAppExecutorService())
    }

    internal fun acceptSourceRequest() {
        val panel = flow ?: return
        workspace.requestedFile?.let { panel.openFromSource(it, workspace.requestedOffset) }
    }

    override fun getComponent(): JComponent = component
    override fun getPreferredFocusedComponent(): JComponent = component
    override fun getName(): String = "Pipeline 流程"
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !disposed
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile(): VirtualFile = file
    override fun dispose() { disposed = true }
}
