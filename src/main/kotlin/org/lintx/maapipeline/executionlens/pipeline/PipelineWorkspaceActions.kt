package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

internal class OpenPipelineWorkspaceAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { PipelineWorkspaceState.getInstance(it).open() }
    }
}

internal class OpenPipelineFromSourceAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = event.project != null && file != null && PipelineFileDetector.isPipelineFile(file)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        PipelineWorkspaceState.getInstance(project).open(file, event.getData(CommonDataKeys.EDITOR)?.caretModel?.offset ?: 0)
    }
}
