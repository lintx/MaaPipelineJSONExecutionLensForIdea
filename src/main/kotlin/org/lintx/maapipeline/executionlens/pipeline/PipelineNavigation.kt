package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.platform.ide.navigation.NavigationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
internal class PipelineNavigation(private val project: Project, private val scope: CoroutineScope) {
    fun open(file: VirtualFile, offset: Int = 0, requestFocus: Boolean = true, onOpened: () -> Unit = {}) {
        if (file is PipelineWorkspaceFile) {
            scope.launch(Dispatchers.EDT) {
                if (!project.isDisposed && file.isValid && FileEditorManager.getInstance(project).openFile(file, requestFocus).isNotEmpty()) {
                    onOpened()
                }
            }
            return
        }
        val navigation = NavigationService.getInstance(project)
        scope.launch {
            val request = readAction {
                file.takeIf { it.isValid }?.let { NavigationRequest.sourceNavigationRequest(project, it, offset) }
            } ?: return@launch
            // 空上下文避免源码跳转被详情区域的内嵌编辑器接管。
            navigation.navigate(request, NavigationOptions.defaultOptions().requestFocus(requestFocus), DataContext.EMPTY_CONTEXT)
            withContext(Dispatchers.EDT) { if (!project.isDisposed) onOpened() }
        }
    }

    companion object {
        fun getInstance(project: Project): PipelineNavigation = project.getService(PipelineNavigation::class.java)
    }
}
