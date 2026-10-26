package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.codeInsight.daemon.impl.IntentionsUI
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.ui.Messages
import com.google.gson.JsonPrimitive
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

internal class PipelineWorkspaceSource(private val project: Project) : JPanel(BorderLayout()), Disposable, UiDataProvider {
    private val editors = linkedMapOf<VirtualFile, TextEditor>()
    private val pathLabel = JBLabel("选择流程节点查看源码").apply { border = JBUI.Borders.empty(4, 8) }
    private val sourceTabs = javax.swing.JTabbedPane().apply {
        name = "pipeline-resource-sources"
    }
    private var nodeSourceKey = ""
    private var shownNodeKey = ""
    private var nodeSourceFile: VirtualFile? = null
    private var resourceSources = emptyList<SourceTab>()
    private var updatingSources = false
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

    init {
        sourceTabs.addChangeListener {
            if (!updatingSources) resourceSources.getOrNull(sourceTabs.selectedIndex)?.let { selected ->
                if (selected.file != null) showFile(selected.file, selected.range, false)
                else {
                    selected.create?.invoke()
                    selectSource(currentFile)
                }
            }
        }
        add(JPanel(BorderLayout()).apply {
            add(sourceTabs, BorderLayout.NORTH)
            add(pathLabel, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    fun clearNodeSources() { nodeSourceKey = "" }

    fun showNode(node: PipelineNodeLocation, overlays: List<PipelineOverrideLocation>, environmentId: String = "", navigate: Boolean = true) {
        val base = node.sourceFile ?: return
        val resolvedSources = ReadAction.compute<List<PipelineProjectFiles.ResourceSource>, RuntimeException> {
            val manifest = PipelineProjectFiles.manifest(project, base)
            val layers = manifest?.let { PipelineProjectFiles.resourceLayers(PipelineProjectFiles.text(it), environmentId) }.orEmpty()
            PipelineProjectFiles.resourceLayerFiles(manifest?.parent, base, layers)
        }
        val layerSources = resolvedSources.map { resource ->
            val pipelinePrefix = "${resource.manifestDirectory.path.trimEnd('/', '\\')}/${resource.layer.path.trimStart('.', '/', '\\').replace('\\', '/')}/pipeline/"
            val patch = overlays.lastOrNull { it.file.path.replace('\\', '/').startsWith(pipelinePrefix.replace('\\', '/')) }
            resource.copy(file = patch?.file ?: resource.file)
        }
        val nodeKey = "${base.url}#${node.name}"
        val preservePosition = shownNodeKey == nodeKey
        shownNodeKey = nodeKey
        val key = "${base.url}#${node.name}:$environmentId:" + layerSources.joinToString { "${it.file?.url}:${it.file?.modificationStamp}" } +
            overlays.joinToString { "${it.file.url}:${it.version}" }
        if (nodeSourceKey != key) {
            nodeSourceKey = key
            val sources = layerSources.map { source ->
                val patch = overlays.lastOrNull { it.file == source.file }
                SourceTab(source.layer.label, source.file, patch?.range ?: if (source.file == base) node.nameRange else TextRange(0, 0), source)
            }.ifEmpty {
                listOf(SourceTab("基础定义", base, node.nameRange, null))
            }
            val extraSources = overlays.filter { overlay -> sources.none { it.file == overlay.file } }.map {
                SourceTab(it.contextPath.removePrefix("资源 · "), it.file, it.range, null)
            }
            val allSources = sources + extraSources
            nodeSourceFile = allSources.firstOrNull { it.file == nodeSourceFile }?.file ?: base
            updatingSources = true
            try {
                sourceTabs.removeAll()
                resourceSources = allSources.map { source ->
                    source.copy(create = source.source?.takeIf { source.file == null }?.let { resource ->
                        { createMissingSource(node, resource, environmentId) }
                    })
                }
                resourceSources.forEach { source ->
                    sourceTabs.addTab(source.label + if (source.file == null) " · 创建" else "", null)
                    sourceTabs.setToolTipTextAt(sourceTabs.tabCount - 1, source.file?.path
                        ?: "创建 ${source.source?.targetRelativePath}，仅包含当前节点的空覆盖对象")
                }
                sourceTabs.selectedIndex = resourceSources.indexOfFirst { it.file == nodeSourceFile }
            } finally { updatingSources = false }
            sourceTabs.revalidate()
            sourceTabs.repaint()
        }
        val selectedLayer = layerSources.firstOrNull { it.file == nodeSourceFile }
        val overlay = overlays.lastOrNull { it.file == nodeSourceFile }
        // 内容同步只刷新资源入口；显式选中节点或切换资源时才移动源码光标。
        if ((!navigate && currentFile != null) || preservePosition && nodeSourceFile == currentFile) return
        showFile(nodeSourceFile ?: base, overlay?.range ?: selectedLayer?.let { if (it.file == base) node.nameRange else TextRange(0, 0) } ?: node.nameRange, false)
    }

    private data class SourceTab(
        val label: String,
        val file: VirtualFile?,
        val range: TextRange,
        val source: PipelineProjectFiles.ResourceSource?,
        val create: (() -> Unit)? = null,
    )

    private fun selectSource(file: VirtualFile?) {
        updatingSources = true
        try {
            sourceTabs.selectedIndex = resourceSources.indexOfFirst { it.file != null && it.file == file }
        } finally { updatingSources = false }
    }

    private fun createMissingSource(node: PipelineNodeLocation, source: PipelineProjectFiles.ResourceSource, environmentId: String) {
        if (Messages.showYesNoDialog(
            project,
            "未找到资源覆盖文件：${source.targetRelativePath}\n是否创建仅包含节点“${node.name}”的空覆盖片段？",
            "创建控制器覆盖文件",
            Messages.getQuestionIcon(),
        ) != Messages.YES) return
        var created: VirtualFile? = null
        val targetSegments = source.targetRelativePath.replace('\\', '/').split('/').filter(String::isNotBlank)
        if (targetSegments.any { it == ".." || it.contains(':') } || source.targetRelativePath.startsWith('/')) {
            Messages.showWarningDialog(project, "覆盖文件路径不在当前项目目录内，已取消创建。", "无法创建控制器覆盖")
            return
        }
        runCatching {
            WriteCommandAction.runWriteCommandAction(project, "创建 Pipeline 控制器覆盖", "MaaPipeline JSON Execution Lens", Runnable {
                val slash = source.targetRelativePath.lastIndexOf('/')
                val directoryPath = source.targetRelativePath.substring(0, slash.coerceAtLeast(0))
                var directory = source.manifestDirectory
                directoryPath.split('/').filter(String::isNotBlank).forEach { part ->
                    directory = directory.findChild(part) ?: directory.createChildDirectory(this, part)
                }
                val name = source.targetRelativePath.substringAfterLast('/')
                val existing = directory.findChild(name)
                val target = existing ?: directory.createChildData(this, name).also {
                    it.setBinaryContent("{\n  ${JsonPrimitive(node.name)}: {}\n}\n".toByteArray(Charsets.UTF_8))
                }
                created = target
            })
        }.onFailure {
            Messages.showErrorDialog(project, "创建覆盖文件失败：${it.message}", "无法创建控制器覆盖")
        }
        created?.let {
            nodeSourceFile = it
            nodeSourceKey = ""
            showNode(node, emptyList(), environmentId)
        }
    }

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
        // 资源选择属于当前文件状态，不能依赖按钮焦点或只在点击时更新。
        if (resourceSources.any { it.file == file }) nodeSourceFile = file
        selectSource(file)
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
        val targetOffset = range.startOffset.coerceIn(0, textEditor.editor.document.textLength)
        try {
            textEditor.editor.caretModel.moveToOffset(targetOffset)
        } finally { navigating = false }
        val requestedStamp = textEditor.editor.document.modificationStamp
        while (editors.size > 8) {
            val unused = editors.keys.firstOrNull { it != pinnedFile && it != file } ?: break
            editors.remove(unused)?.let {
                intentionBulbs.remove(it)
                TextEditorProvider.getInstance().disposeEditor(it)
            }
        }
        ApplicationManager.getApplication().invokeLater({
            if (disposed || request != generation || textEditor.editor.isDisposed) return@invokeLater
            // 延后滚动只服务本次导航，不能覆盖用户在下一轮事件中输入或移动的新位置。
            if (textEditor.editor.document.modificationStamp != requestedStamp ||
                textEditor.editor.caretModel.offset != targetOffset) return@invokeLater
            navigating = true
            try {
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
