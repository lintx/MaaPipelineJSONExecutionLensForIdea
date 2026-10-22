package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.GsonBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.json.psi.JsonFile
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ModalityState
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JToggleButton
import javax.swing.JTabbedPane
import javax.swing.DefaultListModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import java.awt.Component
import com.intellij.openapi.util.TextRange
import com.intellij.ui.OnePixelSplitter
import com.google.gson.JsonParser

internal class PipelineFlowPanel(
    private val project: Project,
    private val file: VirtualFile,
    private val editor: Editor,
    private val source: PipelineWorkspaceSource,
    private val workspace: PipelineWorkspaceState,
    private val workspaceFile: PipelineWorkspaceFile? = null,
) : JPanel(BorderLayout()), Disposable.Parent {
    private companion object {
        // 逻辑视图只负责帮助理解当前路径；对大型项目设置上限，避免一次创建过多 Swing 组件。
        private const val MAX_LOGIC_NODES = 200
    }

    private data class LogicOverrideNote(val condition: String, val fields: Map<String, String>, val active: Boolean)

    private val nodeListModel = DefaultListModel<String>()
    private val nodeList = JBList(nodeListModel)
    private val nodeSelector = JButton("选择节点…")
    private var allNodeOptions: List<PipelineNodeLocation> = emptyList()
    private val summaryLabel = JBLabel()
    private val entrySelector = JButton("搜索任务…")
    private val optionsButton = JButton("任务选项…")
    private val rightTabs = JTabbedPane().apply { name = "pipeline-detail-tabs" }
    private lateinit var contentSplit: OnePixelSplitter
    // 首次资源扫描就使用已保存的控制器，避免页签恢复后仍持有默认控制器的索引。
    private var contextKey = workspaceFile?.nodeContext ?: workspace.data.lastContext
    private var standaloneRoot: PipelineNodeLocation? = null
    private var effectiveNodes: Map<String, PipelineNodeLocation> = emptyMap()
    @Volatile private var effectiveGeneration = 0L
    private var selectedOverrides: List<PipelineOverrideLocation> = emptyList()
    private var contextLoading = false
    private var restoring = false
    private var pendingSource: Pair<VirtualFile, Int>? = null
    private var sourceRequestGeneration = 0L
    private val expandedBranches = linkedSetOf<String>()
    private val playbackBudgetBranches = linkedSetOf<String>()
    private var playbackRevealPath = emptyList<String>()
    private var nodeBudget = MAX_LOGIC_NODES
    private var restoreScroll = false
    private var logicBuildQueue: ArrayDeque<() -> Unit>? = null
    private var renderedNodeCount = 0
    private val logicRowByNode = linkedMapOf<String, String>()
    private val logicOccurrences = linkedMapOf<String, MutableList<String>>()
    private var locatedOccurrence: String? = null
    private val activeLogicRows = mutableMapOf<String, String>()
    private val readingHistory = ArrayDeque<Pair<String, Int>>()
    private val session: PipelineReadingState get() = workspace.reading(contextKey)
    private val entryStatusLabel = JBLabel("未检测到任务入口")
    private val traceButton = JButton("加载运行日志")
    private val clearTraceButton = JButton("清除日志")
    private val overrideToggle = JCheckBox("应用任务覆盖", true)
    private val logicPanel = PipelineRowsPanel()
    private val flowSearch = PipelineFlowSearch(logicPanel)
    private val detailsPanel = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(16)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            (visibleRect.height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
    private val detailsScroll = JBScrollPane(detailsPanel).apply {
        border = JBUI.Borders.empty()
        viewportBorder = null
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private var renderedDetailKey: String? = null
    private var detailsScrollGeneration = 0L
    private var savedDetailsPosition = 0
    private var currentModel = PipelineDocumentModel(file, -1L, emptyList(), hasSyntaxErrors = true)
    private var sourceNodes: Map<VirtualFile?, List<PipelineNodeLocation>> = emptyMap()
    private var readingGraph: PipelineReadingGraph? = null
    private var detailsDirty = true
    private var renderedContext: String? = null
    private val refreshAlarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this)
    private var currentTrace: PipelineExecutionTrace? = null
    private var traceSourceVersions: Map<VirtualFile, Long> = emptyMap()
    private var traceSourceVersionState: Boolean? = null
    private var traceLoadGeneration = 0L
    private var traceReplayExpanded = false
    private val playbackCursors = mutableMapOf<String, PipelinePlaybackCursor>()
    private val playbackToolbar = PipelinePlaybackToolbar(::playbackPositionChanged, ::openTraceFile)
    private var simulation: PipelineSimulation? = null
    private var simulationCursor: PipelinePlaybackCursor? = null
    private var completedSubtasks = emptySet<String>()
    private val expandedSubtasks = mutableSetOf<String>()
    private val environmentSelector = JComboBox(PipelineEnvironment.available(null).toTypedArray())
    private var traceDetailsPanel: JPanel? = null
    private var overrideDetailsHeader: JComponent? = null
    private var disposed = false
    private var refreshing = false
    private var syncingEntrySelector = false
    private var syncingNodeSelector = false
    private var selectingFromSource = false
    private var inspectedLogicNode: PipelineNodeLocation? = null
    private var selectedEntry: PipelineEntryLocation? = null
    @Volatile private var refreshGeneration = 0L
    private val pendingChangedFiles = linkedSetOf<VirtualFile>()
    private var fullRefreshRequired = true
    private val collapsedLogicNodes = linkedSetOf<String>()
    private val explicitlyExpandedNodes = linkedSetOf<String>()
    private var overrideNotesModel: PipelineDocumentModel? = null
    private var overrideNotesEntry: PipelineEntryLocation? = null
    private var overrideNotesActive: List<PipelineOverrideLocation>? = null
    private var overrideNotesEnabled = false
    private var overrideNotes: Map<String, List<LogicOverrideNote>> = emptyMap()
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            refreshFromDocument(event.document)
        }
    }

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(PipelinePreviewSettings.CHANGED, Runnable {
            ApplicationManager.getApplication().invokeLater { if (!disposed) renderSelectedNode() }
        })
        border = JBUI.Borders.empty(8)
        nodeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        nodeList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !refreshing) {
                inspectedLogicNode = null
                syncNodeSelector()
                renderSelectedNode()
                if (!refreshing && !selectingFromSource) saveSession()
            }
        }
        nodeSelector.name = "pipeline-node-selector"
        nodeSelector.horizontalAlignment = javax.swing.SwingConstants.LEFT
        nodeSelector.toolTipText = "搜索节点 ID 或描述；选择后仅切换详情"
        nodeSelector.addActionListener { showNodeChooser() }
        com.intellij.ui.ComboboxSpeedSearch(environmentSelector)
        entrySelector.toolTipText = "按任务 ID、名称、注释、UI 文本搜索；最近任务优先"
        entryStatusLabel.foreground = UIUtil.getContextHelpForeground()
        overrideToggle.apply {
            isOpaque = false
            isFocusable = false
            toolTipText = "应用匹配日志的启动覆盖；没有日志参数时使用任务默认或手动选项"
            addActionListener {
                updateSummary()
                if (!restoring) rebuildEffectiveContext()
            }
        }
        entrySelector.addActionListener {
            val dialog = PipelineTaskChooserDialog(project, currentModel.pipelineEntries, workspace.data.recentTasks)
            if (dialog.showAndGet()) dialog.selectedEntry?.let(::activateTask)
        }
        optionsButton.addActionListener { showTaskOptions() }
        nodeList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val nodeName = value as? String
                text = nodeDisplayText(nodeName)
                return this
            }
        }
        detailsPanel.layout = VerticalLayout(0)
        detailsPanel.border = JBUI.Borders.empty(10, 12)
        summaryLabel.foreground = UIUtil.getContextHelpForeground()
        summaryLabel.border = JBUI.Borders.empty(0, 8, 5, 8)
        nodeList.fixedCellHeight = JBUI.scale(26)
        nodeList.border = JBUI.Borders.empty(3, 0)
        nodeList.toolTipText = "标记说明：○ 未从入口到达　↺ 循环边　⇉ 共享目标　⚓ 动态 Anchor"
        val logic = JBScrollPane(logicPanel).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        traceButton.apply {
            isFocusable = false
            toolTipText = "选择 MaaFramework debug/maafw.log，只读显示实际执行轨迹"
            addActionListener { loadTrace() }
        }
        clearTraceButton.apply {
            isFocusable = false
            isEnabled = false
            toolTipText = "移除当前面板叠加的运行轨迹，不修改日志文件"
            addActionListener {
                traceLoadGeneration++
                currentTrace = null
                playbackCursors.clear()
                playbackToolbar.bind(null, false)
                traceSourceVersions = emptyMap()
                traceSourceVersionState = null
                traceButton.isEnabled = true
                nodeList.repaint()
                updateTaskControls()
                rebuildEffectiveContext()
            }
        }
        environmentSelector.name = "pipeline-environment-selector"
        environmentSelector.toolTipText = "选择控制器资源；流程与详情使用合并后的属性，不连接真实设备"
        environmentSelector.addActionListener {
            if (!restoring) {
                session.environmentId = (environmentSelector.selectedItem as? PipelineEnvironment)?.id.orEmpty()
                stopSimulation()
                refreshFromDocument(editor.document, fullRefresh = true)
            }
        }
        val summaryBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            val navigationPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }
            val entryPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
                add(JBLabel("任务："), BorderLayout.WEST)
                entrySelector.minimumSize = Dimension(JBUI.scale(180), JBUI.scale(28))
                entrySelector.preferredSize = Dimension(JBUI.scale(360), JBUI.scale(28))
                add(entrySelector, BorderLayout.CENTER)
                add(optionsButton, BorderLayout.EAST)
            }
            val nodePanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
                add(JBLabel("查看节点："), BorderLayout.WEST)
                nodeSelector.minimumSize = Dimension(JBUI.scale(180), JBUI.scale(28))
                nodeSelector.preferredSize = Dimension(JBUI.scale(360), JBUI.scale(28))
                add(nodeSelector, BorderLayout.CENTER)
                add(pipelineIconButton(com.intellij.icons.AllIcons.General.Locate,
                    "在左侧定位此节点；再次点击定位下一处引用", ::locateDetailInFlow), BorderLayout.EAST)
            }
            navigationPanel.add(entryPanel)
            rightTabs.addTab("节点详情", JPanel(BorderLayout()).apply {
                add(nodePanel, BorderLayout.NORTH)
                add(detailsScroll, BorderLayout.CENTER)
            })
            rightTabs.addTab("原始 JSON", source)
            rightTabs.addChangeListener {
                source.setActive(rightTabs.selectedIndex == 1)
                if (!restoring && rightTabs.selectedIndex == 1) selectedDetail()?.let {
                    savedDetailsPosition = detailsScroll.viewport.viewPosition.y
                    showNodeSource(it)
                }
                if (!restoring && rightTabs.selectedIndex == 0) {
                    if (detailsDirty) renderSelectedNode()
                    positionDetails(savedDetailsPosition)
                }
                if (!restoring) saveSession()
            }
            val statusPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel(VerticalLayout(0)).apply {
                    isOpaque = false
                    add(JPanel(PipelineBaselineLayout()).apply {
                        isOpaque = false
                        add(overrideToggle, BorderLayout.WEST)
                        summaryLabel.border = JBUI.Borders.empty(0, 8)
                        add(summaryLabel, BorderLayout.CENTER)
                    })
                    add(entryStatusLabel)
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                    isOpaque = false
                    add(traceButton)
                    add(clearTraceButton)
                }, BorderLayout.EAST)
            }
            add(navigationPanel, BorderLayout.NORTH)
            add(statusPanel, BorderLayout.CENTER)
        }
        contentSplit = OnePixelSplitter(false, 0.68f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(logic, BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply {
                    add(createExecutionToolbar(), BorderLayout.NORTH)
                    add(JPanel(VerticalLayout(0)).also { addLogicToolbar(it) }, BorderLayout.SOUTH)
                    add(flowSearch, BorderLayout.CENTER)
                }, BorderLayout.NORTH)
                add(playbackToolbar, BorderLayout.SOUTH)
                flowSearch.install(this, this@PipelineFlowPanel)
            }
            secondComponent = rightTabs
            border = JBUI.Borders.emptyTop(2)
        }
        add(summaryBar, BorderLayout.NORTH)
        add(contentSplit, BorderLayout.CENTER)
        logic.viewport.addChangeListener { saveScrollPosition() }
        contentSplit.addPropertyChangeListener("proportion") { if (!restoring) saveSession() }
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val changed = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (changed in currentModel.indexedVersions || changed == file ||
                    changed in currentModel.pipelineEntries.map { it.file } ||
                    changed.path.contains("/locales/")) refreshFromDocument(event.document)
            }
        }, this)
        source.onCaret = { sourceFile, offset ->
            if (!restoring) {
                if (!restoreScroll && contextKey.isNotEmpty()) {
                    session.sourceUrl = sourceFile.url
                    session.sourceOffset = offset
                }
                val selected = selectedDetail()
                if (selected?.sourceFile != sourceFile || !selected.propertyRange.containsOffset(offset)) {
                    val nodes = sourceNodes[sourceFile].orEmpty()
                    val index = nodes.binarySearchBy(offset) { it.propertyRange.startOffset }
                        .let { if (it >= 0) it else -it - 2 }
                    nodes.getOrNull(index)?.takeIf { it.propertyRange.containsOffset(offset) }
                        ?.let { inspectNode(it, syncSource = false) }
                }
            }
        }
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val relevant = events.filter { event ->
                    if (event is com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent && event.isFromSave) return@filter false
                    event.file in currentModel.indexedVersions || event.path.startsWith(project.basePath.orEmpty()) &&
                        (event.path.contains("/pipeline/") || event.path.contains("/tasks/") ||
                            event.path.contains("/locales/") || event.path.endsWith("/interface.json"))
                }
                if (relevant.isNotEmpty()) ApplicationManager.getApplication().invokeLater {
                    if (!disposed) {
                        pendingChangedFiles += relevant.mapNotNull { it.file }
                        refreshFromDocument(editor.document,
                            relevant.any { it !is com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent })
                    }
                }
            }
        })
        refreshFromDocument(editor.document)
    }

    private fun selectNodeAtOffset(offset: Int) {
        val index = currentModel.nodes.indexOfFirst { node ->
            node.propertyRange.startOffset <= offset && offset <= node.propertyRange.endOffset
        }
        if (index < 0 || index == nodeList.selectedIndex) return
        selectingFromSource = true
        try {
            nodeList.selectedIndex = index
        } finally {
            selectingFromSource = false
        }
    }

    private fun currentRoot(): PipelineNodeLocation? = selectedEntry?.let(::entryRootNode) ?: standaloneRoot

    private fun selectedDetail(): PipelineNodeLocation? = inspectedLogicNode ?: currentRoot()

    private fun inspectNode(location: PipelineNodeLocation, syncSource: Boolean = true) {
        if (selectedDetail() == location) {
            if (syncSource && rightTabs.selectedIndex == 1) source.showFile(location.sourceFile ?: file, location.nameRange, false)
            return
        }
        inspectedLogicNode = location
        logicOccurrences[logicNodeKey(location.name, location.sourceFile ?: file)]?.let(logicPanel::activateNode)
        refreshing = true
        try {
            nodeList.selectedIndex = currentModel.nodes.indexOfFirst {
                it.name == location.name && it.sourceFile == location.sourceFile
            }
        } finally { refreshing = false }
        inspectedLogicNode = location
        renderSelectedNode()
        if (syncSource && rightTabs.selectedIndex == 1) showNodeSource(location)
        saveSession()
    }

    private fun inspectOverrides(location: PipelineNodeLocation) {
        inspectNode(location, syncSource = false)
        rightTabs.selectedIndex = 0
        val header = overrideDetailsHeader ?: return
        ApplicationManager.getApplication().invokeLater {
            if (disposed || header !== overrideDetailsHeader || selectedDetail() != location) return@invokeLater
            detailsPanel.validate()
            detailsPanel.scrollRectToVisible(Rectangle(0, header.y, 1, detailsPanel.visibleRect.height.coerceAtLeast(header.height)))
        }
    }

    private fun showNodeSource(location: PipelineNodeLocation) {
        source.showNode(location, currentModel.resourceOverrides.filter { it.targetName == location.name }, currentModel.environmentId)
    }

    private fun updateTaskControls() {
        entrySelector.text = selectedEntry?.let { "${it.label} · ${it.taskId}" } ?: "搜索任务…"
        entrySelector.isEnabled = currentModel.pipelineEntries.isNotEmpty()
        entrySelector.toolTipText = selectedEntry?.description?.takeIf(String::isNotBlank)
            ?: "搜索任务 ID、名称、注释、UI 文本或入口"
        val logOverrides = traceOverrides() != null && overrideToggle.isSelected
        optionsButton.isEnabled = selectedEntry?.optionNames?.isNotEmpty() == true && !logOverrides
        optionsButton.toolTipText = if (logOverrides) "当前采用该次日志的启动覆盖；移除日志后可调整任务选项" else "未手动调整时使用 task 声明的默认选项"
        overrideToggle.isEnabled = selectedEntry != null || traceOverrides() != null || session.temporaryOverrides != "{}"
    }

    internal fun activateTask(entry: PipelineEntryLocation) {
        sourceRequestGeneration++
        saveSession()
        selectedEntry = entry
        standaloneRoot = null
        contextKey = entry.stableId
        if (workspaceFile?.nodeContext == null) workspace.data.lastContext = contextKey
        workspace.rememberTask(contextKey)
        inspectedLogicNode = currentModel.projectNodes.firstOrNull {
            logicNodeKey(it.name, it.sourceFile ?: file) == session.selectedNode
        }
        restoreSession()
        entryStatusLabel.text = entry.issue.orEmpty()
        updateTaskControls()
        rebuildEffectiveContext()
    }

    internal fun openFromSource(sourceFile: VirtualFile, offset: Int) {
        if (currentModel.version < 0) { pendingSource = sourceFile to offset; return }
        workspace.clearSourceRequest()
        val nodes = currentModel.projectNodes.filter { it.sourceFile == sourceFile }
        val node = nodes.firstOrNull { offset in it.propertyRange.startOffset..it.propertyRange.endOffset }
            ?: nodes.firstOrNull()
        if (node == null) {
            entryStatusLabel.text = "当前资源域未索引该文件：${sourceFile.name}"
            return
        }
        if (currentRoot() != null && (staticPathTo(node) != null || currentRoot() == node)) {
            inspectNode(node)
            return
        }
        val generation = ++sourceRequestGeneration
        val snapshot = currentModel
        val readings = workspace.data.sessions.mapValues { (_, reading) -> reading.selections.mapValues { it.value.toList() } to reading.inputs.toMap() }
        entryStatusLabel.text = "正在查找关联任务…"
        ReadAction.nonBlocking<List<PipelineEntryLocation>> {
            snapshot.pipelineEntries.filter { entry ->
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                if (entry.issue != null) return@filter false
                val root = snapshot.projectNodes.singleOrNull { it.sourceFile == entry.targetFile && it.name == entry.entryName }
                    ?: return@filter false
                val (selections, inputs) = readings[entry.stableId] ?: (emptyMap<String, List<String>>() to emptyMap())
                val overrides = PipelineTaskContext.activeOverrides(entry, snapshot.pipelineOverrides, selections)
                val effective = PipelineDocumentIndexer.effectiveNodes(project, snapshot, overrides, inputs, entry.taskOptions)
                val graph = PipelineReadingGraph(snapshot.projectNodes) { effective[PipelineReadingGraph.key(it)] ?: it }
                graph.path(root, node) != null
            }
        }.expireWith(this).finishOnUiThread(ModalityState.any()) { candidates ->
            if (disposed || generation != sourceRequestGeneration || currentModel !== snapshot) return@finishOnUiThread
            openSourceContext(node, sourceFile, candidates)
        }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun openSourceContext(node: PipelineNodeLocation, sourceFile: VirtualFile, candidates: List<PipelineEntryLocation>) {
        entryStatusLabel.text = selectedEntry?.issue.orEmpty()
        if (candidates.size == 1) {
            activateTask(candidates.single())
        } else if (candidates.size > 1) {
            val dialog = PipelineTaskChooserDialog(project, candidates, workspace.data.recentTasks)
            if (!dialog.showAndGet()) return
            dialog.selectedEntry?.let(::activateTask)
        } else {
            saveSession()
            selectedEntry = null
            standaloneRoot = node
            inspectedLogicNode = node
            contextKey = "node:${logicNodeKey(node.name, sourceFile)}"
            if (workspaceFile?.nodeContext == null) workspace.data.lastContext = contextKey
            restoreSession()
            session.rootNode = logicNodeKey(node.name, sourceFile)
            entryStatusLabel.text = "从节点查看 · 未应用任务覆盖"
            updateTaskControls()
            rebuildEffectiveContext()
        }
        inspectNode(node)
    }

    private fun saveSession() {
        if (disposed || restoring || restoreScroll || contextLoading || contextKey.isEmpty() || currentModel.version < 0) return
        session.rootNode = currentRoot()?.let { logicNodeKey(it.name, it.sourceFile ?: file) }.orEmpty()
        session.selectedNode = selectedDetail()?.let { logicNodeKey(it.name, it.sourceFile ?: file) }.orEmpty()
        session.collapsed = collapsedLogicNodes.toMutableList()
        session.expandedNodes = explicitlyExpandedNodes.toMutableList()
        session.expandedBranches = expandedBranches.toMutableList()
        session.nodeBudget = nodeBudget
        session.rightTab = rightTabs.selectedIndex
        session.splitProportion = contentSplit.proportion
        session.applyOverrides = overrideToggle.isSelected
        saveScrollPosition()
        source.currentFile?.let { current ->
            session.sourceUrl = current.url
            session.sourceOffset = source.editorFor(current).editor.caretModel.offset
        }
        if (workspaceFile?.nodeContext == null) workspace.data.lastSourceUrl = currentRoot()?.sourceFile?.url ?: file.url
    }

    private fun saveScrollPosition() {
        if (restoring || restoreScroll || contextLoading || contextKey.isEmpty() || currentModel.version < 0) return
        logicPanel.scrollAnchor()?.let { (key, offset) -> session.rowKey = key; session.rowOffset = offset }
    }

    private fun restoreSession() {
        restoring = true
        try {
            environmentSelector.selectedItem = (0 until environmentSelector.itemCount).map { environmentSelector.getItemAt(it) }
                .firstOrNull { it.id == session.environmentId } ?: environmentSelector.getItemAt(0)
            collapsedLogicNodes.clear()
            collapsedLogicNodes += session.collapsed
            explicitlyExpandedNodes.clear()
            explicitlyExpandedNodes += session.expandedNodes
            expandedBranches.clear()
            expandedBranches += session.expandedBranches
            playbackBudgetBranches.clear()
            nodeBudget = session.nodeBudget.coerceAtLeast(MAX_LOGIC_NODES)
            rightTabs.selectedIndex = session.rightTab.coerceIn(0, 1)
            source.setActive(rightTabs.selectedIndex == 1)
            contentSplit.proportion = session.splitProportion.coerceIn(0.15f, 0.9f)
            overrideToggle.isSelected = session.applyOverrides
            restoreScroll = true
        } finally { restoring = false }
    }

    private fun currentOverrides(): List<PipelineOverrideLocation> = selectedOverrides

    private fun createExecutionToolbar(): JComponent {
        val actions = DefaultActionGroup()
        fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, operation: () -> Unit) {
            actions.add(object : DumbAwareAction(text, text, icon) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(event: AnActionEvent) { event.presentation.isEnabled = enabled() }
                override fun actionPerformed(event: AnActionEvent) = operation()
            })
        }
        action("开始模拟", com.intellij.icons.AllIcons.Actions.Execute, { !contextLoading && currentRoot()?.let(::effectiveNode)?.enabled == true }) {
            startSimulation()
        }
        action("选择下一步", com.intellij.icons.AllIcons.Actions.Forward, { simulationCursor?.let { simulation?.frames?.getOrNull(it.eventIndex)?.let { frame -> !frame.ended && !frame.failed } } == true }) { chooseSimulationStep(false) }
        action("模拟失败 / 超时", com.intellij.icons.AllIcons.General.Warning, { simulationCursor?.let { simulation?.frames?.getOrNull(it.eventIndex)?.ended == false } == true }) { chooseSimulationStep(true) }
        action("结束模拟", com.intellij.icons.AllIcons.Actions.Suspend, { simulation != null }) { stopSimulation() }
        actions.addSeparator()
        action("预览设置…", com.intellij.icons.AllIcons.General.Settings) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, PipelinePreviewSettingsConfigurable::class.java)
            renderSelectedNode()
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("MaaPipeline.Execution", actions, true)
        toolbar.targetComponent = this
        object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) { chooseSimulationStep(false) }
        }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CustomShortcutSet(
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK)), logicPanel, this)
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(environmentSelector, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
        }
    }

    private fun startSimulation() {
        val root = currentRoot()?.let(::effectiveNode)?.takeIf { it.enabled } ?: return
        if (contextLoading) return
        val environment = environmentSelector.selectedItem as? PipelineEnvironment ?: return
        simulation = PipelineSimulation(currentModel.projectNodes.ifEmpty { currentModel.nodes }.map(::effectiveNode), environment,
            { PipelinePropertyCatalog.resourceDefaults(it, currentModel) }).also { it.start(root) }
        simulationCursor = PipelinePlaybackCursor(simulation!!)
        syncPlaybackSession()
        playbackPositionChanged(true)
    }

    private fun stopSimulation() {
        if (simulation == null) return
        simulation = null
        simulationCursor = null
        val restoreSubtasks = completedSubtasks.isNotEmpty()
        completedSubtasks = emptySet()
        expandedSubtasks.clear()
        syncPlaybackSession()
        if (restoreSubtasks) renderLogicView(currentRoot())
        refreshPlaybackComponents(logicPanel)
        logicPanel.revalidate()
        renderSelectedNode()
        updatePlaybackMarker()
    }

    private fun chooseSimulationStep(error: Boolean) {
        val execution = simulation ?: return
        val cursor = simulationCursor ?: return
        val choices = if (error) execution.errors(cursor.eventIndex) else execution.choices(cursor.eventIndex)
        if (choices.isEmpty()) {
            if (execution.finish(cursor.eventIndex, error)) {
                playbackToolbar.seek(execution.frames.lastIndex)
                playbackToolbar.refresh()
            } else if (error && execution.requiresParentOutcome(cursor.eventIndex)) {
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(PipelineSimulationParentOutcome.entries)
                    .setTitle("子任务失败后，选择父动作的模拟结果")
                    .setItemChosenCallback { outcome ->
                        if (execution.finish(cursor.eventIndex, true, outcome)) {
                            playbackToolbar.seek(execution.frames.lastIndex)
                            playbackToolbar.refresh()
                        }
                    }.createPopup().showInCenterOf(logicPanel)
            }
            return
        }
        val available = choices.filter { it.node != null }
        if (available.size == 1) {
            advanceSimulation(available.single())
            return
        }
        val current = execution.frames.getOrNull(cursor.eventIndex)?.node ?: return
        val key = logicNodeKey(current.name, current.sourceFile ?: file)
        collapsedLogicNodes -= key
        logicOccurrences[key].orEmpty().forEach { collapsedLogicNodes -= it.removeSuffix(":header") }
        playbackRevealPath = currentRoot()?.let { root -> currentReadingGraph().path(root, current) }
            .orEmpty().map { (_, node) -> logicNodeKey(node.name, node.sourceFile ?: file) }
        try { renderLogicView(currentRoot()) } finally { playbackRevealPath = emptyList() }
        logicPanel.followPlaybackRow(logicRowByNode[key])
        renderSelectedNode()
    }

    private fun simulationChoices(): List<PipelineSimulationChoice> {
        val execution = simulation ?: return emptyList()
        val cursor = simulationCursor ?: return emptyList()
        return (execution.choices(cursor.eventIndex) + execution.errors(cursor.eventIndex)).filter { it.node != null }
    }

    private fun advanceSimulation(choice: PipelineSimulationChoice) {
        val execution = simulation ?: return
        val cursor = simulationCursor ?: return
        if (execution.advance(cursor.eventIndex, choice)) {
            playbackToolbar.seek(execution.frames.lastIndex)
            playbackToolbar.refresh()
        }
    }

    private fun simulationRunButton(onRun: () -> Unit): JButton = JButton(com.intellij.icons.AllIcons.Actions.Execute).apply {
        name = "pipeline-simulation-run"
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        border = JBUI.Borders.empty(2)
        margin = JBUI.emptyInsets()
        preferredSize = JBUI.size(20, 20)
        addActionListener { onRun() }
    }

    private fun simulationButton(choice: PipelineSimulationChoice): JButton = simulationRunButton { advanceSimulation(choice) }.apply {
        toolTipText = "模拟运行 · $choice"
    }

    private fun withSimulationRun(heading: JComponent, reference: PipelineReference?, parentName: String?, fieldName: String): JComponent {
        if (reference == null || parentName == null) return heading
        var candidate: PipelineSimulationChoice? = null
        val run = simulationRunButton { candidate?.let(::advanceSimulation) }
        val runContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(run, BorderLayout.NORTH)
        }
        val update = Runnable {
            val frame = simulationCursor?.let { simulation?.frames?.getOrNull(it.eventIndex) }
            candidate = if (frame?.node?.name == parentName) simulationChoices().firstOrNull {
                it.kind == fieldName && it.reference == reference
            } else null
            run.isVisible = candidate != null
            runContainer.isVisible = candidate != null
            run.toolTipText = candidate?.let { "模拟运行 · $it" }
        }
        update.run()
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            putClientProperty("pipeline.playback.refresh", update)
            add(runContainer, BorderLayout.WEST)
            add(heading, BorderLayout.CENTER)
        }
    }

    private fun editTemporaryOverrides() {
        val node = selectedDetail()?.let(::effectiveNode) ?: return
        val all = PipelineVisualModel.parse(session.temporaryOverrides) ?: com.google.gson.JsonObject()
        val initial = all.get(node.name)?.takeIf { it.isJsonObject }?.asJsonObject ?: com.google.gson.JsonObject()
        val properties = (PipelineVisualModel.parse(node.rawJson) ?: com.google.gson.JsonObject()).deepCopy()
        PipelinePropertyCatalog.properties(node, PipelinePropertyCatalog.resourceDefaults(node, currentModel)).forEach { property ->
            if (!properties.has(property.name)) property.value?.let { properties.add(property.name, it.deepCopy()) }
        }
        if (!properties.has("recognition")) properties.addProperty("recognition", "DirectHit")
        if (!properties.has("action")) properties.addProperty("action", "DoNothing")
        val dialog = PipelineTemporaryOverrideDialog(project, node.name, initial.toString(), properties)
        if (!dialog.showAndGet()) return
        val patch = PipelineVisualModel.parse(dialog.value) ?: return
        if (patch.size() == 0) all.remove(node.name) else all.add(node.name, patch)
        session.temporaryOverrides = all.toString()
        overrideToggle.isSelected = true
        updateTaskControls()
        rebuildEffectiveContext()
    }

    private fun chooseOverrideSource() {
        val node = selectedDetail() ?: return
        val base = PipelineOverrideLocation(node.sourceFile ?: file, node.name, node.propertyRange, emptyList(),
            valueRange = node.nameRange, contextPath = "基础定义")
        val candidates = (listOf(base) + currentModel.resourceOverrides + currentOverrides() + selectedEntry?.let {
            PipelineTaskContext.possibleOverrides(it, currentModel.pipelineOverrides)
        }.orEmpty()).filter { it.targetName == node.name }.distinct()
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(candidates)
            .setTitle("属性来源 · ${node.name}")
            .setNamerForFiltering { "${overrideSourceName(it)} ${it.fields.joinToString { field -> field.name }}" }
            .setRenderer(object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: javax.swing.JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                    super.getListCellRendererComponent(list, value, index, selected, focus)
                    val patch = value as? PipelineOverrideLocation
                    text = patch?.let {
                        val active = it === base || it in currentModel.resourceOverrides || overrideToggle.isSelected && it in currentOverrides()
                        (if (active) "已应用 · " else "未启用 · ") + overrideSourceName(it) +
                            it.fields.takeIf { fields -> fields.isNotEmpty() }?.joinToString(prefix = " · ") { field -> field.name }.orEmpty()
                    }.orEmpty()
                    toolTipText = patch?.file?.path
                    return this
                }
            }).setItemChosenCallback {
                if (it.contextPath == "临时覆盖") editTemporaryOverrides() else navigateToFileRange(it.file, it.valueRange)
            }.createPopup().showInCenterOf(this)
    }

    private fun overrideSourceName(patch: PipelineOverrideLocation): String = when {
        patch.contextPath == "基础定义" -> "基础定义 · ${patch.file.name}"
        patch.contextPath == "临时覆盖" -> "临时属性 · 最后应用"
        patch in currentModel.resourceOverrides -> patch.contextPath
        patch.logTaskId != null -> "日志启动配置 · task_id=${patch.logTaskId}"
        else -> selectedEntry?.let { PipelineTaskContext.overrideCondition(it, patch) } ?: patch.file.name
    }

    private fun traceOverrides(): PipelineTraceTaskOverrides? =
        currentTrace?.overridesForEntry(selectedEntry?.entryName ?: currentRoot()?.name)

    private fun overrideSourceLabel(): String {
        if (!overrideToggle.isSelected) return "原始节点定义"
        if (session.temporaryOverrides.isNotBlank() && PipelineVisualModel.parse(session.temporaryOverrides)?.size() != 0)
            return "任务 / 日志选项 + 临时覆盖"
        traceOverrides()?.let { return "日志启动覆盖 · task_id=${it.taskId}" }
        val entry = selectedEntry ?: return "原始节点定义"
        val defaults = entry.taskOptions.all { option ->
            (session.selections[option.name] ?: option.defaults) == option.defaults && option.inputs.all { input ->
                (session.inputs["${option.path}/${input.name}"] ?: input.defaultValue) == input.defaultValue
            }
        }
        val label = if (defaults) "任务默认选项" else "手动任务选项"
        return if (currentTrace == null) label else "$label（日志未记录匹配的启动覆盖）"
    }

    private fun rebuildEffectiveContext() {
        stopSimulation()
        refreshEnvironments()
        playbackToolbar.pause()
        syncPlaybackSession()
        saveSession()
        val generation = ++effectiveGeneration
        val snapshot = currentModel
        val trace = currentTrace
        val logged = traceOverrides().takeIf { overrideToggle.isSelected }
        selectedEntry?.let { entry ->
            val valid = PipelineTaskContext.validSelections(entry.taskOptions, session.selections)
            if (valid != session.selections) {
                session.selections = valid.mapValues { it.value.toMutableList() }.toMutableMap()
                entryStatusLabel.text = "部分已保存选项不存在，已恢复声明的默认值"
            }
        }
        selectedOverrides = if (logged != null) emptyList() else selectedEntry?.let {
            PipelineTaskContext.activeOverrides(it, currentModel.pipelineOverrides, session.selections)
        }.orEmpty()
        val overrides = if (overrideToggle.isSelected) selectedOverrides else emptyList()
        effectiveNodes = emptyMap()
        readingGraph = null
        val temporary = session.temporaryOverrides.takeIf { overrideToggle.isSelected } ?: "{}"
        contextLoading = overrides.isNotEmpty() || logged != null || PipelineVisualModel.parse(temporary)?.size()?.let { it > 0 } == true
        updateTaskControls()
        updateSummary()
        if (!contextLoading) {
            effectiveNodes = emptyMap()
            syncNodeSelector()
            renderSelectedNode(updateFlow = true)
            restoreReadingPosition()
            return
        }
        renderSelectedNode(updateFlow = true)
        val inputs = session.inputs.toMap()
        val taskOptions = selectedEntry?.taskOptions.orEmpty()
        ReadAction.nonBlocking<Pair<List<PipelineOverrideLocation>, Map<String, PipelineNodeLocation>>> {
            val base = if (logged != null && trace != null) PipelineDocumentIndexer.logOverrides(project, trace, logged) else overrides
            val applied = base + PipelineTemporaryOverrides.parse(project, temporary)
            applied to PipelineDocumentIndexer.effectiveNodes(project, snapshot, applied, inputs, taskOptions)
        }.expireWith(this).finishOnUiThread(ModalityState.any()) { (applied, nodes) ->
            if (disposed || generation != effectiveGeneration || currentModel !== snapshot) return@finishOnUiThread
            contextLoading = false
            selectedOverrides = applied
            effectiveNodes = nodes
            syncNodeSelector()
            readingGraph = null
            renderSelectedNode(updateFlow = true)
            restoreReadingPosition()
        }.expireWhen { generation != effectiveGeneration }
            .coalesceBy(this, "pipeline-effective")
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun refreshEnvironments() {
        val generation = effectiveGeneration
        ReadAction.nonBlocking<List<PipelineEnvironment>> {
            val manifest = PipelineProjectFiles.manifest(project, file)
            PipelineEnvironment.available(manifest?.let { FileDocumentManager.getInstance().getDocument(it)?.text })
        }.expireWith(this).finishOnUiThread(ModalityState.nonModal()) { values ->
            if (disposed || generation + 1 != effectiveGeneration) return@finishOnUiThread
            restoring = true
            try {
                environmentSelector.model = javax.swing.DefaultComboBoxModel(values.toTypedArray())
                environmentSelector.selectedItem = values.firstOrNull { it.id == session.environmentId } ?: values.first()
                session.environmentId = (environmentSelector.selectedItem as PipelineEnvironment).id
            } finally { restoring = false }
        }.coalesceBy(this, "pipeline-environments").submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun restoreReadingPosition() {
        val generation = effectiveGeneration
        val restoredContext = contextKey
        val sourceUrl = session.sourceUrl
        val sourceOffset = session.sourceOffset
        val shouldRestoreSource = restoreScroll
        if (restoreScroll) {
            logicPanel.restoreAnchor(session.rowKey, session.rowOffset)
        }
        if (rightTabs.selectedIndex == 1 && shouldRestoreSource) {
            val savedSource = if (shouldRestoreSource) com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                .findFileByUrl(sourceUrl)?.takeIf { it.isValid } else null
            if (savedSource != null) source.showFile(savedSource, TextRange(sourceOffset, sourceOffset), false)
            else selectedDetail()?.let(::showNodeSource)
        }
        if (shouldRestoreSource) ApplicationManager.getApplication().invokeLater {
            if (!disposed && effectiveGeneration == generation && contextKey == restoredContext) {
                restoreScroll = false
                saveSession()
            }
        } else saveSession()
    }

    private fun showTaskOptions() {
        val entry = selectedEntry ?: return
        val dialog = PipelineTaskOptionsDialog(project, entry, session.selections, session.inputs)
        if (!dialog.showAndGet()) return
        session.selections = dialog.selections.mapValues { it.value.toMutableList() }.toMutableMap()
        session.inputs = dialog.inputs.toMutableMap()
        rebuildEffectiveContext()
    }

    private fun nodeDisplayText(nodeName: String?): String {
        if (nodeName == null) return "（未选择节点）"
        val state = traceNodeState(nodeName)
        val node = currentModel.nodes.firstOrNull { it.name == nodeName }
        val references = node?.let { it.next + it.onError + it.embeddedReferences }.orEmpty()
        val reachability = if (nodeName !in reachableNodeNames().orEmpty()) "○ " else ""
        val relationMarkers = buildString {
            if (references.any { it.isCycle }) append("↺ ")
            if (references.any { it.isSharedTarget }) append("⇉ ")
            if (references.any { it.isAnchor }) append("⚓ ")
        }
        return reachability + relationMarkers + when (state?.status) {
            PipelineTraceStatus.SUCCEEDED -> "✓ $nodeName"
            PipelineTraceStatus.FAILED -> "✗ $nodeName"
            PipelineTraceStatus.STARTING -> "… $nodeName"
            null -> nodeName
        }
    }

    private fun syncNodeSelector() {
        val node = selectedDetail()
        nodeSelector.text = node?.let { it.name + effectiveNode(it).description?.let { desc -> " · $desc" }.orEmpty() } ?: "选择节点…"
    }

    private fun showNodeChooser() { nodeChooser().showUnderneathOf(nodeSelector) }

    private fun nodeChooser(): com.intellij.openapi.ui.popup.JBPopup =
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(allNodeOptions)
            .setTitle("查看节点").setFilterAlwaysVisible(true).setRequestFocus(true)
            .setNamerForFiltering { "${it.name} ${effectiveNode(it).description.orEmpty()}" }
            .setRenderer(object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: javax.swing.JList<*>, value: Any?, index: Int,
                    selected: Boolean, focus: Boolean): Component {
                    super.getListCellRendererComponent(list, value, index, selected, focus)
                    val node = value as? PipelineNodeLocation
                    text = node?.let { it.name + effectiveNode(it).description?.let { desc -> " · $desc" }.orEmpty() }.orEmpty()
                    border = JBUI.Borders.empty(5, 10)
                    return this
                }
            }).setItemChosenCallback { inspectNode(it) }.createPopup()

    private fun refreshFromDocument(document: Document, fullRefresh: Boolean = false) {
        traceSourceVersionState = null
        FileDocumentManager.getInstance().getFile(document)?.let(pendingChangedFiles::add)
        fullRefreshRequired = fullRefreshRequired || fullRefresh
        val generation = ++refreshGeneration
        effectiveGeneration++
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ indexDocument(generation) }, 40)
    }

    private fun indexDocument(generation: Long) {
        if (disposed || generation != refreshGeneration) return
        val previous = currentModel.takeUnless { fullRefreshRequired || it.version < 0 }
        val changedFiles = pendingChangedFiles.toSet()
        val environmentId = session.environmentId
        ReadAction.nonBlocking<List<Document>> {
            val files = if (previous == null) PipelineProjectFiles.pipelineFiles(project, file, environmentId) +
                PipelineProjectFiles.taskFiles(project, file) + PipelineProjectFiles.translationFiles(project, file)
                else changedFiles.toList()
            files.mapNotNull { FileDocumentManager.getInstance().getCachedDocument(it) }.distinct()
        }.expireWith(this).finishOnUiThread(ModalityState.nonModal()) { documents ->
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && generation == refreshGeneration) {
                    indexCommittedDocuments(generation, previous, changedFiles, documents)
                }
            }, ModalityState.nonModal())
        }.expireWhen { generation != refreshGeneration }
            .coalesceBy(this, "pipeline-discovery")
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun indexCommittedDocuments(
        generation: Long,
        previous: PipelineDocumentModel?,
        changedFiles: Set<VirtualFile>,
        documents: List<Document>,
    ) {
        val psiDocuments = PsiDocumentManager.getInstance(project)
        // 只提交本次依赖文件，不等待整个项目其他编辑器的未提交文档。
        documents.forEach { if (!psiDocuments.isCommitted(it)) psiDocuments.commitDocument(it) }
        val document = editor.document
        val requestedStamp = document.modificationStamp
        val environmentId = session.environmentId
        val environments = PipelineEnvironment.available(
            PipelineProjectFiles.manifest(project, file)?.let(PipelineProjectFiles::text),
        )
        val selectedEnvironment = environmentId.takeIf { id -> environments.any { it.id == id } }
            ?: environments.first().id
        ReadAction.nonBlocking<PipelineDocumentModel> {
            PipelineDocumentIndexer.index(project, file, previous, changedFiles, selectedEnvironment)
        }.expireWith(this).finishOnUiThread(ModalityState.any()) { model ->
            if (
                disposed ||
                generation != refreshGeneration ||
                document.modificationStamp != requestedStamp ||
                model.version != document.modificationStamp
            ) {
                return@finishOnUiThread
            }
            saveSession()
            pendingChangedFiles.clear()
            fullRefreshRequired = false
            val initialSnapshot = currentModel.version < 0
            val previousEntryId = selectedEntry?.stableId ?: contextKey
            val previousDetail = selectedDetail()?.let { logicNodeKey(it.name, it.sourceFile ?: file) }
            refreshing = true
            currentModel = model
            session.environmentId = model.environmentId
            if (rightTabs.selectedIndex == 1) source.clearNodeSources()
            sourceNodes = model.projectNodes.groupBy { it.sourceFile }.mapValues { (_, nodes) ->
                nodes.sortedBy { it.propertyRange.startOffset }
            }
            readingGraph = null
            nodeListModel.removeAllElements()
            model.nodes.forEach { nodeListModel.addElement(it.name) }
            syncingNodeSelector = true
            try {
                allNodeOptions = model.projectNodes
                nodeSelector.isEnabled = model.projectNodes.isNotEmpty()
            } finally {
                syncingNodeSelector = false
            }
            selectedEntry = model.pipelineEntries.firstOrNull { it.stableId == previousEntryId }
            contextKey = selectedEntry?.stableId ?: contextKey
            standaloneRoot = model.projectNodes.firstOrNull { contextKey.startsWith("node:") &&
                logicNodeKey(it.name, it.sourceFile ?: file) == workspace.reading(contextKey).rootNode
            }
            inspectedLogicNode = model.projectNodes.firstOrNull {
                logicNodeKey(it.name, it.sourceFile ?: file) == (previousDetail ?: session.selectedNode)
            }
            if (initialSnapshot) restoreSession()
            entryStatusLabel.text = when {
                selectedEntry?.issue != null -> selectedEntry?.issue
                previousEntryId.isNotEmpty() && selectedEntry == null && standaloneRoot == null -> "上次任务或流程已不存在，请重新选择"
                else -> ""
            }
            updateTaskControls()
            syncNodeSelector()
            updateSummary()
            nodeList.repaint()
            nodeSelector.repaint()
            refreshing = false
            if (rightTabs.selectedIndex == 1) selectedDetail()?.let(::showNodeSource)
            val request = pendingSource
            if (request != null) {
                pendingSource = null
                openFromSource(request.first, request.second)
            } else rebuildEffectiveContext()
        }
            .expireWhen { generation != refreshGeneration }
            .coalesceBy(this, "pipeline-index")
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun renderSelectedNode(updateFlow: Boolean = false) {
        if (updateFlow && (!contextLoading || renderedContext != contextKey)) renderLogicView(currentRoot())
        syncNodeSelector()
        // 源码光标只改变选中节点；隐藏的详情在切回页签时才构建，流程不跟随光标重建。
        detailsDirty = true
        val locationKey = selectedDetail()?.let { logicNodeKey(it.name, it.sourceFile ?: file) }
        if (locationKey != renderedDetailKey) savedDetailsPosition = 0
        if (rightTabs.selectedIndex != 0) return
        detailsDirty = false
        val scrollPosition = if (locationKey == renderedDetailKey) detailsScroll.viewport.viewPosition.y else 0
        savedDetailsPosition = scrollPosition
        renderedDetailKey = locationKey
        detailsPanel.removeAll()
        traceDetailsPanel = null
        overrideDetailsHeader = null
        val selectedLocation = currentRoot()
        val location = inspectedLogicNode ?: selectedLocation
        // 中间流程保持任务入口或列表选中的根节点，右侧详情可以独立查看流程树中的任意目标。
        if (contextLoading) {
            detailsPanel.add(JBLabel("正在应用任务选项…"))
        } else if (location == null) {
            detailsPanel.add(JBLabel("选择任务或从源码节点打开流程"))
        } else {
            detailsPanel.add(PipelineWrappingLabel(location.name).apply {
                font = font.deriveFont(Font.BOLD)
                foreground = UIUtil.getLabelForeground()
            })
            detailsPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
                add(JButton("属性来源…").apply {
                    toolTipText = "查看此节点的基础定义、控制器资源及任务选项来源"
                    addActionListener { chooseOverrideSource() }
                })
                add(JButton("临时覆盖…").apply {
                    toolTipText = "仅在当前任务视图覆盖此节点的属性；不修改源文件"
                    addActionListener { editTemporaryOverrides() }
                })
                simulationChoices().filter { it.node?.name == location.name && it.node.sourceFile == location.sourceFile }
                    .forEach { add(simulationButton(it)) }
            })
            val effectiveLocation = effectiveNode(location)
            effectiveLocation.description?.let {
                detailsPanel.add(PipelineWrappingLabel(it).apply { toolTipText = PipelineVisualModel.fieldHelp("desc") })
            }
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            val staticPath = staticPathTo(location)
            if (staticPath == null && selectedEntry != null) {
                detailsPanel.add(PipelineWrappingLabel("路径分析：从当前任务入口未解析到此节点").apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            } else if (staticPath != null) {
                addPathAnalysis(staticPath)
            }
            addRelationshipOverview(location)
            val sourceFile = location.sourceFile ?: file
            addControlFlowExplanation(effectiveLocation, sourceFile, location)
            addAnchorDefinitions(location, sourceFile)
            addNodeDiagnostics(location)
            addTaskOverrideSummary(location)
            addReferences(
                "on_error",
                effectiveLocation.onError,
                effectiveLocation.onErrorField,
                "on_error",
                effectiveLocation.onErrorField?.sourceFile ?: sourceFile,
            )
            addReferences(
                "next",
                effectiveLocation.next,
                effectiveLocation.nextField,
                "next",
                effectiveLocation.nextField?.sourceFile ?: sourceFile,
            )
            addTraceSummary(location)
            addSimulationSummary(location)
            detailsPanel.add(JSeparator())
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            addReferences("嵌套节点引用", location.embeddedReferences, sourceFile = sourceFile)
        }
        detailsPanel.revalidate()
        detailsPanel.repaint()
        positionDetails(scrollPosition)
    }

    private fun positionDetails(position: Int) {
        val request = ++detailsScrollGeneration
        fun restore() {
            detailsScroll.viewport.viewPosition = java.awt.Point(0,
                position.coerceIn(0, (detailsPanel.height - detailsScroll.viewport.extentSize.height).coerceAtLeast(0)))
        }
        restore()
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && request == detailsScrollGeneration && rightTabs.selectedIndex == 0) {
                detailsScroll.viewport.doLayout()
                detailsPanel.doLayout()
                restore()
            }
        }, ModalityState.any())
    }

    private fun updateSummary() {
        val suffix = if (currentModel.hasSyntaxErrors) "（JSON 语法暂不可完整解析）" else ""
        summaryLabel.text = buildString {
            append("项目资源：${currentModel.projectNodes.size} 个节点 $suffix")
            if (selectedEntry != null || traceOverrides() != null) append("　覆盖：${overrideSourceLabel()}")
            currentTrace?.let { trace ->
                val events = trace.eventsForEntry(selectedEntry?.entryName)
                val executed = events.filter { it.kind == PipelineTraceEventKind.PIPELINE_NODE && it.status == PipelineTraceStatus.STARTING }
                    .mapNotNull { it.nodeName }.distinct().size
                append("　当前任务日志：${events.size} 个事件，已执行 $executed 个节点")
                append("　来源：${displayPath(trace.file)}")
                if (traceSourceChanged()) append("　⚠ 导入日志后源码已改变")
            }
        }
        clearTraceButton.isEnabled = currentTrace != null
    }

    private fun renderLogicView(location: PipelineNodeLocation?) {
        val rows = mutableListOf<PipelineRow>()
        if (contextLoading) {
            appendLogicNote(rows, "loading", 0, "正在应用覆盖参数…")
        } else if (location == null) {
            appendLogicNote(rows, "empty", 0, selectedEntry?.issue ?: "搜索并选择任务，或在 JSON 中右键“从此处查看流程”")
        } else {
            val entry = selectedEntry
            appendLogicRow(rows, "task", 0, revision = listOf(entry?.file, entry?.range, entry?.entryName)) { panel ->
                val label = if (entry == null) JBLabel("task（未指定入口）") else {
                    createNavigationLabel("task ${entry.entryName}") { navigateToFileRange(entry.file, entry.range) }
                }
                label.font = label.font.deriveFont(Font.BOLD)
                panel.add(label)
            }
            appendLogicNote(rows, "context", 0, if (entry == null) {
                "未指定任务入口 · 当前仅显示所选节点的静态控制流"
            } else {
                "当前任务：${displayPath(entry.file)} · ${overrideSourceLabel()}"
            })
            appendTraceReplay(rows)
            val root = location
            val rootKey = logicNodeKey(root.name, root.sourceFile ?: file)
            appendLogicNote(rows, "limit", 0, "从 ${root.name} 查看流程；共享和循环可在流程内定位，动态目标按候选查看。")
            logicRowByNode.clear()
            logicOccurrences.clear()
            activeLogicRows.clear()
            renderedNodeCount = 0
            val work = ArrayDeque<() -> Unit>()
            logicBuildQueue = work
            try {
                work.addLast { appendLogicNode(root, rows, 0, setOf(rootKey), linkedSetOf(rootKey),
                    revealIndex = if (playbackRevealPath.firstOrNull() == rootKey) 0 else -1) }
                while (work.isNotEmpty()) work.removeLast().invoke()
            } finally { logicBuildQueue = null }
        }
        logicPanel.setRows(rows)
        flowSearch.refresh()
        updatePlaybackMarker()
        renderedContext = contextKey
    }

    private fun traceSourceChanged(): Boolean = traceSourceVersionState ?: ReadAction.compute<Boolean, RuntimeException> {
        traceSourceVersions.any { (source, version) ->
            !source.isValid || (FileDocumentManager.getInstance().getDocument(source)?.modificationStamp ?: source.modificationStamp) != version
        }
    }.also { traceSourceVersionState = it }

    private fun appendTraceReplay(rows: MutableList<PipelineRow>) {
        val trace = currentTrace ?: return
        val entry = selectedEntry?.entryName
        val events = trace.eventsForEntry(entry)
        if (events.isEmpty()) {
            appendLogicNote(rows, "trace:empty", 0, "当前入口没有关联日志，保留静态流程；不会借用其它任务的结果。")
            return
        }
        val replay = trace.replayForEntry(entry)
        appendLogicRow(rows, "trace:header", 0, revision = listOf(trace, entry, traceReplayExpanded)) { panel ->
            val header = JPanel(BorderLayout()).apply { isOpaque = false }
            header.add(createFoldMarker(traceReplayExpanded, "本次运行日志回放") { expanded ->
                traceReplayExpanded = expanded
                renderLogicView(currentRoot())
            }, BorderLayout.WEST)
            header.add(JBLabel("本次运行 · ${replay.steps.size} 条观察 · ${replay.calls.size} 次 JumpBack 推断").apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.CENTER)
            panel.add(header)
        }
        if (traceSourceChanged()) {
            appendLogicNote(rows, "trace:changed", 0, "⚠ 导入日志后源码已改变，以下历史轨迹不代表当前源码的执行结果。")
        }
        val knownNodes = currentModel.projectNodes.ifEmpty { currentModel.nodes }.map { it.name }.toSet()
        val missing = events.filter { it.kind == PipelineTraceEventKind.PIPELINE_NODE }.mapNotNull { it.nodeName }
            .filterNot { it in knownNodes }.distinct()
        if (missing.isNotEmpty()) {
            appendLogicNote(rows, "trace:missing", 0, "⚠ 日志含当前索引未找到的节点：${missing.take(5).joinToString("、")}；请核对资源或源码版本。")
        }
        if (!traceReplayExpanded) return
        appendLogicNote(rows, "trace:version", 0, "日志未提供源码版本指纹；回放仅展示观察和明确标注的推断。点击行末可定位原始日志。")
        appendLogicRow(rows, "trace:recent", 0, revision = listOf(trace, entry)) { panel ->
            val update = Runnable {
                panel.removeAll()
                val cursor = playbackToolbar.session
                val source = cursor?.source as? PipelineLogPlaybackSource
                if (source != null) {
                    val first = (cursor.eventIndex - 7).coerceAtLeast(0)
                    for (index in first..cursor.eventIndex) {
                        val frame = source.frame(index) ?: continue
                        panel.add(PipelineWrappingLabel(frame.text))
                        frame.lineNumber?.let { line ->
                            panel.add(createNavigationLabel("查看原日志 · 第 $line 行") { openTraceFile(line) })
                        }
                    }
                }
                panel.revalidate()
                panel.repaint()
            }
            panel.putClientProperty("pipeline.playback.refresh", update)
            update.run()
        }
    }

    private fun appendLogicRow(
        rows: MutableList<PipelineRow>,
        key: String,
        depth: Int,
        revision: Any? = null,
        build: (JPanel) -> Unit,
    ) {
        rows += PipelineRow(key, revision?.let { listOf(contextKey, depth, currentModel.isGenerated, it) }, depth) {
            object : JPanel(VerticalLayout(0)) {
                override fun getMinimumSize() = Dimension(0, preferredSize.height)
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 16 + depth * 12, 2, 16)
                build(this)
            }
        }
    }

    private fun appendLogicNote(
        rows: MutableList<PipelineRow>, key: String, depth: Int, text: String,
        foreground: java.awt.Color = UIUtil.getContextHelpForeground(),
        bold: Boolean = false, tooltip: String? = null,
    ) {
        appendLogicRow(rows, key, depth, revision = listOf(text, foreground, bold, tooltip)) { panel ->
            panel.add((if (key.endsWith(":action")) PipelineStyledText(text) else PipelineWrappingLabel(text)).apply {
                this.foreground = foreground
                font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN)
                toolTipText = tooltip
                border = JBUI.Borders.empty(2, 8)
            })
        }
    }

    private fun entryRootNode(entry: PipelineEntryLocation): PipelineNodeLocation? {
        if (entry.issue != null) return null
        val targetFile = entry.targetFile ?: file
        return currentReadingGraph().node(targetFile, entry.entryName) ?: currentReadingGraph().uniqueNode(entry.entryName)
    }

    private fun addLogicToolbar(container: JPanel) {
        val actions = DefaultActionGroup()
        fun addAction(text: String, description: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, action: () -> Unit) {
            actions.add(object : DumbAwareAction(text, description, icon) {

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = enabled()
                }

                override fun actionPerformed(event: AnActionEvent) = action()
            })
        }
        addAction("全部展开", "展开当前流程树中的所有节点", com.intellij.icons.AllIcons.Actions.Expandall) {
            collapsedLogicNodes.clear()
            explicitlyExpandedNodes += currentModel.projectNodes.ifEmpty { currentModel.nodes }
                .map { logicNodeKey(it.name, it.sourceFile ?: file) }
            renderLogicView(currentRoot())
            saveSession()
        }
        addAction("返回上次浏览处", "撤回最近一次定位，恢复定位前的滚动位置", com.intellij.icons.AllIcons.Actions.Back, { readingHistory.isNotEmpty() }) {
            readingHistory.removeLastOrNull()?.let { (key, offset) -> logicPanel.restoreAnchor(key, offset) }
        }
        addAction("查找", "在当前流程中查找文字（使用 IDE 查找快捷键）", com.intellij.icons.AllIcons.Actions.Find) { flowSearch.open() }
        addAction("在流程中定位详情节点", "滚动到右侧详情节点；再次点击定位下一处引用", com.intellij.icons.AllIcons.General.Locate, { selectedDetail() != null }) { locateDetailInFlow() }
        addAction("在新标签浏览此节点流程", "保留当前任务与覆盖，在独立标签中以所选节点为起点", com.intellij.icons.AllIcons.Actions.Forward, { selectedDetail() != null }) {
            val node = selectedDetail() ?: return@addAction
            saveSession()
            workspace.openNode(node, session.environmentId)
        }
        addAction("在 IDE 打开源码", "在普通文件标签中打开选中节点的原始 JSON", com.intellij.icons.AllIcons.Actions.EditSource, { selectedDetail() != null }) {
            val node = selectedDetail() ?: return@addAction
            OpenFileDescriptor(project, node.sourceFile ?: file, node.nameRange.startOffset).navigate(true)
        }
        addAction("全部收起", "收起当前流程中的节点内容", com.intellij.icons.AllIcons.Actions.Collapseall) {
            val nodes = currentModel.projectNodes.ifEmpty { currentModel.nodes }
            collapsedLogicNodes += nodes.map { logicNodeKey(it.name, it.sourceFile ?: file) }
            explicitlyExpandedNodes.clear()
            renderLogicView(currentRoot())
            saveSession()
        }
        val actionToolbar = ActionManager.getInstance().createActionToolbar("MaaPipeline.Logic", actions, true)
        actionToolbar.targetComponent = this
        container.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(actionToolbar.component, BorderLayout.CENTER)
        })
    }

    private fun logicNodeKey(nodeName: String, sourceFile: VirtualFile = file): String =
        "${sourceFile.url}#$nodeName"

    private fun locateDetailInFlow() {
        val node = selectedDetail() ?: return
        val key = logicNodeKey(node.name, node.sourceFile ?: file)
        if (logicOccurrences[key].isNullOrEmpty()) {
            val path = currentRoot()?.let { currentReadingGraph().path(it, node) }.orEmpty()
            if (path.isEmpty()) { entryStatusLabel.text = "${node.name} 不在当前流程中"; return }
            playbackRevealPath = path.map { (_, value) -> logicNodeKey(value.name, value.sourceFile ?: file) }
            try { renderLogicView(currentRoot()) } finally { playbackRevealPath = emptyList() }
        }
        val occurrences = logicOccurrences[key].orEmpty()
        if (occurrences.isEmpty()) return
        val next = occurrences[(occurrences.indexOf(locatedOccurrence) + 1) % occurrences.size]
        logicPanel.scrollAnchor()?.let(readingHistory::addLast)
        locatedOccurrence = next
        logicPanel.activateRow(next)
        logicPanel.followPlaybackRow(next)
    }

    private fun appendLogicNode(
        location: PipelineNodeLocation,
        rows: MutableList<PipelineRow>,
        depth: Int,
        ancestors: Set<String>,
        expandedNodes: MutableSet<String>,
        incoming: LogicIncomingBranch? = null,
        instanceKey: String = logicNodeKey(location.name, location.sourceFile ?: file),
        anchorContext: PipelineAnchorContext = PipelineAnchorContext(),
        revealIndex: Int = -1,
    ) {
        val sourceFile = location.sourceFile ?: file
        val nodeKey = logicNodeKey(location.name, sourceFile)
        val effective = effectiveNode(location)
        if (revealIndex >= 0) {
            collapsedLogicNodes -= instanceKey
            collapsedLogicNodes -= nodeKey
            if (!effective.enabled) explicitlyExpandedNodes += instanceKey
        }
        val isCollapsed = instanceKey in collapsedLogicNodes || nodeKey in collapsedLogicNodes ||
            (!effective.enabled && instanceKey !in explicitlyExpandedNodes && nodeKey !in explicitlyExpandedNodes)
        val overrides = nodeOverrideNotes(location)
        val defaults = PipelinePropertyCatalog.resourceDefaults(location, currentModel)
        val policySummary = PipelinePropertyCatalog.policySummary(effective, defaults)
        val bindings = currentReadingGraph().anchorFlow.afterNode(location, anchorContext)
        val anchorChanges = currentReadingGraph().visibleAnchorChanges(location)
        val subTasks = effective.embeddedReferences.filter { it.kind == PipelineReferenceKind.SUBTASK }
        val hasBody = effective.action != null || effective.description != null || anchorChanges.isNotEmpty() ||
            policySummary != null || overrides.isNotEmpty() || effective.next.isNotEmpty() || effective.onError.isNotEmpty() ||
            effective.embeddedReferences.any { it.kind == PipelineReferenceKind.SUBTASK }
        val previousRow = activeLogicRows.put(nodeKey, "$instanceKey:header")
        scheduleLogic {
            if (previousRow == null) activeLogicRows.remove(nodeKey) else activeLogicRows[nodeKey] = previousRow
        }
        renderedNodeCount++
        logicRowByNode.putIfAbsent(nodeKey, "$instanceKey:header")
        logicOccurrences.getOrPut(nodeKey, ::mutableListOf).add("$instanceKey:header")
        appendLogicRow(rows, "$instanceKey:header", depth,
            revision = listOf(effective, incoming, isCollapsed, currentTrace, hasBody, overrides)) { panel ->
            val header = JPanel(BorderLayout()).apply { isOpaque = false }
            val headerText = if (!effectiveNode(location).enabled) "已禁用（enabled=false） → ${location.name}" else incoming?.let {
                    logicCandidateHeading(
                    index = it.index,
                    reference = it.reference,
                    traceNodeName = it.sourceLocation.name,
                    showTargetName = true,
                    conditionSummary = PipelineConditionSummary.flowCondition(effectiveNode(location)),
                    fieldName = it.fieldName,
                    boundTargetName = location.name.takeIf { _ -> it.reference.isAnchor },
                    anchorMayBeUnset = it.anchorMayBeUnset,
                )
            } ?: "${PipelineConditionSummary.flowBranch(0, "next", PipelineConditionSummary.flowCondition(effectiveNode(location)))} → ${location.name}"
            val overrideHint = if (overrides.isEmpty()) "" else if (overrides.any { it.active }) {
                if (traceOverrides() != null) " · 日志覆盖" else " · 任务覆盖"
            } else " · 可由任务选项覆盖"
            header.add(createLogicHeading(
                headerText + overrideHint + if (hasBody && !isCollapsed) " {" else "", location,
                incoming?.reference, incoming?.fieldName ?: "next", incoming?.sourceLocation?.name,
                incoming?.anchorHint, bold = true, subtaskSource = incoming?.sourceLocation,
            ), BorderLayout.CENTER)
            header.add(createFoldMarker(!isCollapsed, location.name) { isExpanded ->
                if (isExpanded) {
                    collapsedLogicNodes -= instanceKey; collapsedLogicNodes -= nodeKey
                    explicitlyExpandedNodes += instanceKey
                } else {
                    collapsedLogicNodes += instanceKey
                    explicitlyExpandedNodes -= instanceKey; explicitlyExpandedNodes -= nodeKey
                }
                renderLogicView(currentRoot())
                saveSession()
            }, BorderLayout.WEST)
            panel.add(header)
        }
        if (isCollapsed) return
        effective.description?.let {
            appendLogicNote(rows, "$instanceKey:description", depth + 1, it,
                foreground = UIUtil.getLabelForeground(), tooltip = PipelineVisualModel.fieldHelp("desc"))
        }
        if (overrides.isNotEmpty()) {
            val active = overrides.filter { it.active }
            val fields = active.flatMap { it.fields.entries }.associate { it.toPair() }
            val inactiveCount = overrides.size - active.size
            val summary = listOfNotNull(
                fields.takeIf { it.isNotEmpty() }?.let {
                    "当前覆盖：" + it.entries.take(2).joinToString("；") { (name, value) ->
                        "$name = ${value.take(64)}${if (value.length > 64) "…" else ""}"
                    } + if (it.size > 2) " 等 ${it.size} 个字段" else ""
                },
                "其它可选覆盖：$inactiveCount 项".takeIf { inactiveCount > 0 },
            ).joinToString(" · ")
            appendLogicRow(rows, "$instanceKey:override", depth + 1, revision = listOf(location, overrides)) { panel ->
                panel.add(createFlowSelectionLabel(summary, location, onSelect = { inspectOverrides(location) }).apply {
                    toolTipText = PipelineFlowTooltip.render(
                        active.take(4).map { PipelineConditionDetail(it.condition + " → " +
                            it.fields.entries.joinToString("；") { (name, value) -> "$name = $value" }) } +
                            PipelineConditionDetail("点击在右侧查看覆盖来源与可选配置"),
                        getFontMetrics(font),
                    )
                })
            }
        }
        if (subTasks.isEmpty()) PipelineConditionSummary.action(effective)?.let { action ->
            val operation = PipelineVisualModel.operation(effective, isAction = true)
            val details = listOf(PipelineConditionDetail(PipelineVisualModel.actionHelp(operation))) +
                PipelinePropertyCatalog.operationProperties(operation, defaults)
                    .filter { it.name in operation.parameters }.flatMap { property -> listOf(
                        PipelineConditionDetail("${property.name} = ${property.value?.let(PipelineVisualModel::display)}", 1),
                        PipelineConditionDetail(property.explanation, 2),
                    ) }
            appendLogicNote(rows, "$instanceKey:action", depth + 1, action,
                foreground = UIUtil.getLabelForeground(),
                tooltip = PipelineFlowTooltip.render(details, getFontMetrics(UIUtil.getLabelFont())))
        }
        anchorChanges.forEach { definition ->
            val text = PipelineConditionSummary.anchorChange(definition, location.name)
            appendLogicRow(rows, "$instanceKey:anchor:${definition.name}", depth + 1, revision = text) { panel ->
                panel.add(PipelineStyledText(text).apply {
                    foreground = UIUtil.getLabelForeground()
                    border = JBUI.Borders.empty(0, 4)
                    toolTipText = PipelineFlowTooltip.render(listOf(
                        PipelineConditionDetail("动作完成后应用 Anchor"),
                        PipelineConditionDetail("动作成功或失败都会应用。", 1),
                        PipelineConditionDetail("重试父 next 不会重新应用父节点设置。", 1),
                    ), getFontMetrics(UIUtil.getLabelFont()))
                })
            }
        }
        if (policySummary != null) {
            val details = PipelinePropertyCatalog.properties(effective, defaults)
                .filter { it.name in setOf("timeout", "max_hit") && !it.inherited && it.value != it.defaultValue }
                .map { PipelineConditionDetail("${it.name}：${it.explanation}") }
            appendLogicNote(rows, "$instanceKey:policy", depth + 1, policySummary,
                tooltip = PipelineFlowTooltip.render(details, getFontMetrics(UIUtil.getLabelFont())))
        }
        if (hasBody) scheduleLogic {
            appendLogicRow(rows, "$instanceKey:close", depth, revision = "}") { panel ->
                panel.add(JBLabel("}").apply {
                    alignmentX = LEFT_ALIGNMENT
                    border = JBUI.Borders.empty(0, 4)
                    foreground = UIUtil.getContextHelpForeground()
                })
            }
        }
        scheduleLogic { appendVirtualLogicBranch(location, effective.next, "next", rows, depth + 1, ancestors, expandedNodes, instanceKey, bindings,
            revealIndex = revealIndex) }
        scheduleLogic { appendVirtualLogicBranch(location, effective.onError, "on_error", rows, depth + 1, ancestors, expandedNodes, instanceKey, bindings,
            revealIndex = revealIndex) }
        if (subTasks.isNotEmpty()) scheduleLogic {
            // RunTask 是独立子流程，不能因父流程见过同名节点而省略其主体；保留祖先检查防止递归无限展开。
            appendVirtualLogicBranch(location, subTasks, "subtask", rows, depth + 1, ancestors, linkedSetOf(), instanceKey, bindings,
                revealIndex = revealIndex)
        }
    }

    private fun scheduleLogic(action: () -> Unit) {
        logicBuildQueue?.addLast(action) ?: action()
    }

    private fun appendVirtualLogicBranch(
        location: PipelineNodeLocation,
        references: List<PipelineReference>,
        fieldName: String,
        rows: MutableList<PipelineRow>,
        depth: Int,
        ancestors: Set<String>,
        expandedNodes: MutableSet<String>,
        instanceKey: String,
        anchorContext: PipelineAnchorContext,
        candidateAnchor: String? = null,
        revealIndex: Int = -1,
    ) {
        val branchKey = "$instanceKey:$fieldName"
        val editableFieldName = fieldName
            .takeIf { it == "next" || it == "on_error" }
            ?.takeIf { location.sourceFile == null || location.sourceFile == file }
            ?.takeIf { !hasTaskOverride(location, it) }
        if (references.isEmpty()) return
        if (fieldName == "subtask") {
            val collapsed = logicNodeKey(location.name, location.sourceFile ?: file) in completedSubtasks && branchKey !in expandedSubtasks
            appendLogicRow(rows, "$branchKey:fold", depth, revision = "subtask:$collapsed:${references.size}") { panel ->
                val heading = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
                heading.add(pipelineIconButton(if (collapsed) com.intellij.icons.AllIcons.General.ArrowRight else com.intellij.icons.AllIcons.General.ArrowDown,
                    if (collapsed) "展开 subtask" else "收起 subtask") {
                    if (collapsed) expandedSubtasks += branchKey else {
                        expandedSubtasks -= branchKey
                        completedSubtasks = completedSubtasks + logicNodeKey(location.name, location.sourceFile ?: file)
                    }
                    renderLogicView(currentRoot())
                })
                heading.add(JBLabel("subtask (${references.size})"))
                panel.add(heading)
            }
            if (collapsed) return
        }
        val bindings = currentReadingGraph().anchorFlow.forBranch(references, fieldName, anchorContext)
        // 分支类型已经写进候选标题，省略独立的 on_error 容器，避免同一目标多出一层空壳。
        if (hasTaskOverride(location, fieldName)) {
            appendLogicNote(rows, "$branchKey:override", depth, "字段来自当前任务的 pipeline_override；它替换默认列表。")
        }
        for (index in references.indices.reversed()) scheduleLogic {
            val branchExpandedNodes = if (fieldName == "subtask") linkedSetOf<String>() else expandedNodes
            val reference = references[index]
            val candidateKey = "$branchKey:$index"
            val target = reference.target
            val anchorResolution = reference.takeIf { it.isAnchor }?.let { currentReadingGraph().anchorFlow.resolve(it, bindings) }
            val targetLocation = if (reference.isAnchor) anchorResolution?.inlineTarget else target?.let { targetNode ->
                currentReadingGraph().node(targetNode.file, targetNode.name)
            }
            val targetKey = targetLocation?.let { logicNodeKey(it.name, it.sourceFile ?: file) }
            val targetRow = activeLogicRows[targetKey] ?: logicRowByNode[targetKey]
            val nextRevealKey = playbackRevealPath.getOrNull(revealIndex + 1).takeIf { revealIndex >= 0 }
            val revealTarget = targetKey != null && targetKey == nextRevealKey
            if (revealTarget) {
                expandedBranches += candidateKey
                // 只放行回放目标路径，不提高整棵树的预算，也不解除循环边界。
                if (renderedNodeCount >= nodeBudget) playbackBudgetBranches += candidateKey
            } else if (targetLocation == null && anchorResolution?.targets?.any {
                    logicNodeKey(it.name, it.sourceFile ?: file) == nextRevealKey
                } == true) {
                expandedBranches += candidateKey
            }
            val expansionBoundary = targetKey?.let {
                PipelineExpansionPolicy.boundary(it, ancestors, branchExpandedNodes, candidateKey in expandedBranches,
                    renderedNodeCount, if (candidateKey in playbackBudgetBranches) Int.MAX_VALUE else nodeBudget)
            }
            val boundary = when {
                reference.isAnchor && targetLocation == null -> "运行时绑定"
                target == null && targetLocation == null -> "目标 ${reference.targetName} 未解析，保留为边界。"
                targetLocation == null && target != null -> "跨文件目标未在当前项目索引中展开：${displayPath(target.file)} → ${target.name}。"
                expansionBoundary == PipelineExpansionBoundary.CYCLE -> "↩ 循环"
                expansionBoundary == PipelineExpansionBoundary.SHARED -> "⇉ 共享"
                expansionBoundary == PipelineExpansionBoundary.BUDGET -> "后续流程尚未展开，可继续加载下一批。"
                else -> null
            }
            if (boundary != null) {
                if (targetKey != null) logicOccurrences.getOrPut(targetKey, ::mutableListOf).add("$candidateKey:card")
                appendLogicRow(rows, "$candidateKey:card", depth,
                    revision = listOf(reference, location, targetLocation?.let(::effectiveNode), editableFieldName, fieldName, index, references.size, currentTrace, expansionBoundary, anchorResolution)) {
                    addLogicCandidateCard(
                        index = index,
                        reference = reference,
                        container = it,
                        traceNodeName = location.name,
                        editableField = editableFieldName,
                        fieldName = fieldName,
                        sourceLocation = location,
                        referenceCount = references.size,
                        targetLocation = targetLocation,
                        showTargetName = true,
                        boundaryKind = expansionBoundary,
                        anchorResolution = anchorResolution,
                    )
                }
                if (!reference.isAnchor && expansionBoundary != PipelineExpansionBoundary.CYCLE && expansionBoundary != PipelineExpansionBoundary.SHARED) {
                    appendLogicNote(rows, "$candidateKey:boundary", depth, boundary)
                }
                if (reference.isAnchor && targetLocation == null) {
                    appendAnchorCandidates(location, reference, candidateKey, rows, depth, ancestors, expandedNodes, bindings, anchorResolution!!,
                        revealIndex)
                } else if (targetLocation != null) {
                    appendLogicRow(rows, "$candidateKey:continue", depth,
                        revision = listOf(targetLocation, targetRow, candidateKey, expansionBoundary)) { panel ->
                        if (expansionBoundary == PipelineExpansionBoundary.CYCLE || expansionBoundary == PipelineExpansionBoundary.SHARED) {
                            panel.add(JButton(if (expansionBoundary == PipelineExpansionBoundary.CYCLE) "↩ 回到此层" else "定位流程中的节点").apply {
                                isFocusable = false
                                alignmentX = LEFT_ALIGNMENT
                                addActionListener {
                                    logicPanel.scrollAnchor()?.let(readingHistory::addLast)
                                    targetRow?.let { logicPanel.restoreAnchor(it); logicPanel.activateRow(it) }
                                    inspectNode(targetLocation)
                                }
                            })
                        }
                        if (expansionBoundary != PipelineExpansionBoundary.CYCLE) panel.add(JButton(
                            if (expansionBoundary == PipelineExpansionBoundary.BUDGET) "继续展开（下一批）" else "在此展开"
                        ).apply {
                            isFocusable = false
                            alignmentX = LEFT_ALIGNMENT
                            addActionListener {
                                expandedBranches += candidateKey
                                if (renderedNodeCount >= nodeBudget) nodeBudget += MAX_LOGIC_NODES
                                renderLogicView(currentRoot())
                                saveSession()
                            }
                        })
                    }
                }
            } else if (targetLocation != null && targetKey != null) {
                branchExpandedNodes += targetKey
                appendLogicNode(
                    targetLocation,
                    rows,
                    depth,
                    ancestors + targetKey,
                    branchExpandedNodes,
                    instanceKey = PipelineExpansionPolicy.instanceKey(candidateKey, targetKey),
                    anchorContext = candidateAnchor?.let { bindings.bind(it, reference.targetName) } ?: bindings,
                    revealIndex = if (revealTarget) revealIndex + 1 else -1,
                    incoming = LogicIncomingBranch(
                        index = index,
                        reference = reference,
                        sourceLocation = location,
                        editableField = editableFieldName,
                        fieldName = fieldName,
                        referenceCount = references.size,
                        anchorHint = anchorResolution?.hint(),
                        anchorMayBeUnset = anchorResolution?.value?.mayBeUnset == true,
                    ),
                )
            }
        }
    }

    private fun appendAnchorCandidates(location: PipelineNodeLocation, reference: PipelineReference, key: String,
        rows: MutableList<PipelineRow>, depth: Int, ancestors: Set<String>, expandedNodes: MutableSet<String>,
        bindings: PipelineAnchorContext, resolution: PipelineAnchorResolution, revealIndex: Int = -1) {
        val candidates = resolution.targets.map { target ->
            reference.copy(targetName = target.name, displayName = target.name, isAnchor = false,
                targetExists = true, isAnchorUnresolved = false, target = PipelineNodeTarget(target.sourceFile ?: file, target.name, target.nameRange,
                    target.description, target.recognition?.summary, target.action?.summary, target.next.size))
        }
        if (candidates.isEmpty()) return
        appendLogicRow(rows, "$key:candidates", depth, revision = listOf(candidates, key in expandedBranches)) { panel ->
            panel.add(JButton(if (key in expandedBranches) "收起候选分支" else "查看 ${candidates.size} 个可能目标").apply {
                isFocusable = false
                addActionListener {
                    if (!expandedBranches.add(key)) expandedBranches.remove(key)
                    renderLogicView(currentRoot())
                    saveSession()
                }
            })
        }
        if (key in expandedBranches) scheduleLogic {
            appendVirtualLogicBranch(location, candidates, "anchor_candidate", rows, depth + 1,
                ancestors, expandedNodes, "$key:dynamic", bindings, reference.targetName, revealIndex)
        }
    }

    private fun createFoldMarker(
        initiallyExpanded: Boolean,
        subject: String = "此节点",
        onToggle: (Boolean) -> Unit,
    ): JComponent = object : JComponent() {
        private var expanded = initiallyExpanded

        init {
            isOpaque = false
            isFocusable = false
            preferredSize = Dimension(JBUI.scale(16), JBUI.scale(24))
            minimumSize = preferredSize
            maximumSize = preferredSize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (expanded) "收起$subject" else "展开$subject"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button != MouseEvent.BUTTON1) return
                    expanded = !expanded
                    toolTipText = if (expanded) "收起$subject" else "展开$subject"
                    onToggle(expanded)
                    repaint()
                }
            })
        }

        override fun paintComponent(graphics: Graphics) {
            val graphics2D = graphics.create() as Graphics2D
            try {
                graphics2D.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                graphics2D.color = UIUtil.getContextHelpForeground()
                val centerX = width / 2
                val centerY = height / 2
                val halfSize = JBUI.scale(4)
                val triangle = if (expanded) {
                    java.awt.Polygon(
                        intArrayOf(centerX - halfSize, centerX + halfSize, centerX),
                        intArrayOf(centerY - halfSize / 2, centerY - halfSize / 2, centerY + halfSize),
                        3,
                    )
                } else {
                    java.awt.Polygon(
                        intArrayOf(centerX - halfSize / 2, centerX - halfSize / 2, centerX + halfSize),
                        intArrayOf(centerY - halfSize, centerY + halfSize, centerY),
                        3,
                    )
                }
                graphics2D.fillPolygon(triangle)
            } finally {
                graphics2D.dispose()
            }
        }
    }



    private fun addLogicCandidateCard(
        index: Int,
        reference: PipelineReference,
        container: JPanel = logicPanel,
        traceNodeName: String? = null,
        editableField: String? = null,
        fieldName: String = "next",
        sourceLocation: PipelineNodeLocation? = null,
        referenceCount: Int = 1,
        targetLocation: PipelineNodeLocation? = null,
        showTargetName: Boolean = true,
        boundaryKind: PipelineExpansionBoundary? = null,
        anchorResolution: PipelineAnchorResolution? = null,
    ) {
            val heading = logicCandidateHeading(
                index = index,
                reference = reference,
                traceNodeName = traceNodeName,
                showTargetName = showTargetName,
                conditionSummary = targetLocation?.let { PipelineConditionSummary.flowCondition(effectiveNode(it)) },
                fieldName = fieldName,
                boundTargetName = targetLocation?.name.takeIf { reference.isAnchor },
                clearedAnchor = anchorResolution?.isCleared == true,
                anchorMayBeUnset = anchorResolution?.value?.mayBeUnset == true,
            )
        val card = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        val boundaryLabel = when (boundaryKind) {
            PipelineExpansionBoundary.CYCLE -> " · ↩ 循环"
            PipelineExpansionBoundary.SHARED -> " · ⇉ 共享"
            else -> ""
        }
        card.add(
            createLogicHeading(heading + boundaryLabel, targetLocation, reference, fieldName, traceNodeName,
                anchorResolution?.hint(), subtaskSource = sourceLocation),
            BorderLayout.CENTER,
        )
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        container.add(card)
    }

    private fun logicCandidateHeading(
        index: Int,
        reference: PipelineReference,
        traceNodeName: String?,
        showTargetName: Boolean,
        conditionSummary: String? = null,
        fieldName: String = "next",
        boundTargetName: String? = null,
        clearedAnchor: Boolean = false,
        anchorMayBeUnset: Boolean = false,
    ): String {
        if (fieldName == "subtask") return "${index + 1}. subtask → ${reference.targetName}"
        if (fieldName == "anchor_candidate") return "可能目标（运行时绑定） → ${reference.targetName}"
        val jumpBack = if (reference.isJumpBack) {
            "[JumpBack] "
        } else ""
        if (reference.isAnchor && boundTargetName == null) {
            return "${index + 1}. $jumpBack${if (clearedAnchor) "skip" else PipelineConditionSummary.candidatePrefix(index, fieldName)} ${reference.targetName}${if (clearedAnchor) "（已清除）" else " 已绑定"} · 运行时绑定"
        }
        return buildString {
            append("${index + 1}. ")
            append(jumpBack)
            val condition = PipelineConditionSummary.flowCandidateCondition(reference, conditionSummary)
            append(PipelineConditionSummary.flowBranch(index, fieldName,
                if (reference.isAnchor) PipelineConditionSummary.anchorCondition(condition, reference.targetName, anchorMayBeUnset) else condition))
            append(if (showTargetName) " → ${boundTargetName ?: reference.targetName}" else " →")
            if (boundTargetName != null) {
                append(" · 运行时绑定")
            }
        }
    }

    private fun createLogicHeading(
        text: String,
        location: PipelineNodeLocation?,
        reference: PipelineReference? = null,
        fieldName: String = "next",
        parentName: String? = null,
        anchorHint: String? = null,
        bold: Boolean = false,
        subtaskSource: PipelineNodeLocation? = null,
    ): JComponent {
        val recognition = location?.let { effectiveNode(it).recognition }
        val conditionDetails = if (location != null) recognition?.readingDetails?.takeIf { it.isNotEmpty() }
            ?: listOf(PipelineConditionDetail(PipelineConditionSummary.flowCondition(effectiveNode(location))))
            else if (reference?.isAnchor == true) emptyList()
            else reference?.target?.recognitionDetails?.takeIf { it.isNotEmpty() }
                ?: listOf(PipelineConditionDetail(reference?.target?.recognitionSummary ?: "目标尚未解析"))
        val tooltip = PipelineFlowTooltip.render(
            if (fieldName == "subtask" && subtaskSource != null) subtaskDetails(effectiveNode(subtaskSource)) else conditionDetails,
            getFontMetrics(UIUtil.getLabelFont()),
            jumpBack = reference?.isJumpBack == true, parentName = parentName, fieldName = fieldName,
            anchorHint = anchorHint, sourcePath = (location?.sourceFile ?: reference?.target?.file)?.let(::displayPath),
            operationHint = if (location != null) "点击查看详情 · Ctrl+点击跳转源码" else "目标暂时无法在流程索引中展开",
        )
        fun label(part: String): JComponent {
            val component = if (location != null) createFlowSelectionLabel(part, location, fieldName == "on_error")
                else reference?.let { createFlowReferenceLabel(part, it, null, fieldName == "on_error") } ?: JBLabel(part)
            if (bold) component.font = component.font.deriveFont(Font.BOLD)
            component.toolTipText = tooltip
            return component
        }
        return withSimulationRun(withPlaybackBadge(label(text), location, reference, parentName), reference, parentName, fieldName)
    }

    private fun subtaskDetails(source: PipelineNodeLocation): List<PipelineConditionDetail> {
        val operation = PipelineVisualModel.operation(source, isAction = true)
        val parameters = operation.parameters["custom_action_param"]?.takeIf { it.isJsonObject }?.asJsonObject
        val random = parameters?.get("random_choice")?.toString()?.toIntOrNull() ?: 0
        return buildList {
            add(PipelineConditionDetail("subtask · ${source.name} 的 SubTask 动作"))
            add(PipelineConditionDetail(if (random > 0) "随机选取至多 $random 项，实际选择和顺序由本轮抽取决定。"
                else "按列表顺序调用独立子流程。", 1))
            add(PipelineConditionDetail("每个子流程结束后继续父动作，父动作结束后再处理父节点的 next。", 1))
            listOf("continue", "strict").forEach { name ->
                parameters?.get(name)?.let { add(PipelineConditionDetail("$name = $it", 1)) }
            }
        }
    }

    private data class LogicIncomingBranch(
        val index: Int,
        val reference: PipelineReference,
        val sourceLocation: PipelineNodeLocation,
        val editableField: String?,
        val fieldName: String,
        val referenceCount: Int,
        val anchorHint: String? = null,
        val anchorMayBeUnset: Boolean = false,
    )

    private fun hasTaskOverride(location: PipelineNodeLocation, fieldName: String): Boolean {
        if (!overrideToggle.isSelected) return false
        return currentOverrides().any { override ->
            override.targetName == location.name && override.fields.any { it.name == fieldName }
        }
    }

    private fun nodeOverrideNotes(location: PipelineNodeLocation): List<LogicOverrideNote> {
        val entry = selectedEntry
        val active = currentOverrides()
        if (overrideNotesModel !== currentModel || overrideNotesEntry !== entry ||
            overrideNotesActive !== active || overrideNotesEnabled != overrideToggle.isSelected) {
            overrideNotesModel = currentModel
            overrideNotesEntry = entry
            overrideNotesActive = active
            overrideNotesEnabled = overrideToggle.isSelected
            val logged = traceOverrides() != null && overrideToggle.isSelected
            val possible = entry?.let {
                PipelineTaskContext.possibleOverrides(it, currentModel.pipelineOverrides)
            }.orEmpty()
            val patches = if (logged) active + possible else possible
            overrideNotes = patches
                .groupBy { it.targetName }.mapValues { (_, patches) -> patches.map { patch ->
                    val fields = patch.fields.associate { it.name to overrideFieldValue(patch, it) }
                    val source = if (patch.logTaskId != null) "日志第 ${patch.logLineNumber} 行" else
                        entry?.let { PipelineTaskContext.overrideCondition(it, patch) }.orEmpty()
                    LogicOverrideNote(source, fields, overrideToggle.isSelected && patch in active)
                } }
        }
        return overrideNotes[location.name].orEmpty()
    }

    private fun overrideFieldValue(override: PipelineOverrideLocation, field: PipelineFieldLocation): String {
        val value = runCatching {
            (PipelineJsonc.parse(override.rawJson) as? com.google.gson.JsonObject)?.get(field.name)
        }.getOrNull()
        return value?.let { GsonBuilder().setPrettyPrinting().create().toJson(it) } ?: field.summary
    }

    private fun addControlFlowExplanation(location: PipelineNodeLocation, sourceFile: VirtualFile, original: PipelineNodeLocation) {
        val catalog = currentModel.projectNodes.groupBy { it.name }.mapValues { (_, nodes) -> nodes.singleOrNull()?.let(::effectiveNode) }
        val frame = simulationCursor?.let { simulation?.frames?.getOrNull(it.eventIndex) }
        val resolve: (String) -> PipelineNodeLocation? = { name ->
            catalog[if (name.startsWith("[Anchor]")) frame?.anchors?.get(name.removePrefix("[Anchor]")) else name]
        }
        val visuals = PipelineVisualDetails(project, sourceFile, resolve, {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(it, true)
        }, blocked = { simulation != null || it in currentModel.generatedFiles }, defaults = {
            PipelinePropertyCatalog.resourceDefaults(it ?: location, currentModel)
        }, environmentId = currentModel.environmentId)
        val recognition = PipelineVisualModel.operation(location, resolve = resolve)
        val action = PipelineVisualModel.operation(location, isAction = true, resolve = resolve).let { operation ->
            if (frame?.node?.name == location.name) operation.copy(simulatedRegions = frame.targets.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()) else operation
        }
        addQuickReadField("条件", if (location.enabled) recognition.title + if (location.recognition == null) "（默认）" else "" else "已禁用（enabled=false）",
            location.recognition, sourceFile, emphasized = true, operationEdit = { editOperation(location, sourceFile, false) })
        detailsPanel.add(visuals.operation(recognition))
        addQuickReadField("动作", action.title + if (location.action == null) "（默认）" else "", location.action, sourceFile,
            emphasized = true, operationEdit = { editOperation(location, sourceFile, true) }, action = action)
        detailsPanel.add(visuals.operation(action))
        visuals.referenceImage(original, recognition, action)?.let { detailsPanel.add(it) }
        PipelineConditionSummary.anchor(location)?.let { anchor ->
            addQuickReadField("锚点", anchor, location.anchor, sourceFile)
        }
        detailsPanel.add(createSectionLabel("节点属性"))
        val defaults = PipelinePropertyCatalog.resourceDefaults(location, currentModel)
        val defaultContent = object : JPanel() {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            layout = VerticalLayout(0)
        }
        var defaultCount = 0
        PipelinePropertyCatalog.properties(location, defaults).forEach { property ->
            if (property.name == "next" || property.name in setOf("on_error", "anchor") && !property.inherited) return@forEach
            val target = if (property.isDefault) defaultContent else detailsPanel
            if (property.isDefault) defaultCount++
            target.add(PipelineWrappingLabel(property.text).apply { toolTipText = property.explanation })
            if (property.name in setOf("rate_limit", "timeout", "max_hit")) {
                target.add(PipelineWrappingLabel(property.explanation).apply { foreground = UIUtil.getContextHelpForeground() })
            }
        }
        if (defaultCount > 0) detailsPanel.add(pipelineDefaultProperties(defaultContent, defaultCount))
    }

    private fun addQuickReadField(
        title: String,
        text: String,
        field: PipelineFieldLocation?,
        sourceFile: VirtualFile,
        emphasized: Boolean = false,
        operationEdit: (() -> Unit)? = null,
        action: PipelineOperation? = null,
    ) {
        val fieldSourceFile = field?.sourceFile ?: sourceFile
        val row = object : JPanel(PipelineBaselineLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        row.add(JBLabel("$title：").apply {
            foreground = if (emphasized) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
            if (emphasized) font = font.deriveFont(Font.BOLD)
            verticalAlignment = javax.swing.SwingConstants.CENTER
            border = JBUI.Borders.empty(2, 0)
            preferredSize = Dimension(JBUI.scale(42), preferredSize.height)
            toolTipText = PipelineVisualModel.fieldHelp(title)
        }, BorderLayout.WEST)
        val value = if (emphasized) {
            PipelineStyledText(text).apply {
                if (field != null) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(event: MouseEvent) {
                            if (event.button == MouseEvent.BUTTON1 && selectedText.isNullOrEmpty()) navigateToFileRange(fieldSourceFile, field.range)
                        }
                    })
                }
            }
        } else if (field == null) {
            PipelineWrappingLabel(text)
        } else {
            createNavigationLabel(text) { navigateToFileRange(fieldSourceFile, field.range) }
        }
        if (action != null) {
            value.foreground = UIUtil.getLabelForeground()
            value.toolTipText = PipelineVisualModel.actionHelp(action)
        }
        row.add(value, BorderLayout.CENTER)
        if (field != null || operationEdit != null) {
            row.add(JButton("编辑").apply {
                toolTipText = "编辑当前字段"
                isEnabled = fieldSourceFile.isWritable && fieldSourceFile !in currentModel.generatedFiles && field?.isDerived != true
                if (field?.isDerived == true) toolTipText = "有效值由多个来源合成，请在下方覆盖来源或原始 JSON 中编辑"
                isFocusable = false
                addActionListener {
                    if (operationEdit != null) operationEdit() else if (field != null) editJsonField(field, fieldSourceFile)
                }
            }, BorderLayout.EAST)
        }
        detailsPanel.add(row)
    }

    private fun addDetailText(text: String, emphasized: Boolean = false) {
        detailsPanel.add(PipelineWrappingLabel(text).apply {
            if (emphasized) font = font.deriveFont(Font.BOLD)
            foreground = UIUtil.getLabelForeground()
            alignmentX = LEFT_ALIGNMENT
        })
    }

    private fun addAnchorDefinitions(location: PipelineNodeLocation, sourceFile: VirtualFile = file) {
        if (location.anchorDefinitions.isEmpty()) return
        detailsPanel.add(createSectionLabel("Anchor 定义"))
        location.anchorDefinitions.forEach { definition ->
            val target = definition.targetNodeName ?: "当前节点（运行时绑定）"
            val state = if (definition.isCleared) "清除目标" else "目标：$target"
            detailsPanel.add(
                createNavigationLabel("${definition.name} · $state") {
                    navigateToFileRange(sourceFile, definition.range)
                },
            )
        }
        location.anchor?.let { field ->
            detailsPanel.add(JButton("编辑 Anchor 定义").apply {
                isFocusable = false
                val fieldSourceFile = field.sourceFile ?: sourceFile
                isEnabled = fieldSourceFile.isWritable && fieldSourceFile !in currentModel.generatedFiles && !field.isDerived
                toolTipText = "编辑当前节点的完整 anchor 字段，保留其它节点和注释"
                addActionListener {
                    editJsonField(field, fieldSourceFile)
                }
            })
        }
        detailsPanel.add(JButton("管理项目 Anchor").apply {
            isFocusable = false
            addActionListener { showAnchorManager() }
        })
    }

    private fun showAnchorManager() {
        val snapshots = ReadAction.compute<Map<VirtualFile, PipelineEditSnapshot>, RuntimeException> {
            currentModel.indexedVersions.keys.mapNotNull { sourceFile ->
                FileDocumentManager.getInstance().getDocument(sourceFile)?.let { document ->
                    sourceFile to PipelineEditSnapshot(document.modificationStamp, document.text)
                }
            }.toMap()
        }
        val cursor = playbackToolbar.session
        val observedEvents = (cursor?.source as? PipelineLogPlaybackSource)?.eventsThrough(cursor.eventIndex).orEmpty()
        val rows = PipelineAnchorOverview.build(currentModel).map { row ->
            val observed = observedEvents.lastOrNull { it.anchorName == row.name }
            row.copy(
                canEdit = row.canEdit && row.definitionFile?.let { sourceFile ->
                    snapshots[sourceFile]?.version == currentModel.indexedVersions[sourceFile]
                } == true,
                runtimeText = observed?.let {
                    "日志观察：${it.nodeName ?: "未知节点"} · ${it.status.label} · 第 ${it.lineNumber} 行（不代表后续绑定）"
                } ?: "本次日志未观察到此 Anchor 的运行时解析",
            )
        }
        PipelineAnchorManagerDialog(
            project = project,
            rows = rows,
            onNavigate = ::navigateToFileRange,
            onAddDefinition = {
                currentModel.nodes.getOrNull(nodeList.selectedIndex)?.let(::addAnchorDefinition)
            },
            onRename = { row -> snapshots[row.definitionFile]?.let { renameAnchorDefinition(row, it) } },
            onRemove = { row -> snapshots[row.definitionFile]?.let { removeAnchorDefinition(row, it) } },
            onEditField = { row -> snapshots[row.definitionFile]?.let { editAnchorField(row, it) } },
        ).show()
    }

    private fun renameAnchorDefinition(row: PipelineAnchorManagerRow, snapshot: PipelineEditSnapshot) {
        val targetFile = row.definitionFile ?: return
        if (!ensureFileSnapshot(targetFile, snapshot)) return
        val newName = Messages.showInputDialog(
            project,
            "修改 Anchor 名称：",
            "重命名 Anchor",
            null,
            row.name,
            null,
        )?.trim().orEmpty()
        if (newName.isEmpty() || newName == row.name) return
        val range = row.definitionRange ?: return
        val replacement = quoteJsonString(newName)
        val duplicate = PipelineAnchorOverview.build(currentModel).any {
            it.name == newName && it.definitionFile == targetFile && it.ownerNodeName == row.ownerNodeName
        }
        if (duplicate) {
            Messages.showWarningDialog(project, "当前节点已定义此 Anchor：$newName", "无法重命名 Anchor")
            return
        }
        applyAnchorEdits(
            row, snapshot, listOf(PipelineTextEdit(range, replacement)), "重命名 Anchor",
            "只重命名此设置点；${row.references.size} 个现有引用保持原名称。",
        )
    }

    private fun removeAnchorDefinition(row: PipelineAnchorManagerRow, snapshot: PipelineEditSnapshot) {
        val targetFile = row.definitionFile ?: return
        if (!ensureFileSnapshot(targetFile, snapshot)) return
        val definitionRange = row.definitionRange ?: return
        val deletionRange: TextRange
        val replacement: String?
        if (row.isScalar) {
            deletionRange = row.valueRange ?: return
            replacement = "[]"
        } else {
            deletionRange = row.propertyRange ?: definitionRange
            replacement = null
        }
        val edits = if (replacement == null) {
            PipelineJsonTextEdits.deletionEdits(snapshot.text, deletionRange)
        } else {
            listOf(PipelineTextEdit(deletionRange, replacement))
        }
        applyAnchorEdits(
            row, snapshot, edits, "删除 Anchor 定义",
            "${row.references.size} 个现有引用不会自动改写；运行时将不再经过此设置点绑定 Anchor。",
        )
    }

    private fun editAnchorField(row: PipelineAnchorManagerRow, snapshot: PipelineEditSnapshot) {
        val targetFile = row.definitionFile ?: return
        if (!ensureFileSnapshot(targetFile, snapshot)) return
        val range = row.valueRange ?: return
        val dialog = PipelineJsonFieldDialog(project, "anchor", snapshot.text.substring(range.startOffset, range.endOffset))
        if (!dialog.showAndGet()) return
        applyAnchorEdits(row, snapshot, listOf(PipelineTextEdit(range,
            PipelineJsonTextEdits.alignValue(snapshot.text, range.startOffset, dialog.value))), "编辑 Anchor 绑定")
    }

    private fun documentFor(sourceFile: VirtualFile): Document? =
        ReadAction.compute<Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(sourceFile) }

    private fun ensureFileSnapshot(
        sourceFile: VirtualFile,
        snapshot: PipelineEditSnapshot,
        edits: List<PipelineTextEdit> = emptyList(),
    ): Boolean {
        val document = documentFor(sourceFile) ?: return false
        if (!sourceFile.isWritable || sourceFile in currentModel.generatedFiles || hasGeneratedHeader(document.text)) {
            Messages.showWarningDialog(project, "文件不可写或由生成器维护：${displayPath(sourceFile)}", "无法编辑 Pipeline")
            return false
        }
        return ensureDocumentSnapshot(document, snapshot, edits)
    }

    private fun hasGeneratedHeader(text: String): Boolean = text.lineSequence().take(40)
        .map(String::trim)
        .filter { it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") }
        .any { Regex("(?i)generated|autogenerated|do\\s+not\\s+edit|由[^\\r\\n]*生成").containsMatchIn(it) }

    private fun applyAnchorEdits(
        row: PipelineAnchorManagerRow,
        snapshot: PipelineEditSnapshot,
        edits: List<PipelineTextEdit>,
        title: String,
        note: String = "",
    ) {
        val targetFile = row.definitionFile ?: return
        if (!ensureFileSnapshot(targetFile, snapshot, edits)) return
        val preview = edits.joinToString("\n") { snapshot.text.substring(it.range.startOffset, it.range.endOffset) }
        val replacement = edits.joinToString("\n") { it.replacement }.ifEmpty { "（删除）" }
        val document = documentFor(targetFile) ?: return
        if (!confirmDocumentChange(title, preview, replacement, "文件：${displayPath(targetFile)}\n$note", document, edits)) return
        if (!ensureFileSnapshot(targetFile, snapshot, edits)) return
        WriteCommandAction.runWriteCommandAction(project, title, "MaaPipeline JSON Execution Lens", Runnable {
            edits.sortedByDescending { it.range.startOffset }.forEach {
                document.replaceString(it.range.startOffset, it.range.endOffset, it.replacement)
            }
        })
        refreshFromDocument(editor.document)
    }

    private fun addAnchorDefinition(node: PipelineNodeLocation) {
        if (!ensureEditable()) return
        val snapshot = captureEditSnapshot()
        val anchorName = Messages.showInputDialog(
            project,
            "输入要定义的 Anchor 名称：",
            "新增 Anchor 定义",
            null,
            "",
            null,
        )?.trim().orEmpty()
        if (anchorName.isEmpty()) return
        if (!ensureSnapshot(snapshot)) return
        if (node.anchorDefinitions.any { it.name == anchorName }) {
            Messages.showWarningDialog(project, "当前节点已定义此 Anchor：$anchorName", "无法新增 Anchor")
            return
        }
        val field = node.anchor
        val range = field?.valueRange ?: node.propertyRange
        if (range.startOffset < 0 || range.endOffset > editor.document.textLength) return
        val oldText = editor.document.getText(range)
        val replacement = if (field == null) {
            val objectStart = oldText.indexOf('{')
            if (objectStart < 0) return
            oldText.substring(0, objectStart) + PipelineJsonTextEdits.appendMember(
                oldText.substring(objectStart),
                "\"anchor\": [${quoteJsonString(anchorName)}]",
            )
        } else {
            val value = editor.document.getText(field.valueRange)
            when {
                value.trim().startsWith("[") && value.trim().endsWith("]") -> {
                    PipelineJsonTextEdits.appendMember(value, quoteJsonString(anchorName))
                }
                value.trim().startsWith("{") && value.trim().endsWith("}") -> {
                    PipelineJsonTextEdits.appendMember(value, "${quoteJsonString(anchorName)}: ${quoteJsonString(node.name)}")
                }
                else -> "[${value.trim()}, ${quoteJsonString(anchorName)}]"
            }
        }
        val edit = PipelineJsonTextEdits.replacementEdit(range, oldText, replacement)
        if (!confirmDocumentChange("新增 Anchor 定义", oldText, replacement, edits = listOf(edit))) return
        if (!ensureEditable()) return
        WriteCommandAction.runWriteCommandAction(
            project,
            "新增 Anchor 定义",
            "MaaPipeline JSON Execution Lens",
            Runnable { editor.document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement) },
        )
    }

    private fun addReferences(
        title: String,
        references: List<PipelineReference>,
        field: PipelineFieldLocation? = null,
        fieldName: String? = null,
        sourceFile: VirtualFile = file,
    ) {
        // 空的 on_error 没有阅读价值；不创建区块可以把注意力留给成功路径。
        if (references.isEmpty() && fieldName == "on_error") return
        val body = JPanel().apply {
            layout = VerticalLayout(0)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        if (references.isEmpty()) {
            body.add(JBLabel("（无）").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        } else {
            references.forEachIndexed { index, reference ->
                val marker = buildString {
                    if (reference.isJumpBack) append("↩ ")
                    if (reference.isAnchor) append("⚓ ")
                }
                val states = buildList {
                    if (reference.isAmbiguousTarget) add("目标名称不唯一")
                    if (!reference.targetExists && !reference.isAnchor) add("项目中未定义")
                    if (reference.isAnchorUnresolved) add("Anchor 未解析")
                    if (reference.isCrossFile) add("跨文件")
                    if (reference.isCycle) add("回边")
                    if (reference.isSharedTarget) add("共享节点")
                }
                val targetLocation = reference.target?.let { target ->
                    currentModel.projectNodes.firstOrNull { node ->
                        (node.sourceFile ?: file) == target.file && node.name == target.name
                    }
                }
                body.add(
                    createReferenceCard(
                        index = index,
                        marker = marker,
                        reference = reference,
                        states = states,
                        fieldName = fieldName,
                        referenceCount = references.size,
                        sourceFile = sourceFile,
                        ownerField = field,
                        references = references,
                        targetRecognitionSummary = targetLocation?.let { PipelineConditionSummary.flowCondition(effectiveNode(it)) },
                    ),
                )
            }
        }
        val section = object : JPanel(BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        val foldMarker = createFoldMarker(body.isVisible, title) { isExpanded ->
            body.isVisible = isExpanded
            detailsPanel.revalidate()
            detailsPanel.repaint()
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(createSectionLabel(title).apply {
                if (fieldName == "on_error") {
                    foreground = JBColor.namedColor("Label.errorForeground", UIUtil.getErrorForeground())
                } else if (fieldName == "next") {
                    foreground = JBColor.namedColor("Label.infoForeground", UIUtil.getLabelForeground())
                }
            }, BorderLayout.CENTER)
            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                if ((field != null && !field.isDerived) || field == null && fieldName == "next") {
                    add(pipelineIconButton(com.intellij.icons.AllIcons.General.Add, "添加一个${referenceFieldLabel(fieldName)}") {
                            if (field != null) addReference(field, fieldName, field.sourceFile ?: sourceFile)
                            else addMissingNext(sourceFile)
                    }.apply {
                        isEnabled = sourceFile.isWritable && sourceFile !in currentModel.generatedFiles
                    })
                }
                add(foldMarker)
            }
            add(actions, BorderLayout.EAST)
        }
        section.add(header, BorderLayout.NORTH)
        section.add(body, BorderLayout.CENTER)
        detailsPanel.add(section)
    }

    private fun addReference(field: PipelineFieldLocation, fieldName: String? = field.name, sourceFile: VirtualFile = file) {
        val context = detailEditContext(sourceFile) ?: return
        val dialog = PipelineReferenceDialog(project, currentModel.projectNodes)
        if (!dialog.showAndGet()) return
        val targetName = dialog.value
        if (targetName.isEmpty()) return
        val range = field.valueRange
        if (range.startOffset < 0 || range.endOffset > context.snapshot.text.length) return
        val currentValue = context.snapshot.text.substring(range.startOffset, range.endOffset)
        val quotedName = quoteJsonString(targetName)
        val replacement = if (currentValue.trim().startsWith("[") && currentValue.trim().endsWith("]")) {
            val ownerIndent = context.snapshot.text.substring(0, range.startOffset).substringAfterLast('\n').takeWhile { it == ' ' || it == '\t' }
            val existingIndent = Regex("(?m)^([ \\t]+)\\\"").find(currentValue)?.groupValues?.get(1)
            val indentUnit = Regex("(?m)^([ \\t]+)\\\"").find(context.snapshot.text)?.groupValues?.get(1) ?: "    "
            PipelineJsonTextEdits.appendMember(currentValue, quotedName, existingIndent ?: ownerIndent + indentUnit)
        } else {
            "[$currentValue, $quotedName]"
        }
        val edit = PipelineJsonTextEdits.replacementEdit(range, currentValue, replacement)
        applyDetailEdits(context, listOf(edit), "添加 Pipeline ${referenceFieldLabel(fieldName)}")
    }

    private fun addMissingNext(sourceFile: VirtualFile) {
        val location = selectedDetail() ?: return
        val context = detailEditContext(sourceFile) ?: return
        val dialog = PipelineReferenceDialog(project, currentModel.projectNodes)
        if (!dialog.showAndGet()) return
        val range = ReadAction.compute<TextRange?, RuntimeException> {
            val owner = PipelineOperationEdits.objectValue(project, context.snapshot.text)?.findProperty(location.name)?.value as? JsonObject
            owner?.takeIf { it.findProperty("next") == null }?.textRange
        } ?: return
        val original = context.snapshot.text.substring(range.startOffset, range.endOffset)
        val updated = jsonObjectPropertyInsertion(context.document, range, "next", "[${quoteJsonString(dialog.value)}]")
        applyDetailEdits(context, listOf(PipelineJsonTextEdits.replacementEdit(range, original, updated)), "添加 Pipeline next")
    }

    private fun createReferenceCard(
        index: Int,
        marker: String,
        reference: PipelineReference,
        states: List<String>,
        fieldName: String?,
        referenceCount: Int,
        sourceFile: VirtualFile = file,
        ownerField: PipelineFieldLocation? = null,
        references: List<PipelineReference> = emptyList(),
        targetRecognitionSummary: String? = null,
    ): JComponent {
        val card = object : JPanel() {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            layout = VerticalLayout(0)
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(3, 6)
            alignmentX = LEFT_ALIGNMENT
        }
        val condition = PipelineConditionSummary.flowCandidateCondition(reference, targetRecognitionSummary)
        val branch = PipelineConditionSummary.flowBranch(index, fieldName ?: "next", condition)
        val prefix = if (reference.isJumpBack) "[JumpBack] " else ""
        val binding = if (reference.isAnchor) " · 运行时绑定" else ""
        card.add(
            createNavigationLabel(
                "${index + 1}. $prefix$branch → ${reference.targetName}$binding",
            ) { navigateToReference(reference) }.apply {
                toolTipText = PipelineFlowTooltip.render(
                    reference.target?.recognitionDetails.orEmpty(), getFontMetrics(UIUtil.getLabelFont()),
                    jumpBack = reference.isJumpBack, fieldName = fieldName ?: "next",
                    anchorHint = states.joinToString(" · "), sourcePath = reference.target?.file?.let(::displayPath))
            },
        )
        if (ownerField != null && !ownerField.isDerived && (fieldName == "next" || fieldName == "on_error")) {
            val fieldLabel = referenceFieldLabel(fieldName)
            val targetFile = ownerField.sourceFile ?: sourceFile
            val editable = targetFile.isWritable && targetFile !in currentModel.generatedFiles
            val actions = DefaultActionGroup()
            fun command(text: String, icon: javax.swing.Icon, enabled: Boolean = editable, execute: () -> Unit) {
                actions.add(object : DumbAwareAction(text, text, icon) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(event: AnActionEvent) { event.presentation.isEnabled = enabled }
                    override fun actionPerformed(event: AnActionEvent) = execute()
                })
            }
            fun flag(text: String, icon: javax.swing.Icon, selected: Boolean, execute: (Boolean) -> Unit) {
                actions.add(object : com.intellij.openapi.actionSystem.ToggleAction(text, text, icon), com.intellij.openapi.project.DumbAware {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(event: AnActionEvent) { super.update(event); event.presentation.isEnabled = editable }
                    override fun isSelected(event: AnActionEvent) = selected
                    override fun setSelected(event: AnActionEvent, state: Boolean) = execute(state)
                })
            }
            command("修改引用", com.intellij.icons.AllIcons.Actions.Edit) { editReferenceName(reference, targetFile) }
            run {
                flag("JumpBack · 执行后重试父 next", com.intellij.icons.AllIcons.Actions.Back, reference.isJumpBack) {
                    toggleReferenceMarker(reference, "[JumpBack]", it, targetFile)
                }
                flag("Anchor · 使用运行时绑定", com.intellij.icons.AllIcons.General.Pin_tab, reference.isAnchor) {
                    toggleReferenceMarker(reference, "[Anchor]", it, targetFile)
                }
            }
            command("将此${fieldLabel}前移", com.intellij.icons.AllIcons.Actions.MoveUp, index > 0 && editable) {
                moveReference(targetFile, references, fieldName, index, -1)
            }
            command("将此${fieldLabel}后移", com.intellij.icons.AllIcons.Actions.MoveDown, index < referenceCount - 1 && editable) {
                moveReference(targetFile, references, fieldName, index, 1)
            }
            command("删除此$fieldLabel", com.intellij.icons.AllIcons.General.Remove) {
                removeReference(targetFile, ownerField, reference, fieldName)
            }
            val toolbar = ActionManager.getInstance().createActionToolbar("MaaPipeline.Next", actions, true)
            toolbar.targetComponent = card
            card.add(toolbar.component)
        }
        return card
    }

    private fun referenceFieldLabel(fieldName: String?): String = when (fieldName) {
        "on_error" -> "on_error"
        "next" -> "next"
        else -> "引用"
    }

    private fun pipelinePath(file: com.intellij.openapi.vfs.VirtualFile): String {
        val normalizedPath = file.path.replace('\\', '/')
        return normalizedPath.substringAfter("/assets/resource/pipeline/", file.name)
    }

    private fun displayPath(file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/')
        val normalizedPath = file.path.replace('\\', '/')
        return if (basePath != null && normalizedPath.startsWith("$basePath/")) {
            normalizedPath.removePrefix("$basePath/")
        } else {
            normalizedPath
        }
    }

    private fun addNodeDiagnostics(location: PipelineNodeLocation) {
        val entries = currentModel.pipelineEntries.filter { it.entryName == location.name }
        val diagnostics = currentModel.diagnostics.filter { diagnostic ->
            diagnostic.file == (location.sourceFile ?: file) &&
                diagnostic.range.startOffset >= location.propertyRange.startOffset &&
                diagnostic.range.endOffset <= location.propertyRange.endOffset
        }
        if (entries.isEmpty() && diagnostics.isEmpty()) return
        detailsPanel.add(createSectionLabel("项目诊断"))
        entries.forEach { entry ->
            detailsPanel.add(
                createNavigationLabel("任务入口：${displayPath(entry.file)} → ${entry.entryName}") {
                    navigateToFileRange(entry.file, entry.range)
                },
            )
        }
        diagnostics.forEach { diagnostic ->
            val locationText = "${displayPath(diagnostic.file)}:${diagnostic.lineNumber ?: "?"}"
            val severity = if (diagnostic.severity == PipelineDiagnosticSeverity.ERROR) "错误" else "警告"
            detailsPanel.add(
                createNavigationLabel(
                    "$severity：${diagnostic.message}（${diagnostic.kind.label} → ${diagnostic.targetName}，$locationText；建议：${diagnostic.suggestion}）",
                ) {
                    navigateToFileRange(diagnostic.file, diagnostic.range)
                },
            )
        }
    }

    private fun addTaskOverrideSummary(location: PipelineNodeLocation) {
        val resources = currentModel.resourceOverrides.filter { it.targetName == location.name }
        if (resources.isNotEmpty()) {
            detailsPanel.add(createSectionLabel("控制器资源覆盖").also { overrideDetailsHeader = it })
            resources.forEach { patch ->
                detailsPanel.add(createNavigationLabel("${patch.contextPath.removePrefix("资源 · ")} · ${patch.fields.joinToString { it.name }}") {
                    navigateToFileRange(patch.file, patch.range)
                }.apply { toolTipText = patch.file.path })
            }
        }
        val temporary = currentOverrides().filter { it.contextPath == "临时覆盖" && it.targetName == location.name && overrideToggle.isSelected }
        if (temporary.isNotEmpty()) {
            detailsPanel.add(createSectionLabel("临时覆盖 · 最后应用").also { overrideDetailsHeader = it })
            temporary.forEach { patch ->
                detailsPanel.add(createNavigationLabel("编辑临时覆盖…") { editTemporaryOverrides() })
                patch.fields.forEach { addDetailText("${it.name}：${it.summary}") }
            }
        }
        val loggedMode = traceOverrides() != null && overrideToggle.isSelected
        if (loggedMode) {
            val logged = currentOverrides().filter { it.targetName == location.name && it.logTaskId != null }
            detailsPanel.add(createSectionLabel("日志启动覆盖 · 只读").also { overrideDetailsHeader = it })
            if (logged.isEmpty()) addDetailText("该次任务启动时未覆盖此节点，使用节点定义及资源默认值。")
            logged.forEach { addOverrideSource(location, it) }
        }
        val entry = selectedEntry ?: return
        val possible = PipelineTaskContext.possibleOverrides(entry, currentModel.pipelineOverrides)
            .filter { it.targetName == location.name }
        val overrides = currentOverrides().filter { it.targetName == location.name && overrideToggle.isSelected && it.contextPath != "临时覆盖" }
        val inactive = possible.filterNot { it in overrides }
        if (!loggedMode && overrides.isEmpty()) {
            detailsPanel.add(createSectionLabel("任务覆盖").also { overrideDetailsHeader = it })
            detailsPanel.add(createOverrideAddButton(location, null))
        } else if (!loggedMode) {
            detailsPanel.add(createSectionLabel("覆盖来源 · 按生效顺序").also { overrideDetailsHeader = it })
            detailsPanel.add(
                createNavigationLabel("任务：${displayPath(entry.file)} → ${entry.entryName}") {
                    navigateToFileRange(entry.file, entry.range)
                },
            )
            overrides.forEach { addOverrideSource(location, it) }
        }
        if (inactive.isNotEmpty()) {
            val content = JPanel(VerticalLayout(0)).apply { isOpaque = false }
            // 用完整覆盖值分组，不能用已截断的展示摘要判断两种配置是否相同。
            val groups = inactive.groupBy { runCatching { PipelineJsonc.parse(it.rawJson) }.getOrNull() ?: it.rawJson }
            groups.values.forEach { patches ->
                val group = JPanel(VerticalLayout(0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(4)
                }
                patches.first().fields.forEach { field ->
                    group.add(PipelineWrappingLabel("${field.name}：${overrideFieldValue(patches.first(), field)}"))
                }
                val sources = JPanel(VerticalLayout(0)).apply { isOpaque = false }
                patches.forEach { patch ->
                    sources.add(createNavigationLabel(PipelineTaskContext.overrideCondition(entry, patch)) {
                        navigateToFileRange(patch.file, patch.range)
                    })
                }
                group.add(if (patches.size > 1) pipelineCollapsedSection("适用条件（${patches.size}）", sources) else sources)
                content.add(group)
            }
            val title = if (loggedMode) "任务可选配置 · 未应用" else "其它可选覆盖"
            detailsPanel.add(pipelineCollapsedSection("$title（${inactive.size}）", content))
        }
    }

    private fun addOverrideSource(location: PipelineNodeLocation, override: PipelineOverrideLocation) {
        detailsPanel.add(createNavigationLabel("来源：${overrideSourceName(override)} · ${override.file.name}") {
            navigateToFileRange(override.file, override.range)
        }.apply { toolTipText = override.file.path })
        if (override.logTaskId != null) {
            addDetailText("task_id=${override.logTaskId} · 原日志第 ${override.logLineNumber} 行；按启动时的覆盖顺序合并。")
            override.fields.forEach { field ->
                detailsPanel.add(PipelineWrappingLabel("${field.name}：${overrideFieldValue(override, field)}").apply {
                    toolTipText = PipelinePropertyCatalog.help(field.name)
                })
            }
            return
        }
        if (override.fields.isEmpty()) {
            detailsPanel.add(PipelineWrappingLabel("当前任务存在覆盖对象，但没有可展示的字段").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
            detailsPanel.add(createOverrideAddButton(location, override))
            return
        }
        override.fields.forEach { field ->
            val defaultSummary = defaultFieldSummary(location, field.name)
            detailsPanel.add(JBLabel("字段：${field.name}").apply {
                font = font.deriveFont(Font.BOLD)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            detailsPanel.add(PipelineWrappingLabel("默认值：$defaultSummary"))
            val row = object : JPanel(BorderLayout()) {
                override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
            }.apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }
            row.add(
                createNavigationLabel("此处覆盖：${overrideFieldValue(override, field)}") {
                    navigateToFileRange(override.file, field.range)
                },
                BorderLayout.CENTER,
            )
            row.add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(JButton("编辑").apply {
                    isFocusable = false
                    isEnabled = override.file.isWritable
                    addActionListener { editOverrideField(override, field) }
                })
                add(JButton("删除").apply {
                    isFocusable = false
                    isEnabled = override.file.isWritable
                    addActionListener { removeOverrideField(override, field) }
                })
            }, BorderLayout.EAST)
            detailsPanel.add(row)
        }
        detailsPanel.add(createOverrideAddButton(location, override))
    }

    private fun createOverrideAddButton(
        location: PipelineNodeLocation,
        override: PipelineOverrideLocation?,
    ): JButton = JButton(if (override == null) "新增节点覆盖" else "新增覆盖字段").apply {
            isFocusable = false
            isEnabled = (override?.file ?: selectedEntry?.file)?.isWritable == true
            toolTipText = "在当前任务的 pipeline_override 节点下新增字段"
            addActionListener { addOverrideField(location, override) }
        }

    private fun addOverrideField(location: PipelineNodeLocation, override: PipelineOverrideLocation?) {
        val entry = selectedEntry ?: return
        val taskFile = override?.file ?: entry.file
        val taskDocument = documentFor(taskFile) ?: return
        val expectedVersion = override?.version ?: taskDocument.modificationStamp
        if (!ensureOverrideEditable(taskFile, expectedVersion)) return
        val snapshot = PipelineEditSnapshot(taskDocument.modificationStamp, taskDocument.text)
        val fieldName = Messages.showInputDialog(
            project,
            "输入要新增的覆盖字段名：",
            "新增 pipeline_override 字段",
            null,
            "next",
            null,
        )?.trim().orEmpty()
        if (fieldName.isEmpty()) return
        if (override?.fields?.any { it.name == fieldName } == true) {
            Messages.showWarningDialog(project, "该节点已经存在覆盖字段：$fieldName", "无法新增覆盖字段")
            return
        }
        val initialValue = defaultOverrideJsonValue(fieldName, location)
        val dialog = PipelineJsonFieldDialog(project, fieldName, initialValue)
        if (!dialog.showAndGet()) return
        val replacementValue = dialog.value
        if (!ensureFileSnapshot(taskFile, snapshot)) return
        PsiDocumentManager.getInstance(project).commitDocument(taskDocument)
        val insertionPlan = ReadAction.compute<OverrideInsertionPlan?, RuntimeException> {
            val jsonFile = PsiManager.getInstance(project).findFile(taskFile) as? JsonFile ?: return@compute null
            val owner = taskContextObject(jsonFile, override?.contextPath ?: entry.contextPath) ?: return@compute null
            val pipelineOverrideProperty = owner.findProperty("pipeline_override")
            val targetProperty = (pipelineOverrideProperty?.value as? JsonObject)
                ?.propertyList
                ?.firstOrNull { it.name == location.name }
            when {
                targetProperty?.value is JsonObject -> {
                    (override?.valueRange ?: (targetProperty.value as JsonObject).textRange)?.let {
                        OverrideInsertionPlan(it, fieldName, replacementValue)
                    }
                }
                pipelineOverrideProperty?.value is JsonObject -> {
                    (pipelineOverrideProperty.value as JsonObject).textRange?.let {
                        OverrideInsertionPlan(
                            it,
                            location.name,
                            "{\n  ${quoteJsonString(fieldName)}: $replacementValue\n}",
                        )
                    }
                }
                else -> {
                    owner.textRange?.let {
                        OverrideInsertionPlan(
                            it,
                            "pipeline_override",
                            "{\n  ${quoteJsonString(location.name)}: {\n    ${quoteJsonString(fieldName)}: $replacementValue\n  }\n}",
                        )
                    }
                }
            }
        } ?: return
        val insertion = insertionPlan.range to jsonObjectPropertyInsertion(
            taskDocument,
            insertionPlan.range,
            insertionPlan.name,
            insertionPlan.value,
        )
        val (range, replacement) = insertion
        val oldText = taskDocument.getText(range)
        if (!confirmDocumentChange("新增 pipeline_override 字段", oldText, replacement, document = taskDocument,
                edits = listOf(PipelineTextEdit(range, replacement)))) return
        if (!ensureOverrideEditable(taskFile, expectedVersion)) return
        WriteCommandAction.runWriteCommandAction(
            project,
            "新增 pipeline_override 字段",
            "MaaPipeline JSON Execution Lens",
            Runnable { taskDocument.replaceString(range.startOffset, range.endOffset, replacement) },
        )
    }

    private fun editOverrideField(override: PipelineOverrideLocation, field: PipelineFieldLocation) {
        val document = documentFor(override.file) ?: return
        if (!ensureOverrideEditable(override.file, override.version)) return
        val snapshot = PipelineEditSnapshot(document.modificationStamp, document.text)
        val range = field.valueRange
        if (range.endOffset > document.textLength) return
        val initialText = document.getText(range)
        val dialog = PipelineJsonFieldDialog(project, field.name, initialText)
        if (!dialog.showAndGet()) return
        val replacement = PipelineJsonTextEdits.alignValue(snapshot.text, range.startOffset, dialog.value)
        if (replacement == initialText) return
        val edits = listOf(PipelineTextEdit(range, replacement))
        if (!ensureFileSnapshot(override.file, snapshot, edits)) return
        if (!confirmDocumentChange("编辑任务覆盖", initialText, replacement, document = document, edits = edits)) return
        if (!ensureOverrideEditable(override.file, override.version)) return
        WriteCommandAction.runWriteCommandAction(
            project,
            "编辑任务覆盖",
            "MaaPipeline JSON Execution Lens",
            Runnable { document.replaceString(range.startOffset, range.endOffset, replacement) },
        )
    }

    private fun removeOverrideField(override: PipelineOverrideLocation, field: PipelineFieldLocation) {
        val document = documentFor(override.file) ?: return
        if (!ensureOverrideEditable(override.file, override.version)) return
        val edits = PipelineJsonTextEdits.deletionEdits(document.text, field.range)
        val oldText = document.getText(field.range)
        if (!confirmDocumentChange("删除任务覆盖字段", oldText, "（删除）", document = document, edits = edits)) return
        if (!ensureOverrideEditable(override.file, override.version)) return
        WriteCommandAction.runWriteCommandAction(
            project,
            "删除任务覆盖字段",
            "MaaPipeline JSON Execution Lens",
            Runnable { edits.forEach { document.replaceString(it.range.startOffset, it.range.endOffset, it.replacement) } },
        )
    }

    private fun defaultOverrideJsonValue(fieldName: String, location: PipelineNodeLocation): String = when (fieldName) {
        "next" -> "[]"
        "on_error" -> "[]"
        "recognition", "action" -> "{}"
        "desc" -> "\"\""
        "timeout", "rate_limit", "max_hit" -> "0"
        "anchor" -> "[]"
        else -> "null"
    }

    private fun findJsonProperty(file: JsonFile, name: String): JsonProperty? {
        fun visit(element: PsiElement): JsonProperty? {
            if (element is JsonProperty && element.name == name) return element
            return element.children.asSequence().mapNotNull(::visit).firstOrNull()
        }
        return visit(file)
    }

    private data class OverrideInsertionPlan(
        val range: TextRange,
        val name: String,
        val value: String,
    )

    private fun jsonObjectPropertyInsertion(
        document: Document,
        objectRange: TextRange,
        name: String,
        value: String,
    ): String {
        val objectText = document.getText(objectRange)
        val close = objectText.lastIndexOf('}')
        if (close <= 0) return objectText
        val lineStart = document.getLineStartOffset(document.getLineNumber(objectRange.startOffset))
        val baseIndent = document.getText(TextRange(lineStart, objectRange.startOffset))
            .takeWhile { it == ' ' || it == '\t' }
        val childIndent = Regex("(?m)\\n([ \\t]+)\\S").find(objectText.substring(0, close))
            ?.groupValues?.getOrNull(1)
            ?: baseIndent + "  "
        return PipelineJsonTextEdits.appendMember(objectText, "${quoteJsonString(name)}: $value", childIndent)
    }

    private fun ensureOverrideEditable(file: VirtualFile, expectedVersion: Long?): Boolean {
        if (!file.isWritable) {
            Messages.showWarningDialog(project, "任务文件不可写：${displayPath(file)}", "无法编辑任务覆盖")
            return false
        }
        val document = documentFor(file) ?: return false
        if (hasGeneratedHeader(document.text)) {
            Messages.showWarningDialog(project, "任务文件由生成器维护，插件不会直接修改它。", "无法编辑任务覆盖")
            return false
        }
        if (expectedVersion != null && expectedVersion >= 0 && document.modificationStamp != expectedVersion) {
            Messages.showWarningDialog(
                project,
                "任务文件在索引后发生了变化，插件不会覆盖外部修改。请重新选择任务后再编辑。\n\n文件：${displayPath(file)}",
                "任务覆盖索引已过期",
            )
            return false
        }
        return true
    }

    private fun defaultFieldSummary(location: PipelineNodeLocation, fieldName: String): String = when (fieldName) {
        "desc" -> location.description ?: "（未设置）"
        "recognition" -> location.recognition?.summary ?: "（未设置）"
        "action" -> location.action?.summary ?: "（未设置）"
        "anchor" -> location.anchor?.summary ?: "（未设置）"
        "next" -> referenceSummary(location.next)
        "on_error" -> referenceSummary(location.onError)
        "timeout" -> location.executionPolicy.timeout?.summary ?: "（未设置）"
        "rate_limit" -> location.executionPolicy.rateLimit?.summary ?: "（未设置）"
        "max_hit" -> location.executionPolicy.maxHit?.summary ?: "（未设置）"
        else -> "（当前视图未索引）"
    }

    private fun referenceSummary(references: List<PipelineReference>): String = references
        .joinToString("、") { it.displayName }
        .ifEmpty { "（未设置）" }

    private fun effectiveNode(location: PipelineNodeLocation): PipelineNodeLocation {
        if (!overrideToggle.isSelected) return location
        return effectiveNodes[logicNodeKey(location.name, location.sourceFile ?: file)] ?: location
    }

    private fun taskContextObject(json: JsonFile, context: String): JsonObject? {
        var value: com.intellij.json.psi.JsonValue = json.topLevelValue ?: return null
        val pointer = context.substringAfter('#', context)
        for (part in pointer.split('/').drop(1)) {
            val key = part.replace("~1", "/").replace("~0", "~")
            value = when (val owner = value) {
                is JsonObject -> owner.findProperty(key)?.value
                is com.intellij.json.psi.JsonArray -> key.toIntOrNull()?.let { owner.valueList.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return value as? JsonObject
    }

    private fun reachableNodeNames(): Set<String>? {
        val root = currentRoot() ?: return null
        val graph = currentReadingGraph()
        val reachable = graph.reachable(root)
        return currentModel.nodes.filter { PipelineReadingGraph.key(it) in reachable }.mapTo(linkedSetOf()) { it.name }
    }

    private fun staticPathTo(target: PipelineNodeLocation): List<String>? {
        val root = currentRoot() ?: return null
        return currentReadingGraph().path(root, target)?.map { (label, node) ->
            if (label.isEmpty()) nodePathLabel(node) else "$label → ${nodePathLabel(node)}"
        }
    }

    private fun currentReadingGraph(): PipelineReadingGraph = readingGraph ?: run {
        val effective = effectiveNodes
        PipelineReadingGraph(currentModel.projectNodes) { effective[PipelineReadingGraph.key(it)] ?: it }
            .also { readingGraph = it }
    }

    private fun nodePathLabel(location: PipelineNodeLocation): String {
        val sourceFile = location.sourceFile ?: file
        return if (sourceFile == file) {
            location.name
        } else {
            "${location.name} [${sourceFile.name}]"
        }
    }

    private fun addRelationshipOverview(location: PipelineNodeLocation) {
        val references = location.next + location.onError + location.embeddedReferences
        val cycleReferences = references.filter { it.isCycle }
        val sharedReferences = references.filter { it.isSharedTarget }
        val anchorReferences = references.filter { it.isAnchor }
        val crossFileReferences = references.filter { it.isCrossFile }
        if (cycleReferences.isEmpty() && sharedReferences.isEmpty() && anchorReferences.isEmpty() && crossFileReferences.isEmpty()) {
            return
        }
        detailsPanel.add(createSectionLabel("关系概览"))
        addRelationshipLine("循环边", cycleReferences)
        addRelationshipLine("共享目标", sharedReferences)
        addRelationshipLine("动态 Anchor", anchorReferences)
        addRelationshipLine("跨文件目标", crossFileReferences)
    }

    private fun addRelationshipLine(title: String, references: List<PipelineReference>) {
        if (references.isEmpty()) return
        val summary = references.joinToString("、") { it.displayName }
        detailsPanel.add(
            createNavigationLabel("$title：$summary") {
                navigateToReference(references.first())
            },
        )
    }

    private fun addPathAnalysis(path: List<String>) {
        val section = object : JPanel(BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT }
        val body = JPanel().apply {
            isOpaque = false
            layout = VerticalLayout(0)
            path.forEachIndexed { index, step -> add(PipelineWrappingLabel("${index + 1}. $step")) }
            isVisible = path.size <= 4
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(createSectionLabel("路径分析 · ${path.size} 步"), BorderLayout.CENTER)
            add(createFoldMarker(body.isVisible, "路径分析") { expanded ->
                body.isVisible = expanded
                detailsPanel.revalidate()
                detailsPanel.repaint()
            }, BorderLayout.EAST)
        }
        section.add(header, BorderLayout.NORTH)
        section.add(body, BorderLayout.CENTER)
        detailsPanel.add(section)
    }

    private fun editReferenceName(reference: PipelineReference, sourceFile: VirtualFile) {
        val context = detailEditContext(sourceFile) ?: return
        val initial = (if (reference.isJumpBack) "[JumpBack]" else "") + (if (reference.isAnchor) "[Anchor]" else "") + reference.targetName
        val dialog = PipelineReferenceDialog(project, currentModel.projectNodes, initial)
        if (!dialog.showAndGet()) return
        val text = dialog.value
        val edit = ReadAction.compute<PipelineTextEdit?, RuntimeException> {
            PipelineReferenceEdits.edit(project, context.snapshot.text, reference, text)
        } ?: return
        applyDetailEdits(context, listOf(edit), "修改 Pipeline 引用")
    }

    private fun addTraceSummary(location: PipelineNodeLocation) {
        traceDetailsPanel = null
        if (currentTrace == null) return
        val panel = JPanel().apply {
            layout = VerticalLayout(0)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        val update = Runnable { renderTraceSummary(location, panel) }
        panel.putClientProperty("pipeline.playback.refresh", update)
        traceDetailsPanel = panel
        detailsPanel.add(panel)
        update.run()
    }

    private fun renderTraceSummary(location: PipelineNodeLocation, panel: JPanel) {
        panel.removeAll()
        val cursor = playbackToolbar.session ?: return
        val source = cursor.source as? PipelineLogPlaybackSource ?: return
        val events = source.nodeEventsThrough(location.name, cursor.eventIndex)
        val rounds = source.roundsThrough(location.name, cursor.eventIndex)
        val calls = source.callsThrough(location.name, cursor.eventIndex)
        panel.add(createSectionLabel("运行轨迹 · 截至当前位置"))
        if (events.isEmpty() && rounds.isEmpty() && calls.isEmpty()) panel.add(JBLabel("尚未观察到此节点"))
        if (traceSourceChanged()) panel.add(JBLabel("⚠ 源码已改变，请按历史运行记录理解这些结果。"))
        rounds.takeLast(6).forEach { round ->
            panel.add(createNavigationLabel("候选观察 · 日志 ${round.started.lineNumber}") {
                openTraceFile(round.started.lineNumber)
            })
            round.started.candidates.forEach { candidate ->
                panel.add(PipelineWrappingLabel("${candidate.name}：${round.outcome(candidate.name).label}").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyLeft(8)
                })
            }
        }
        calls.takeLast(6).forEach { call ->
            val returned = call.resumed?.let { "推断返回 · 日志 ${it.lineNumber}" } ?: "未观察到返回"
            panel.add(createNavigationLabel("↩ ${call.caller} → ${call.target}（推断；$returned）") {
                openTraceFile(call.entered.lineNumber)
            })
        }
        events.takeLast(8).forEach { event ->
            val task = event.entryName?.let { "任务 $it" }.orEmpty()
            val suffix = if (task.isEmpty()) "" else "，$task"
            panel.add(
                createNavigationLabel(
                    "${event.kind.label}：${event.status.label}$suffix（日志第 ${event.lineNumber} 行）",
                ) { openTraceFile(event.lineNumber) },
            )
            if (event.candidates.isNotEmpty()) {
                val candidates = event.candidates.joinToString("、") { candidate ->
                    buildString {
                        if (candidate.isJumpBack) append("↩")
                        if (candidate.isAnchor) append("⚓")
                        append(candidate.name)
                    }
                }
                panel.add(PipelineWrappingLabel("候选：$candidates").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyLeft(8)
                })
            }
            event.anchorName?.let { anchor ->
                panel.add(PipelineWrappingLabel("运行时 Anchor：$anchor").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyLeft(8)
                })
            }
        }
        panel.revalidate()
        panel.repaint()
    }

    private fun syncPlaybackSession() {
        simulationCursor?.let { playbackToolbar.bind(it, true); return }
        val trace = currentTrace
        val entry = selectedEntry?.entryName ?: currentRoot()?.name
        val cursor = if (trace != null && entry != null) playbackCursors.getOrPut(entry) {
            PipelinePlaybackCursor(trace.playbackForEntry(entry))
        } else null
        playbackToolbar.bind(cursor, trace != null)
    }

    private fun traceNodeState(name: String?): PipelineTraceEvent? {
        val cursor = playbackToolbar.session ?: return null
        return name?.let { (cursor.source as? PipelineLogPlaybackSource)?.nodeState(it, cursor.eventIndex) }
    }

    private fun refreshPlaybackComponents(component: Component) {
        if (component is JComponent) (component.getClientProperty("pipeline.playback.refresh") as? Runnable)?.run()
        if (component is java.awt.Container) component.components.forEach(::refreshPlaybackComponents)
    }

    private fun playbackPositionChanged(forceFollow: Boolean) {
        val cursor = simulationCursor
        val completed = simulation?.frames?.take((cursor?.eventIndex ?: -1) + 1).orEmpty()
            .filter { it.pending.isEmpty() && (it.text.startsWith("subtask 结束") || it.text.startsWith("子任务失败")) }
            .map { logicNodeKey(it.node.name, it.node.sourceFile ?: file) }.toSet()
        if (completed != completedSubtasks) {
            completedSubtasks = completed
            expandedSubtasks.clear()
            renderLogicView(currentRoot())
        }
        refreshPlaybackComponents(logicPanel)
        logicPanel.revalidate()
        traceDetailsPanel?.takeIf { it.parent != null }?.let(::refreshPlaybackComponents)
        nodeList.repaint()
        val node = playbackToolbar.session?.frame?.nodeName?.let { currentReadingGraph().uniqueNode(it) }
        logicPanel.followPlaybackRow(null)
        if (node != null) {
            val previousDetail = selectedDetail()
            inspectNode(node)
            if (simulation != null && previousDetail == node) renderSelectedNode()
            val key = logicNodeKey(node.name, node.sourceFile ?: file)
            val unfold = simulation != null && (key in collapsedLogicNodes || logicOccurrences[key].orEmpty().any {
                it.endsWith(":header") && it.removeSuffix(":header") in collapsedLogicNodes
            })
            if (simulation != null || forceFollow || playbackToolbar.follow) {
                if (key !in logicRowByNode || unfold) {
                    val path = currentRoot()?.let { currentReadingGraph().path(it, node) }.orEmpty()
                    if (path.isNotEmpty()) {
                        playbackRevealPath = path.map { (_, value) -> logicNodeKey(value.name, value.sourceFile ?: file) }
                        try { renderLogicView(currentRoot()) } finally { playbackRevealPath = emptyList() }
                    }
                }
                if (forceFollow || playbackToolbar.follow) logicPanel.followPlaybackRow(logicRowByNode[key])
            }
        }
        updatePlaybackMarker()
    }

    private fun addSimulationSummary(location: PipelineNodeLocation) {
        val cursor = simulationCursor ?: return
        val frame = simulation?.frames?.getOrNull(cursor.eventIndex) ?: return
        detailsPanel.add(createSectionLabel("模拟状态 · ${(environmentSelector.selectedItem as? PipelineEnvironment)?.label.orEmpty()}"))
        addDetailText(frame.text)
        addDetailText("Anchor：" + frame.anchors.entries.joinToString { "${it.key} → ${it.value}" }.ifEmpty { "未绑定" })
        if (frame.node.name != location.name) return
        addDetailText("识别框：" + frame.results[location.name]?.let { "$it（注释示意）" }.orEmpty().ifEmpty { "未知，未进行真实识别" })
        frame.targets.forEach { (key, value) -> addDetailText("$key：${value ?: "未知，需要识别结果或 @roi 注释"}") }
    }

    private fun updatePlaybackMarker() {
        val node = playbackToolbar.session?.frame?.nodeName?.let { currentReadingGraph().uniqueNode(it) }
        logicPanel.setPlaybackRow(node?.let { logicRowByNode[logicNodeKey(it.name, it.sourceFile ?: file)] })
    }

    private fun withPlaybackBadge(
        heading: JComponent,
        node: PipelineNodeLocation?,
        reference: PipelineReference?,
        parentName: String?,
    ): JComponent {
        if (currentTrace == null) return heading
        val badge = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyLeft(8) }
        val update = Runnable {
            val cursor = playbackToolbar.session
            val source = cursor?.source as? PipelineLogPlaybackSource
            val outcome = if (source != null && parentName != null && reference != null) {
                listOf(reference.targetName, reference.displayName).asSequence()
                    .map { source.outcome(parentName, it, cursor.eventIndex) }
                    .firstOrNull { it != PipelineTraceCandidateOutcome.UNOBSERVED }
            } else null
            val status = outcome?.let {
                when (it) {
                    PipelineTraceCandidateOutcome.SELECTED -> "已选择"
                    PipelineTraceCandidateOutcome.MISSED -> "未命中"
                    PipelineTraceCandidateOutcome.MATCHED -> "识别命中"
                    PipelineTraceCandidateOutcome.SKIPPED -> "未选择"
                    PipelineTraceCandidateOutcome.UNOBSERVED -> ""
                }
            } ?: if (parentName != null && reference != null) "" else {
                traceNodeState(node?.name)?.let { "节点${it.status.label}" }.orEmpty()
            }
            val current = node?.name != null && cursor?.frame?.nodeName == node.name
            badge.text = listOfNotNull("当前".takeIf { current }, status.takeIf { it.isNotEmpty() }).joinToString(" · ")
            badge.toolTipText = "截至回放当前位置的日志观察"
        }
        badge.putClientProperty("pipeline.playback.refresh", update)
        update.run()
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(heading, BorderLayout.CENTER)
            add(badge, BorderLayout.EAST)
        }
    }

    private fun loadTrace() {
        playbackToolbar.pause()
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("选择 MaaFramework 运行日志")
            .withDescription("选择 MaaFramework maafw.log 或导出的同类日志文件")
        val defaultFile = project.baseDir?.findFileByRelativePath("debug/maafw.log")
        val selected = FileChooser.chooseFile(descriptor, project, defaultFile) ?: return
        val generation = ++traceLoadGeneration
        val sourceFiles = (currentModel.indexedVersions.keys + currentModel.pipelineOverrides.map { it.file } +
            currentModel.pipelineEntries.map { it.file } + file).distinct()
        traceButton.isEnabled = false
        object : Task.Backgroundable(project, "读取 MaaFramework 运行日志", true) {
            private var parsedTrace: PipelineExecutionTrace? = null
            private var sourceVersions: Map<VirtualFile, Long> = emptyMap()

            override fun run(indicator: ProgressIndicator) {
                sourceVersions = ReadAction.compute<Map<VirtualFile, Long>, RuntimeException> {
                    sourceFiles.associateWith { source ->
                        FileDocumentManager.getInstance().getDocument(source)?.modificationStamp ?: source.modificationStamp
                    }
                }
                val trace = PipelineTraceParser.parse(selected) { indicator.checkCanceled() }
                trace.replayForEntry(null)
                trace.taskEntries.values.toSet().forEach { entry ->
                    indicator.checkCanceled()
                    trace.replayForEntry(entry)
                    trace.nodeStatesForEntry(entry)
                    trace.playbackForEntry(entry)
                }
                parsedTrace = trace
            }

            override fun onSuccess() {
                if (disposed || generation != traceLoadGeneration) return
                traceButton.isEnabled = true
                currentTrace = parsedTrace
                playbackCursors.clear()
                traceSourceVersions = sourceVersions
                traceSourceVersionState = null
                nodeList.repaint()
                updateTaskControls()
                rebuildEffectiveContext()
            }

            override fun onCancel() {
                if (!disposed && generation == traceLoadGeneration) traceButton.isEnabled = true
            }

            override fun onThrowable(error: Throwable) {
                if (disposed || generation != traceLoadGeneration) return
                traceButton.isEnabled = true
                Messages.showErrorDialog(project, "无法读取运行日志：${error.message}", "加载运行日志失败")
            }
        }.queue()
    }

    private fun openTraceFile(lineNumber: Int) {
        val trace = currentTrace ?: return
        OpenFileDescriptor(project, trace.file, lineNumber.coerceAtLeast(1) - 1, 0).navigate(true)
    }

    private fun ensureEditable(): Boolean {
        if (!file.isWritable || currentModel.isGenerated || hasGeneratedHeader(editor.document.text)) {
            Messages.showWarningDialog(project, "当前文件由生成器维护，插件不会直接修改它。", "无法编辑 Pipeline")
            return false
        }
        if (currentModel.hasSyntaxErrors) {
            Messages.showWarningDialog(project, "当前 JSON 有语法错误，请先在源码中修复，再使用结构化编辑。", "无法编辑 Pipeline")
            return false
        }
        return ensureSnapshot(PipelineEditSnapshot(currentModel.version, currentModel.indexedText))
    }

    private fun captureEditSnapshot(): PipelineEditSnapshot =
        PipelineEditSnapshot(editor.document.modificationStamp, editor.document.text)

    private fun ensureSnapshot(snapshot: PipelineEditSnapshot, edits: List<PipelineTextEdit> = emptyList()): Boolean {
        return ensureDocumentSnapshot(editor.document, snapshot, edits)
    }

    private fun ensureDocumentSnapshot(
        document: Document,
        snapshot: PipelineEditSnapshot,
        edits: List<PipelineTextEdit> = emptyList(),
    ): Boolean {
        if (snapshot.matches(document.modificationStamp, document.text)) return true
        val current = PipelineEditSnapshot(document.modificationStamp, document.text)
        val targetFile = ReadAction.compute<VirtualFile?, RuntimeException> {
            FileDocumentManager.getInstance().getFile(document)
        }
        PipelineConflictDialog(
            project,
            snapshot.text,
            document.text,
            PipelineJsonTextEdits.applyEdits(snapshot.text, edits),
            onApplyMerged = if (edits.isEmpty() || targetFile == null) null else { merged ->
                applyMergedDocument(targetFile, document, current, merged)
            },
        ).show()
        refreshFromDocument(editor.document)
        return false
    }

    private fun applyMergedDocument(
        targetFile: VirtualFile,
        document: Document,
        snapshot: PipelineEditSnapshot,
        merged: String,
    ) {
        if (disposed || project.isDisposed || merged == snapshot.text) return
        val edit = PipelineJsonTextEdits.replacementEdit(TextRange(0, snapshot.text.length), snapshot.text, merged)
        if (!ensureFileSnapshot(targetFile, snapshot, listOf(edit))) return
        val valid = ReadAction.compute<Boolean, RuntimeException> {
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText("PipelineMerge.json", JsonFileType.INSTANCE, merged)
            (psiFile as? JsonFile)?.topLevelValue is JsonObject && !PsiTreeUtil.hasErrorElements(psiFile)
        }
        if (!valid) {
            Messages.showWarningDialog(project, "合并结果不是有效的 Pipeline JSON 对象，原文档未修改。请修正后重试。", "无法应用合并")
            return
        }
        if (!confirmDocumentChange("应用 Pipeline 合并", snapshot.text, merged, "文件：${displayPath(targetFile)}", document, listOf(edit))) return
        if (!ensureFileSnapshot(targetFile, snapshot, listOf(edit))) return
        WriteCommandAction.runWriteCommandAction(project, "合并 Pipeline 外部修改", "MaaPipeline JSON Execution Lens", Runnable {
            document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
        })
        refreshFromDocument(editor.document)
    }

    private data class DetailEditContext(val file: VirtualFile, val document: Document, val snapshot: PipelineEditSnapshot)

    private fun detailEditContext(sourceFile: VirtualFile): DetailEditContext? {
        val document = documentFor(sourceFile) ?: return null
        if (currentModel.indexedVersions[sourceFile] != document.modificationStamp) {
            refreshFromDocument(editor.document)
            Messages.showWarningDialog(project, "文件已更新，详情刷新后请重试。", "无法编辑 Pipeline")
            return null
        }
        val snapshot = PipelineEditSnapshot(document.modificationStamp, document.text)
        if (!ensureFileSnapshot(sourceFile, snapshot)) return null
        val hasErrors = ReadAction.compute<Boolean, RuntimeException> {
            PsiTreeUtil.hasErrorElements(PsiFileFactory.getInstance(project).createFileFromText(
                "PipelineEdit.json", JsonFileType.INSTANCE, snapshot.text))
        }
        if (hasErrors) {
            Messages.showWarningDialog(project, "JSON 有语法错误，请先在源码中修复。", "无法编辑 Pipeline")
            return null
        }
        return DetailEditContext(sourceFile, document, snapshot)
    }

    private fun applyDetailEdits(context: DetailEditContext, edits: List<PipelineTextEdit>, title: String) {
        if (edits.isEmpty() || !ensureFileSnapshot(context.file, context.snapshot, edits)) return
        if (edits.any { it.range.startOffset < 0 || it.range.endOffset > context.snapshot.text.length }) return
        val oldText = if (title.startsWith("添加")) "" else edits.joinToString("\n") { context.snapshot.text.substring(it.range.startOffset, it.range.endOffset) }
        val newText = edits.joinToString("\n") { it.replacement }
        if (!confirmDocumentChange(title, oldText, newText, document = context.document, edits = edits)) return
        if (!ensureFileSnapshot(context.file, context.snapshot, edits)) return
        WriteCommandAction.runWriteCommandAction(project, title, "MaaPipeline JSON Execution Lens", Runnable {
            edits.sortedByDescending { it.range.startOffset }.forEach {
                context.document.replaceString(it.range.startOffset, it.range.endOffset, it.replacement)
            }
        })
    }

    private fun editJsonField(field: PipelineFieldLocation, sourceFile: VirtualFile) {
        if (field.isDerived) return
        val context = detailEditContext(sourceFile) ?: return
        val range = field.valueRange
        if (range.startOffset < 0 || range.endOffset > context.snapshot.text.length) return
        val initialText = context.snapshot.text.substring(range.startOffset, range.endOffset)
        val dialog = PipelineJsonFieldDialog(project, field.name, initialText)
        if (!dialog.showAndGet()) return
        if (dialog.value.isEmpty() || dialog.value == initialText) return
        val replacement = PipelineJsonTextEdits.alignValue(context.snapshot.text, range.startOffset, dialog.value)
        applyDetailEdits(context, listOf(PipelineTextEdit(range, replacement)), "编辑 Pipeline JSON 字段")
    }

    private fun editOperation(node: PipelineNodeLocation, sourceFile: VirtualFile, isAction: Boolean) {
        val field = if (isAction) node.action else node.recognition
        if (field?.isDerived == true) return
        val context = detailEditContext(field?.sourceFile ?: sourceFile) ?: return
        val name = if (isAction) "action" else "recognition"
        val owner = ReadAction.compute<JsonObject?, RuntimeException> {
            val root = PipelineOperationEdits.objectValue(project, context.snapshot.text) ?: return@compute null
            if (field == null) root.findProperty(node.name)?.value as? JsonObject
            else PsiTreeUtil.collectElementsOfType(root, JsonObject::class.java).firstOrNull {
                it.findProperty(name)?.value?.textRange == field.valueRange
            }
        } ?: return
        val initial = ReadAction.compute<String, RuntimeException> { PipelineOperationEdits.fragment(owner, isAction) }
        val dialog = PipelineJsonFieldDialog(project, name, initial, isAction)
        if (!dialog.showAndGet()) return
        if (dialog.value == initial) return
        val edit = ReadAction.compute<PipelineTextEdit?, RuntimeException> {
            PipelineOperationEdits.operationEdit(project, owner, dialog.value, isAction)
        } ?: return
        applyDetailEdits(context, listOf(edit), "编辑 Pipeline ${if (isAction) "动作" else "条件"}")
    }

    private fun quoteJsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun toggleReferenceMarker(reference: PipelineReference, marker: String, enabled: Boolean, sourceFile: VirtualFile) {
        val context = detailEditContext(sourceFile) ?: return
        val initial = (if (reference.isJumpBack) "[JumpBack]" else "") + (if (reference.isAnchor) "[Anchor]" else "") + reference.targetName
        val updatedValue = if (enabled) {
            if (initial.contains(marker)) {
                initial
            } else {
                marker + initial
            }
        } else {
            removeLeadingMarker(initial, marker)
        }
        if (updatedValue == initial) return
        val edit = ReadAction.compute<PipelineTextEdit?, RuntimeException> {
            PipelineReferenceEdits.edit(project, context.snapshot.text, reference, updatedValue)
        } ?: return
        applyDetailEdits(context, listOf(edit), "编辑 Pipeline 引用标记")
    }

    private fun removeLeadingMarker(value: String, marker: String): String {
        var offset = 0
        while (offset < value.length && value[offset] == '[') {
            val end = value.indexOf(']', offset)
            if (end < 0) break
            val currentMarker = value.substring(offset, end + 1)
            if (currentMarker == marker) {
                return value.removeRange(offset, end + 1)
            }
            offset = end + 1
        }
        return value
    }

    private fun moveReference(sourceFile: VirtualFile, references: List<PipelineReference>, fieldName: String?, index: Int, direction: Int) {
        val targetIndex = index + direction
        if (targetIndex !in references.indices) return
        val context = detailEditContext(sourceFile) ?: return

        val currentRange = ReadAction.compute<TextRange, RuntimeException> { PipelineReferenceEdits.range(project, context.snapshot.text, references[index]) }
        val targetRange = ReadAction.compute<TextRange, RuntimeException> { PipelineReferenceEdits.range(project, context.snapshot.text, references[targetIndex]) }
        val lowerRange: TextRange
        val higherRange: TextRange
        if (currentRange.startOffset < targetRange.startOffset) {
            lowerRange = currentRange
            higherRange = targetRange
        } else {
            lowerRange = targetRange
            higherRange = currentRange
        }
        val lowerText = context.snapshot.text.substring(lowerRange.startOffset, lowerRange.endOffset)
        val higherText = context.snapshot.text.substring(higherRange.startOffset, higherRange.endOffset)
        val fieldLabel = referenceFieldLabel(fieldName)
        applyDetailEdits(context, listOf(PipelineTextEdit(lowerRange, higherText), PipelineTextEdit(higherRange, lowerText)),
            "调整 Pipeline $fieldLabel 顺序")
    }

    private fun removeReference(sourceFile: VirtualFile, field: PipelineFieldLocation, reference: PipelineReference, fieldName: String?) {
        val context = detailEditContext(sourceFile) ?: return
        val range = ReadAction.compute<TextRange, RuntimeException> { PipelineReferenceEdits.range(project, context.snapshot.text, reference) }
        val edits = if (field.valueRange == range) {
            listOf(PipelineTextEdit(range, "[]"))
        } else {
            PipelineJsonTextEdits.deletionEdits(context.snapshot.text, range)
        }
        val fieldLabel = referenceFieldLabel(fieldName)
        applyDetailEdits(context, edits, "删除 Pipeline $fieldLabel")
    }

    private fun addNode() {
        if (!ensureEditable()) return
        val snapshot = captureEditSnapshot()
        val dialog = PipelineNodeDialog(project)
        if (!dialog.showAndGet()) return
        if (!ensureSnapshot(snapshot)) return
        if (!ensureEditable()) return
        val nodeName = dialog.nodeName
        if (currentModel.nodes.any { it.name == nodeName }) {
            Messages.showWarningDialog(project, "当前文件已经存在节点：$nodeName", "无法添加 Pipeline 节点")
            return
        }
        val documentText = editor.document.text
        val indentation = Regex("(?m)^([ \\t]+)\\\"").find(documentText)?.groupValues?.getOrNull(1) ?: "  "
        val fragment = "${quoteJsonString(nodeName)}: ${dialog.nodeBody}"
        val replacement = PipelineJsonTextEdits.appendMember(documentText, fragment, indentation)
        val edit = PipelineJsonTextEdits.replacementEdit(TextRange(0, documentText.length), documentText, replacement)
        if (!confirmDocumentChange("添加 Pipeline 节点", "（无）", fragment, edits = listOf(edit))) return
        if (!ensureEditable()) return
        WriteCommandAction.runWriteCommandAction(
            project,
            "添加 Pipeline 节点",
            "MaaPipeline JSON Execution Lens",
            Runnable { editor.document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement) },
        )
    }

    private fun renameSelectedNode() {
        val node = currentModel.nodes.getOrNull(nodeList.selectedIndex) ?: return
        if (!ensureEditable()) return
        val newName = Messages.showInputDialog(
            project,
            "输入新的节点名称。插件会同步更新当前项目中可定位的引用和任务关系：",
            "重命名 Pipeline 节点",
            null,
            node.name,
            null,
        )?.trim().orEmpty()
        if (newName.isEmpty() || newName == node.name) return
        if (newName.any { it == '"' || it == '\n' || it == '\r' }) {
            Messages.showWarningDialog(project, "节点名称不能包含引号或换行。", "无法重命名 Pipeline 节点")
            return
        }
        if (currentModel.projectNodes.any { it.name == newName && it.name != node.name }) {
            Messages.showWarningDialog(project, "项目索引中已经存在节点：$newName", "无法重命名 Pipeline 节点")
            return
        }

        val sourceFiles = (currentModel.indexedVersions.keys +
            currentModel.pipelineOverrides.map { it.file } +
            currentModel.pipelineEntries.map { it.file } +
            currentModel.projectNodes.mapNotNull { it.sourceFile } +
            listOf(file)).distinct()
        val textByFile = sourceFiles.mapNotNull { sourceFile ->
            documentFor(sourceFile)?.let { sourceFile to it.text }
        }.toMap()
        if (textByFile.size != sourceFiles.size) {
            Messages.showWarningDialog(project, "项目中有文件无法加载为可编辑文档，已取消重命名。", "无法重命名 Pipeline 节点")
            return
        }
        val generatedFile = textByFile.entries.firstOrNull { (sourceFile, text) ->
            currentModel.generatedFiles.contains(sourceFile) || hasGeneratedHeader(text)
        }?.key
        if (generatedFile != null) {
            Messages.showWarningDialog(
                project,
                "重命名会影响生成文件：${displayPath(generatedFile)}\n插件不会直接修改生成文件。",
                "无法重命名 Pipeline 节点",
            )
            return
        }
        if (sourceFiles.any { !it.isWritable }) {
            Messages.showWarningDialog(project, "重命名涉及不可写文件，已取消操作。", "无法重命名 Pipeline 节点")
            return
        }

        val plan = PipelineNodeRenamePlanner.build(currentModel, node, newName, textByFile)
        if (plan.conflicts.isNotEmpty()) {
            Messages.showWarningDialog(
                project,
                "以下关系无法安全定位，插件不会进行部分重命名：\n\n${plan.conflicts.take(12).joinToString("\n")}",
                "无法重命名 Pipeline 节点",
            )
            return
        }
        if (plan.files.isEmpty()) return
        val documents = plan.files.associate { it.file to (documentFor(it.file) ?: return) }
        val snapshots = plan.files.associate { filePlan ->
            filePlan.file to PipelineEditSnapshot(
                documents.getValue(filePlan.file).modificationStamp,
                documents.getValue(filePlan.file).text,
            )
        }
        val preview = buildRenamePreview(plan)
        val accepted = Messages.showYesNoDialog(
            project,
            preview,
            "重命名 Pipeline 节点",
            "应用",
            "取消",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!accepted) return
        if (plan.files.any { filePlan ->
                val document = documents.getValue(filePlan.file)
                !snapshots.getValue(filePlan.file).matches(document.modificationStamp, document.text)
            }) {
            Messages.showWarningDialog(project, "确认期间文件发生变化，插件不会覆盖外部修改。请重新执行重命名。", "重命名已取消")
            refreshFromDocument(editor.document)
            return
        }
        WriteCommandAction.runWriteCommandAction(
            project,
            "重命名 Pipeline 节点",
            "MaaPipeline JSON Execution Lens",
            Runnable {
                plan.files.forEach { filePlan ->
                    val document = documents.getValue(filePlan.file)
                    filePlan.edits.sortedByDescending { it.range.startOffset }.forEach { edit ->
                        document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
                    }
                }
            },
        )
        refreshFromDocument(editor.document)
    }

    private fun buildRenamePreview(plan: PipelineNodeRenamePlan): String = buildString {
        append("将项目内 Pipeline 节点 ")
        append(plan.oldName)
        append(" 重命名为 ")
        append(plan.newName)
        append("，共修改 ")
        append(plan.editCount)
        append(" 处，涉及 ")
        append(plan.affectedFileCount)
        append(" 个文件。\n\n")
        plan.files.forEach { filePlan ->
            append("文件：")
            append(displayPath(filePlan.file))
            append("（")
            append(filePlan.edits.size)
            append(" 处）\n")
            filePlan.edits.take(8).forEach { edit ->
                val oldText = filePlan.originalText.substring(edit.range.startOffset, edit.range.endOffset)
                append("  ")
                append(previewDocumentText(oldText))
                append(" → ")
                append(previewDocumentText(edit.replacement))
                append('\n')
            }
            if (filePlan.edits.size > 8) append("  … 其余 ${filePlan.edits.size - 8} 处\n")
            append('\n')
        }
        append("所有修改会作为一次 IDEA 命令写入，可整体撤销。")
    }

    private fun removeSelectedNode() {
        val node = currentModel.nodes.getOrNull(nodeList.selectedIndex) ?: return
        if (!ensureEditable()) return
        val edits = PipelineJsonTextEdits.deletionEdits(editor.document.text, node.propertyRange)
        val inbound = currentModel.projectNodes
            .flatMap { source ->
                (source.next + source.onError)
                    .filter { reference -> reference.targetName == node.name }
                    .map { reference -> "${source.name} → ${reference.targetName}" }
            }
            .distinct()
        val anchorInbound = currentModel.projectNodes
            .flatMap { source -> source.next + source.onError }
            .filter { reference -> reference.isAnchor && reference.anchorTarget?.targetNodeName == node.name }
            .map { reference -> "${reference.displayName}（Anchor 引用）" }
            .distinct()
        val entryInbound = currentModel.pipelineEntries
            .filter { it.entryName == node.name }
            .map { "任务入口：${displayPath(it.file)}" }
        val note = if (inbound.isEmpty()) {
            (anchorInbound + entryInbound).takeIf { it.isNotEmpty() }?.let {
                "已知影响：${it.take(8).joinToString("、")}" + if (it.size > 8) " 等 ${it.size} 条" else ""
            }.orEmpty()
        } else {
            val all = inbound + anchorInbound + entryInbound
            "已知影响：${all.take(8).joinToString("、")}" +
                if (all.size > 8) " 等 ${all.size} 条" else ""
        }
        val oldText = editor.document.getText(node.propertyRange)
        if (!confirmDocumentChange("删除 Pipeline 节点", oldText, "（删除节点 ${node.name}）", note, edits = edits)) return
        if (!ensureEditable()) return
        WriteCommandAction.runWriteCommandAction(
            project,
            "删除 Pipeline 节点",
            "MaaPipeline JSON Execution Lens",
            Runnable { edits.forEach { editor.document.replaceString(it.range.startOffset, it.range.endOffset, it.replacement) } },
        )
    }

    private fun confirmDocumentChange(
        title: String,
        oldText: String,
        newText: String,
        note: String = "",
        document: Document = editor.document,
        edits: List<PipelineTextEdit> = emptyList(),
    ): Boolean {
        val snapshot = PipelineEditSnapshot(document.modificationStamp, document.text)
        val accepted = PipelineChangeDialog(project, title, oldText, newText, note).showAndGet()
        return accepted && ensureDocumentSnapshot(document, snapshot, edits)
    }

    private fun previewDocumentText(text: String): String = text
        .take(800)
        .let { value -> if (value.length < text.length) "$value…" else value }

    private fun createSectionLabel(title: String): PipelineWrappingLabel = PipelineWrappingLabel(title).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(5)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        toolTipText = when {
            title.contains("Anchor") -> PipelineVisualModel.fieldHelp("anchor")
            title == "next" || title == "on_error" -> PipelineVisualModel.fieldHelp(title)
            else -> null
        }
    }

    private fun createNavigationLabel(text: String, range: TextRange): JComponent =
        createNavigationLabel(text) { navigateToRange(range) }

    private fun createFlowSelectionLabel(
        text: String,
        location: PipelineNodeLocation,
        errorBranch: Boolean = false,
        onSelect: (() -> Unit)? = null,
    ): JComponent = PipelineStyledText(text).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(2, 4)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        toolTipText = "点击在右侧查看详情；按 Ctrl+点击跳转源码"
        addMouseListener(object : MouseAdapter() {
            private val normalForeground = foreground

            override fun mouseEntered(event: MouseEvent) {
                foreground = JBColor.namedColor("Link.hoverForeground", normalForeground)
            }

            override fun mouseExited(event: MouseEvent) {
                foreground = normalForeground
            }

            override fun mouseClicked(event: MouseEvent) {
                if (selectedText.isNullOrEmpty() && isFlowSourceNavigationGesture(event)) {
                    navigateToFileRange(location.sourceFile ?: file, location.nameRange)
                } else if (event.button == MouseEvent.BUTTON1 && selectedText.isNullOrEmpty()) {
                    if (onSelect != null) onSelect() else inspectNode(location)
                }
            }
        })
    }

    private fun isFlowSourceNavigationGesture(event: MouseEvent): Boolean {
        val configured = KeymapManager.getInstance().activeKeymap
            .getShortcuts(IdeActions.ACTION_GOTO_DECLARATION)
            .filterIsInstance<MouseShortcut>()
        if (configured.isNotEmpty()) return configured.any { shortcut ->
                shortcut.button == event.button && shortcut.clickCount == event.clickCount &&
                    configuredMouseModifiersMatch(shortcut.modifiers, event)
            }
        // Windows 的 MouseEvent.modifiers 与 IDEA MouseShortcut.modifiers 可能使用不同掩码；
        // 即使当前 keymap 没有暴露鼠标快捷键，也保留 IDEA 通用的 Ctrl/Meta+点击约定。
        return event.button == MouseEvent.BUTTON1 && (event.isControlDown || event.isMetaDown)
    }

    private fun configuredMouseModifiersMatch(configured: Int, event: MouseEvent): Boolean {
        val hasControl = configured and (InputEvent.CTRL_MASK or InputEvent.CTRL_DOWN_MASK) != 0
        val hasAlt = configured and (InputEvent.ALT_MASK or InputEvent.ALT_DOWN_MASK) != 0
        val hasShift = configured and (InputEvent.SHIFT_MASK or InputEvent.SHIFT_DOWN_MASK) != 0
        val hasMeta = configured and (InputEvent.META_MASK or InputEvent.META_DOWN_MASK) != 0
        return hasControl == event.isControlDown && hasAlt == event.isAltDown &&
            hasShift == event.isShiftDown && hasMeta == event.isMetaDown
    }

    private fun createFlowReferenceLabel(
        text: String,
        reference: PipelineReference,
        targetLocation: PipelineNodeLocation?,
        errorBranch: Boolean = false,
    ): JComponent {
        if (targetLocation != null) return createFlowSelectionLabel(text, targetLocation, errorBranch)
        return PipelineWrappingLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(2, 4)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            toolTipText = "目标暂时无法在流程索引中展开"
        }
    }

    private fun createNavigationLabel(text: String, onClick: () -> Unit): JComponent = PipelineWrappingLabel(text).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
        border = JBUI.Borders.empty(2, 4)
        addMouseListener(object : MouseAdapter() {
            private val normalForeground = foreground

            override fun mouseEntered(event: MouseEvent) {
                foreground = JBColor.namedColor("Link.hoverForeground", normalForeground)
            }

            override fun mouseExited(event: MouseEvent) {
                foreground = normalForeground
            }

            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && selectedText.isNullOrEmpty()) onClick()
            }
        })
    }

    private fun navigateToSelectedNode() {
        val selectedIndex = nodeList.selectedIndex
        val location = currentModel.nodes.getOrNull(selectedIndex) ?: return
        navigateToRange(location.nameRange)
    }

    private fun navigateToLogicNode(location: PipelineNodeLocation) {
        inspectNode(location)
    }

    private fun navigateToRange(range: TextRange) {
        navigateToFileRange(file, range)
    }

    private fun navigateToReference(reference: PipelineReference) {
        val anchorTarget = reference.anchorTarget
        if (anchorTarget != null) {
            navigateToFileRange(anchorTarget.file, anchorTarget.definitionRange)
            return
        }
        val target = reference.target
        if (target != null) {
            val targetLocation = currentModel.projectNodes
                .firstOrNull { node ->
                    (node.sourceFile ?: file) == target.file && node.name == target.name
                }
            if (targetLocation != null) {
                navigateToLogicNode(targetLocation)
                return
            }
        }
        navigateToFileRange(target?.file ?: reference.sourceFile ?: file, target?.nameRange ?: reference.range)
    }

    private fun navigateToFileRange(targetFile: com.intellij.openapi.vfs.VirtualFile, range: TextRange) {
        // 详情来源属于源码导航，必须交给 IDEA 文件编辑器处理，避免把当前右侧 JSON 预览当成目标文件。
        OpenFileDescriptor(project, targetFile, range.startOffset).navigate(true)
        saveSession()
    }

    private data class NavigationPosition(
        val offset: Int,
        val line: Int,
        val column: Int,
    )

    override fun beforeTreeDispose() {
        saveSession()
        disposed = true
        refreshing = true
        source.onCaret = { _, _ -> }
    }

    override fun dispose() {
        playbackToolbar.dispose()
        nodeListModel.clear()
    }
}
