package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

internal class PipelineFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is PipelineWorkspaceFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        PipelineFileEditor(project, file)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun disposeEditor(editor: FileEditor) {
        Disposer.dispose(editor)
    }

    override fun readState(sourceElement: Element, project: Project, file: VirtualFile): FileEditorState =
        FileEditorState.INSTANCE

    override fun writeState(state: FileEditorState, project: Project, targetElement: Element) = Unit

    override fun acceptRequiresReadAction(): Boolean = false

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    private companion object {
        const val EDITOR_TYPE_ID = "MaaPipelineJsonExecutionLens"
    }
}
