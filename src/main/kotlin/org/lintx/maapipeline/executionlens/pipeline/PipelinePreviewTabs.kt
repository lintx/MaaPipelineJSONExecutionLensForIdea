package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

internal data class PipelinePreviewRequest(
    val node: PipelineNodeLocation,
    val image: BufferedImage,
    val operations: List<PipelineOperation>,
    val resolve: (String) -> PipelineNodeLocation?,
    val templates: Map<Int, List<PipelineVisualAssets.TemplatePreview>>,
    val blocked: (VirtualFile) -> Boolean,
)

internal class PipelinePreviewFile(var request: PipelinePreviewRequest) :
    LightVirtualFile("识别预览 · ${request.node.name}", PlainTextFileType.INSTANCE, "") {
    var retained = false
}

@Service(Service.Level.PROJECT)
internal class PipelinePreviewTabs(private val project: Project, private val scope: CoroutineScope) {
    fun open(request: PipelinePreviewRequest) {
        scope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            val manager = FileEditorManagerEx.getInstanceEx(project)
            val previews = manager.openFiles.filterIsInstance<PipelinePreviewFile>()
            val existing = previews.lastOrNull {
                it.request.node.name == request.node.name &&
                    it.request.node.sourceFile?.url == request.node.sourceFile?.url &&
                    it.request.node.environmentId == request.node.environmentId &&
                    it.request.node.controllerType == request.node.controllerType
            }
            // 保留只阻止其他节点替换；重开同节点时不能重建编辑器而丢失 ROI 草稿。
            if (existing != null && manager.getAllEditors(existing).filterIsInstance<PipelinePreviewFileEditor>().any { it.isModified }) {
                manager.openFile(existing, true)
                return@launch
            }
            var reusable = existing ?: previews.lastOrNull { !it.retained && !manager.isPreviewPinned(it) }
            if (reusable != null && manager.getAllEditors(reusable).filterIsInstance<PipelinePreviewFileEditor>().any { it.isModified }) {
                when (Messages.showYesNoCancelDialog(project, "当前预览有尚未应用的 ROI 调整。", "切换识别预览",
                    "保留并新开", "放弃并切换", "取消", null)) {
                    Messages.YES -> { reusable.retained = true; reusable = null }
                    Messages.NO -> Unit
                    else -> return@launch
                }
            }
            val target = reusable ?: PipelinePreviewFile(request)
            target.request = request
            if (target.name != "识别预览 · ${request.node.name}") {
                com.intellij.openapi.application.WriteAction.run<RuntimeException> { target.rename(this, "识别预览 · ${request.node.name}") }
            }
            manager.openFile(target, true)
            manager.getAllEditors(target).filterIsInstance<PipelinePreviewFileEditor>().forEach { it.replace(request) }
            manager.updateFilePresentation(target)
        }
    }

    companion object {
        fun getInstance(project: Project): PipelinePreviewTabs = project.getService(PipelinePreviewTabs::class.java)
    }
}

internal class PipelinePreviewFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is PipelinePreviewFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = PipelinePreviewFileEditor(project, file as PipelinePreviewFile)
    override fun getEditorTypeId(): String = "MaaPipelineRecognitionPreview"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
    override fun acceptRequiresReadAction(): Boolean = false
    override fun disposeEditor(editor: FileEditor) { Disposer.dispose(editor) }
}

internal class PipelinePreviewFileEditor(private val project: Project, private val previewFile: PipelinePreviewFile) :
    UserDataHolderBase(), FileEditor, Disposable {
    private val content = JPanel(BorderLayout())
    private var request: PipelinePreviewRequest? = null
    internal lateinit var preview: PipelineImagePreview
        private set
    private var disposed = false
    internal val retain = object : DumbAwareToggleAction("保留此预览", "保留当前预览；之后打开其他预览时不替换此标签", AllIcons.General.Pin_tab) {
        override fun isSelected(event: AnActionEvent): Boolean = previewFile.retained
        override fun setSelected(event: AnActionEvent, state: Boolean) { previewFile.retained = state }
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    init { replace(previewFile.request) }

    fun replace(value: PipelinePreviewRequest) {
        if (request === value || disposed) return
        if (::preview.isInitialized) { content.remove(preview); Disposer.dispose(preview) }
        request = value
        preview = PipelineImagePreview(project, value.image, value.operations, value.resolve, value.templates,
            if (value.operations.isEmpty()) null else PipelineRoiEditSession(project, value.operations, value.blocked),
            node = value.node, retainAction = retain)
        Disposer.register(this, preview)
        content.add(preview, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    override fun getComponent(): JComponent = content
    override fun getPreferredFocusedComponent(): JComponent = preview.viewer.picture
    override fun getName(): String = "识别预览"
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = preview.hasChanges()
    override fun isValid(): Boolean = !disposed
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile(): VirtualFile = previewFile
    override fun dispose() { disposed = true }
}

// 用户手动固定原生标签时仍尊重该状态；插件的保留按钮不改变标签排列。
private fun FileEditorManagerEx.isPreviewPinned(file: VirtualFile): Boolean =
    windows.any { it.isFileOpen(file) && it.isFilePinned(file) }
