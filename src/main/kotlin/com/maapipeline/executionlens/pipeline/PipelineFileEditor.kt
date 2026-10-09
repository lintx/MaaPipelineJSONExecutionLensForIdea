package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

internal class PipelineFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor, Disposable {
    private val sourceEditor: TextEditor =
        TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
    private val sourceContainer = JPanel(BorderLayout()).apply {
        add(sourceEditor.component, BorderLayout.CENTER)
    }
    private val flowPanel = PipelineFlowPanel(project, file, sourceEditor.editor) {
        showSourceForNavigation()
    }
    private lateinit var views: PipelineEditorViews

    init {
        views = PipelineEditorViews(sourceContainer, flowPanel)
        Disposer.register(this, flowPanel)
    }

    private fun showSourceForNavigation() {
        views.showSourceForNavigation()
    }

    override fun getComponent(): JComponent = views

    override fun getPreferredFocusedComponent(): JComponent? = sourceEditor.preferredFocusedComponent

    override fun getName(): String = "MaaPipeline JSON Execution Lens"

    override fun setState(state: FileEditorState) {
        sourceEditor.setState(state)
    }

    override fun isModified(): Boolean = sourceEditor.isModified

    override fun isValid(): Boolean = sourceEditor.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        sourceEditor.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        sourceEditor.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = sourceEditor.currentLocation

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? = sourceEditor.backgroundHighlighter

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        TextEditorProvider.getInstance().disposeEditor(sourceEditor)
    }
}
