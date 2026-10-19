package com.maapipeline.executionlens

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.maapipeline.executionlens.pipeline.PipelineWorkspaceState

internal class MaaPipelineStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(MaaPipelineStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        logger.info("MaaPipeline JSON Execution Lens loaded for project: ${project.name}")
        val workspace = PipelineWorkspaceState.getInstance(project)
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(ProjectManager.TOPIC,
            object : ProjectManagerListener {
                override fun projectClosing(closingProject: Project) {
                    if (closingProject == project) workspace.closing = true
                }
            })
        project.messageBus.connect(project).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file == workspace.file && !workspace.closing && !project.isDisposed) workspace.data.reopen = false
                }
            })
        if (workspace.data.reopen) ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) workspace.open()
        }
    }
}
