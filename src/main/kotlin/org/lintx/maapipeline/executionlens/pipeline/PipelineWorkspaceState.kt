package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

class PipelineReadingState {
    var rootNode: String = ""
    var selectedNode: String = ""
    var collapsed: MutableList<String> = mutableListOf()
    var expandedNodes: MutableList<String> = mutableListOf()
    var expandedBranches: MutableList<String> = mutableListOf()
    var selections: MutableMap<String, MutableList<String>> = linkedMapOf()
    var inputs: MutableMap<String, String> = linkedMapOf()
    var rightTab: Int = 0
    var splitProportion: Float = 0.68f
    var rowKey: String = ""
    var rowOffset: Int = 0
    var sourceUrl: String = ""
    var sourceOffset: Int = 0
    var applyOverrides: Boolean = true
    var nodeBudget: Int = 200
    var environmentId: String = "Win32"
    var temporaryOverrides: String = "{}"
}

class PipelineWorkspaceStorage {
    var lastContext: String = ""
    var lastSourceUrl: String = ""
    var recentTasks: MutableList<String> = mutableListOf()
    var sessions: MutableMap<String, PipelineReadingState> = linkedMapOf()
    var reopen: Boolean = false
}

@Service(Service.Level.PROJECT)
@State(name = "MaaPipelineWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class PipelineWorkspaceState(private val project: Project) : PersistentStateComponent<PipelineWorkspaceStorage> {
    private var storage = PipelineWorkspaceStorage()
    private val emptyReading = PipelineReadingState()
    val data: PipelineWorkspaceStorage get() = storage
    val file: VirtualFile = VirtualFileManager.getInstance().getFileSystem(PipelineWorkspaceFileSystem.PROTOCOL)
        .findFileByPath("/${project.locationHash}/Pipeline 流程")!!
    var requestedFile: VirtualFile? = null
        private set
    var requestedOffset: Int = 0
        private set
    var closing: Boolean = false

    override fun getState(): PipelineWorkspaceStorage = storage
    override fun loadState(state: PipelineWorkspaceStorage) { storage = state }

    fun open(source: VirtualFile? = null, offset: Int = 0) {
        requestedFile = source
        requestedOffset = offset
        storage.reopen = true
        FileEditorManager.getInstance(project).openFile(file, true)
            .filterIsInstance<PipelineFileEditor>().firstOrNull()?.acceptSourceRequest()
    }

    fun clearSourceRequest() { requestedFile = null }
    fun reading(context: String): PipelineReadingState = if (context.isEmpty()) emptyReading else
        storage.sessions.getOrPut(context) { PipelineReadingState() }

    fun rememberTask(id: String) {
        storage.recentTasks.remove(id)
        storage.recentTasks.add(0, id)
        while (storage.recentTasks.size > 20) storage.recentTasks.removeAt(storage.recentTasks.lastIndex)
    }

    companion object {
        fun getInstance(project: Project): PipelineWorkspaceState = project.getService(PipelineWorkspaceState::class.java)
    }
}
