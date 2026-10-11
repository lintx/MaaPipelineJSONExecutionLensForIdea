package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ModalityState
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
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
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JToggleButton
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

internal class PipelineFlowPanel(
    private val project: Project,
    private val file: VirtualFile,
    private val editor: Editor,
    private val showSourceForNavigation: () -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private companion object {
        // 逻辑视图只负责帮助理解当前路径；对大型项目设置上限，避免一次创建过多 Swing 组件。
        private const val MAX_LOGIC_NODES = 200
    }

    private val nodeListModel = DefaultListModel<String>()
    private val nodeList = JBList(nodeListModel)
    private val nodeSelector = JComboBox<String>()
    private val summaryLabel = JBLabel()
    private val entrySelector = JComboBox<PipelineEntryLocation>()
    private val entryStatusLabel = JBLabel("未检测到任务入口")
    private val traceButton = JButton("加载运行日志")
    private val clearTraceButton = JButton("清除日志")
    private val overrideToggle = JCheckBox("应用任务覆盖", true)
    private val logicPanel = PipelineVirtualRowsPanel()
    private val detailsPanel = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(16)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            (visibleRect.height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
    private var currentModel = PipelineDocumentModel(file, -1L, emptyList(), hasSyntaxErrors = true)
    private var currentTrace: PipelineExecutionTrace? = null
    private var disposed = false
    private var refreshing = false
    private var syncingEntrySelector = false
    private var syncingNodeSelector = false
    private var selectingFromSource = false
    private var selectedEntry: PipelineEntryLocation? = null
    private var refreshGeneration = 0L
    private val collapsedLogicNodes = linkedSetOf<String>()
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            refreshFromDocument(event.document)
        }
    }

    init {
        border = JBUI.Borders.empty(8)
        nodeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        nodeList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                syncNodeSelector()
                renderSelectedNode()
                if (!refreshing && !selectingFromSource) navigateToSelectedNode()
            }
        }
        nodeSelector.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = nodeDisplayText(value as? String)
                return this
            }
        }
        nodeSelector.toolTipText = "选择节点，并同步逻辑视图、详情和源码位置"
        nodeSelector.addActionListener {
            if (syncingNodeSelector) return@addActionListener
            val index = nodeSelector.selectedIndex
            if (index < 0 || index >= nodeListModel.size) return@addActionListener
            if (nodeList.selectedIndex == index) {
                renderSelectedNode()
                navigateToSelectedNode()
            } else {
                nodeList.selectedIndex = index
            }
        }
        entrySelector.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val entry = value as? PipelineEntryLocation
                text = entry?.let {
                    val targetSuffix = it.targetFile
                        ?.takeIf { targetFile -> targetFile != file }
                        ?.let { targetFile -> " [${pipelinePath(targetFile)}]" }
                        .orEmpty()
                    "${displayPath(it.file)} → ${it.entryName}$targetSuffix"
                } ?: "（无任务入口）"
                return this
            }
        }
        entrySelector.toolTipText = "选择任务入口，并定位到对应 Pipeline 节点"
        entrySelector.isVisible = false
        entryStatusLabel.foreground = UIUtil.getContextHelpForeground()
        overrideToggle.apply {
            isOpaque = false
            isFocusable = false
            toolTipText = "切换默认 Pipeline 值与当前任务 pipeline_override 的有效值"
            addActionListener {
                updateSummary()
                renderSelectedNode()
            }
        }
        entrySelector.addActionListener {
            if (syncingEntrySelector) return@addActionListener
            val entry = entrySelector.selectedItem as? PipelineEntryLocation ?: return@addActionListener
            selectedEntry = entry
            nodeList.repaint()
            nodeSelector.repaint()
            val entryTargetsCurrentFile = entry.targetFile == null || entry.targetFile == file
            val index = if (entryTargetsCurrentFile) {
                currentModel.nodes.indexOfFirst { it.name == entry.entryName }
            } else {
                -1
            }
            if (index < 0) {
                renderSelectedNode()
                val target = entryRootNode(entry)
                if (target != null) {
                    navigateToFileRange(target.sourceFile ?: file, target.nameRange)
                } else {
                    navigateToFileRange(entry.file, entry.range)
                }
                return@addActionListener
            }
            if (nodeList.selectedIndex == index) {
                renderSelectedNode()
                navigateToSelectedNode()
            } else {
                nodeList.selectedIndex = index
            }
        }
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
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        detailsPanel.border = JBUI.Borders.empty(10, 12)
        summaryLabel.foreground = UIUtil.getContextHelpForeground()
        summaryLabel.border = JBUI.Borders.empty(0, 8, 5, 8)
        nodeList.fixedCellHeight = JBUI.scale(26)
        nodeList.border = JBUI.Borders.empty(3, 0)
        nodeList.toolTipText = "标记说明：○ 未从入口到达　↺ 循环边　⇉ 共享目标　⚓ 动态 Anchor"
        val details = JBScrollPane(detailsPanel).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
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
                currentTrace = null
                updateSummary()
                nodeList.repaint()
                renderSelectedNode()
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
                add(JBLabel("任务入口："), BorderLayout.WEST)
                entrySelector.minimumSize = Dimension(JBUI.scale(180), JBUI.scale(28))
                entrySelector.preferredSize = Dimension(JBUI.scale(360), JBUI.scale(28))
                add(entrySelector, BorderLayout.CENTER)
                add(entryStatusLabel, BorderLayout.EAST)
            }
            val nodePanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
                add(JBLabel("节点："), BorderLayout.WEST)
                nodeSelector.minimumSize = Dimension(JBUI.scale(180), JBUI.scale(28))
                nodeSelector.preferredSize = Dimension(JBUI.scale(360), JBUI.scale(28))
                add(nodeSelector, BorderLayout.CENTER)
            }
            navigationPanel.add(entryPanel)
            navigationPanel.add(nodePanel)
            val statusPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(overrideToggle, BorderLayout.WEST)
                add(summaryLabel, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                    isOpaque = false
                    add(traceButton)
                    add(clearTraceButton)
                }, BorderLayout.EAST)
            }
            add(navigationPanel, BorderLayout.NORTH)
            add(statusPanel, BorderLayout.CENTER)
        }
        val contentSplit = OnePixelSplitter(false, 0.68f).apply {
            firstComponent = logic
            secondComponent = details
            border = JBUI.Borders.emptyTop(2)
        }
        add(summaryBar, BorderLayout.NORTH)
        add(contentSplit, BorderLayout.CENTER)
        editor.document.addDocumentListener(documentListener, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                selectNodeAtOffset(event.caret.offset)
            }
        }, this)
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

    private fun nodeDisplayText(nodeName: String?): String {
        if (nodeName == null) return "（未选择节点）"
        val state = currentTrace?.nodeStatesForEntry(selectedEntry?.entryName)?.get(nodeName)
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
        if (syncingNodeSelector) return
        syncingNodeSelector = true
        try {
            nodeSelector.selectedIndex = nodeList.selectedIndex
        } finally {
            syncingNodeSelector = false
        }
    }

    private fun refreshFromDocument(document: Document) {
        val generation = ++refreshGeneration
        PsiDocumentManager.getInstance(project).performWhenAllCommitted {
            if (disposed || generation != refreshGeneration) return@performWhenAllCommitted
            val requestedStamp = document.modificationStamp
            ReadAction.nonBlocking<PipelineDocumentModel> {
                PipelineDocumentIndexer.index(project, file)
            }.expireWith(this).finishOnUiThread(ModalityState.any()) { model ->
                if (
                    disposed ||
                    generation != refreshGeneration ||
                    document.modificationStamp != requestedStamp ||
                    model.version != document.modificationStamp
                ) {
                    return@finishOnUiThread
                }
                val selectedIndex = nodeList.selectedIndex
                val previousNodeName = currentModel.nodes.getOrNull(selectedIndex)?.name
                val previousEntry = selectedEntry ?: (entrySelector.selectedItem as? PipelineEntryLocation)
                refreshing = true
                currentModel = model
                nodeListModel.removeAllElements()
                model.nodes.forEach { nodeListModel.addElement(it.name) }
                syncingNodeSelector = true
                try {
                    nodeSelector.removeAllItems()
                    model.nodes.forEach { nodeSelector.addItem(it.name) }
                    nodeSelector.isEnabled = model.nodes.isNotEmpty()
                } finally {
                    syncingNodeSelector = false
                }
                val restoredEntry = previousEntry?.let { previous ->
                    model.pipelineEntries.firstOrNull { entry ->
                        entry.file == previous.file &&
                            entry.entryName == previous.entryName &&
                            entry.range == previous.range
                    }
                } ?: model.pipelineEntries.firstOrNull { it.entryName == previousNodeName }
                    ?: model.pipelineEntries.firstOrNull()
                syncingEntrySelector = true
                try {
                    entrySelector.removeAllItems()
                    model.pipelineEntries.forEach(entrySelector::addItem)
                    val hasEntries = model.pipelineEntries.isNotEmpty()
                    entrySelector.isEnabled = hasEntries
                    entrySelector.isVisible = hasEntries
                    entryStatusLabel.isVisible = !hasEntries
                    selectedEntry = restoredEntry
                    if (restoredEntry != null) {
                        entrySelector.selectedItem = restoredEntry
                    }
                } finally {
                    syncingEntrySelector = false
                }
                val preferredNodeName = restoredEntry
                    ?.takeIf { it.targetFile == null || it.targetFile == file }
                    ?.entryName
                    ?: previousNodeName
                val preferredIndex = preferredNodeName?.let { name ->
                    model.nodes.indexOfFirst { it.name == name }
                } ?: -1
                if (model.nodes.isNotEmpty()) {
                    nodeList.selectedIndex = when {
                        preferredIndex >= 0 -> preferredIndex
                        else -> selectedIndex.coerceIn(0, model.nodes.lastIndex)
                    }
                }
                syncNodeSelector()
                updateSummary()
                nodeList.repaint()
                nodeSelector.repaint()
                refreshing = false
                renderSelectedNode()
            }
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    private fun renderSelectedNode() {
        detailsPanel.removeAll()
        val location = currentModel.nodes.getOrNull(nodeList.selectedIndex)
        renderLogicView(location)
        if (location == null) {
            detailsPanel.add(JBLabel("请选择一个节点查看语义摘要"))
        } else {
            detailsPanel.add(JBLabel(location.name).apply {
                font = font.deriveFont(Font.BOLD)
                foreground = UIUtil.getLabelForeground()
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            val staticPath = staticPathTo(location.name)
            if (staticPath == null && selectedEntry != null) {
                detailsPanel.add(JBLabel("路径分析：从当前任务入口未解析到此节点（按文件和节点名计算项目静态路径）").apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            } else if (staticPath != null) {
                detailsPanel.add(JBLabel("路径分析：${staticPath.joinToString(" → ")}").apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            }
            addRelationshipOverview(location)
            location.description?.let { detailsPanel.add(JBLabel("说明：$it")) }
            addFieldSummary("识别", location.recognition)
            addFieldSummary("动作", location.action)
            addFieldSummary("锚点", location.anchor)
            addAnchorDefinitions(location)
            addNodeDiagnostics(location)
            addTaskOverrideSummary(location)
            addTraceSummary(location)
            detailsPanel.add(JSeparator())
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            addControlFlowExplanation(effectiveNode(location))
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            addReferences("后继", location.next, location.nextField)
            addReferences("错误后继", location.onError, location.onErrorField)
            addReferences("嵌套节点引用", location.embeddedReferences)
        }
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun updateSummary() {
        val suffix = if (currentModel.hasSyntaxErrors) "（JSON 语法暂不可完整解析）" else ""
        val generatedSuffix = if (currentModel.isGenerated) "（生成文件，编辑已禁用）" else ""
        summaryLabel.text = buildString {
            append("当前文件：${currentModel.nodes.size} 个 Pipeline 节点 $suffix$generatedSuffix")
            if (selectedEntry != null && overrideToggle.isSelected) append("　已应用任务覆盖")
            currentTrace?.let { trace ->
                append("　日志：${trace.events.size} 个事件，已执行 ${trace.executedNodeNames.size} 个节点")
                append("　来源：${displayPath(trace.file)}")
            }
        }
        clearTraceButton.isEnabled = currentTrace != null
    }

    private fun renderLogicView(location: PipelineNodeLocation?) {
        val rows = mutableListOf<PipelineVirtualRow>()
        if (location == null) {
            appendLogicNote(rows, "empty", 0, "请选择一个节点查看逻辑控制流")
        } else {
            val entry = selectedEntry
            appendLogicRow(rows, "task", 0) { panel ->
                val label = if (entry == null) JBLabel("task（未指定入口）") else {
                    createNavigationLabel("task ${entry.entryName}") { navigateToFileRange(entry.file, entry.range) }
                }
                label.font = label.font.deriveFont(Font.BOLD)
                panel.add(label)
            }
            appendLogicNote(rows, "context", 0, if (entry == null) {
                "未指定任务入口 · 当前仅显示所选节点的静态控制流"
            } else {
                "当前任务：${displayPath(entry.file)} · 控制流按该任务的有效字段解释"
            })
            appendLogicRow(rows, "toolbar", 0, 34) { addLogicToolbar(it) }
            val root = entry?.let(::entryRootNode) ?: location
            val rootKey = logicNodeKey(root.name, root.sourceFile ?: file)
            appendLogicNote(rows, "limit", 0, "从 ${root.name} 展开静态控制流；循环、共享节点和动态 Anchor 保留边界。组件按可视区域创建，最多展开 $MAX_LOGIC_NODES 个唯一节点。")
            appendVirtualLogicNode(root, rows, 0, setOf(rootKey), linkedSetOf(rootKey))
        }
        logicPanel.setRows(rows)
    }

    private fun appendLogicRow(
        rows: MutableList<PipelineVirtualRow>,
        key: String,
        depth: Int,
        estimatedHeight: Int = 24,
        build: (JPanel) -> Unit,
    ) {
        rows += PipelineVirtualRow(key, JBUI.scale(estimatedHeight)) {
            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            build(content)
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 16 + depth * 12, 2, 16)
                add(content, BorderLayout.CENTER)
            }
        }
    }

    private fun appendLogicNote(rows: MutableList<PipelineVirtualRow>, key: String, depth: Int, text: String) {
        appendLogicRow(rows, key, depth) { addLogicNote(text, it) }
    }

    private fun entryRootNode(entry: PipelineEntryLocation): PipelineNodeLocation? {
        val indexedNodes = currentModel.projectNodes.ifEmpty { currentModel.nodes }
        val targetFile = entry.targetFile ?: file
        return indexedNodes.firstOrNull { node ->
            (node.sourceFile ?: file) == targetFile && node.name == entry.entryName
        } ?: indexedNodes.firstOrNull { node ->
            node.name == entry.entryName
        }
    }

    private fun addLogicToolbar(container: JPanel) {
        val actions = DefaultActionGroup()
        fun addAction(text: String, description: String, enabled: () -> Boolean = { true }, action: () -> Unit) {
            actions.add(object : DumbAwareAction(text, description, null) {
                init {
                    templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = enabled()
                }

                override fun actionPerformed(event: AnActionEvent) = action()
            })
        }
        addAction("+ 节点", "在当前 Pipeline JSON 顶层添加节点", { !currentModel.isGenerated && !currentModel.hasSyntaxErrors }) {
            addNode()
        }
        addAction("− 节点", "删除当前节点，并提示已知引用", { !currentModel.isGenerated && nodeList.selectedIndex >= 0 }) {
            removeSelectedNode()
        }
        addAction("全部展开", "展开当前流程树中的所有节点") {
            collapsedLogicNodes.clear()
            renderLogicView(currentModel.nodes.getOrNull(nodeList.selectedIndex))
        }
        addAction("全部收起", "收起当前流程中的节点内容") {
            val nodes = currentModel.projectNodes.ifEmpty { currentModel.nodes }
            collapsedLogicNodes += nodes.map { logicNodeKey(it.name, it.sourceFile ?: file) }
            renderLogicView(currentModel.nodes.getOrNull(nodeList.selectedIndex))
        }
        addAction("Anchor", "查看项目级 Anchor 定义、引用和运行时设置点") { showAnchorManager() }
        val actionToolbar = ActionManager.getInstance().createActionToolbar("MaaPipeline.Logic", actions, true)
        actionToolbar.targetComponent = this
        container.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("传统逻辑").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
            add(actionToolbar.component, BorderLayout.CENTER)
        })
    }

    private fun logicNodeKey(nodeName: String, sourceFile: VirtualFile = file): String =
        "${sourceFile.url}#$nodeName"

    private fun appendVirtualLogicNode(
        location: PipelineNodeLocation,
        rows: MutableList<PipelineVirtualRow>,
        depth: Int,
        ancestors: Set<String>,
        expandedNodes: MutableSet<String>,
    ) {
        val sourceFile = location.sourceFile ?: file
        val nodeKey = logicNodeKey(location.name, sourceFile)
        val isCollapsed = nodeKey in collapsedLogicNodes
        appendLogicRow(rows, "$nodeKey:header", depth, 32) { panel ->
            val selectedName = currentModel.nodes.getOrNull(nodeList.selectedIndex)?.name
            val selectedSuffix = if (sourceFile == file && location.name == selectedName) " · 当前选择" else ""
            val traceSuffix = currentTrace?.nodeStatesForEntry(selectedEntry?.entryName)?.get(location.name)?.let {
                " · 本次运行：${it.status.label}"
            }.orEmpty()
            val header = JPanel(BorderLayout()).apply { isOpaque = false }
            header.add(createNavigationLabel("run ${location.name}$selectedSuffix$traceSuffix") {
                navigateToLogicNode(location)
            }.apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.CENTER)
            if (sourceFile != file) {
                header.add(JBLabel(" · ${sourceFile.name}").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = displayPath(sourceFile)
                }, BorderLayout.EAST)
            }
            header.add(createFoldMarker(!isCollapsed, location.name) { isExpanded ->
                if (isExpanded) collapsedLogicNodes -= nodeKey else collapsedLogicNodes += nodeKey
                renderLogicView(currentModel.nodes.getOrNull(nodeList.selectedIndex))
            }, BorderLayout.WEST)
            panel.add(header)
        }
        if (isCollapsed) return
        location.description?.let { appendLogicNote(rows, "$nodeKey:description", depth + 1, "说明：$it") }
        appendLogicRow(rows, "$nodeKey:after", depth + 1) {
            addLogicLine("after ${location.name}:", container = it, emphasis = true)
        }
        val effective = effectiveNode(location)
        appendVirtualLogicBranch(location, effective.next, "next", rows, depth + 1, ancestors, expandedNodes)
        appendVirtualLogicBranch(location, effective.onError, "on_error", rows, depth + 1, ancestors, expandedNodes)
        if (!effective.executionPolicy.isEmpty()) {
            appendLogicNote(rows, "$nodeKey:policy", depth + 1, "执行策略：${effective.executionPolicy.summary()}")
        }
    }

    private fun appendVirtualLogicBranch(
        location: PipelineNodeLocation,
        references: List<PipelineReference>,
        fieldName: String,
        rows: MutableList<PipelineVirtualRow>,
        depth: Int,
        ancestors: Set<String>,
        expandedNodes: MutableSet<String>,
    ) {
        val branchKey = "${logicNodeKey(location.name, location.sourceFile ?: file)}:$fieldName"
        val isError = fieldName == "on_error"
        val title = if (isError) "错误后继 · 识别超时或动作失败" else "成功后继 · 按 JSON 顺序尝试"
        appendLogicRow(rows, "$branchKey:title", depth) {
            addLogicLine(title, container = it, emphasis = true)
        }
        if (hasTaskOverride(location, fieldName)) {
            appendLogicNote(rows, "$branchKey:override", depth, "字段来自当前任务的 pipeline_override；它替换默认列表。")
        }
        if (references.isEmpty()) {
            appendLogicNote(rows, "$branchKey:empty", depth, if (isError) {
                "未声明错误后继，失败后按当前节点策略处理。"
            } else {
                "本轮没有成功后继，按当前节点策略重试或结束。"
            })
            return
        }
        val indexedNodes = currentModel.projectNodes.ifEmpty { currentModel.nodes }
        references.forEachIndexed { index, reference ->
            val candidateKey = "$branchKey:$index"
            appendLogicRow(rows, "$candidateKey:card", depth, 120) {
                addLogicCandidateCard(index, reference, it, location.name)
            }
            val target = reference.target
            val targetLocation = target?.let { targetNode ->
                indexedNodes.firstOrNull { (it.sourceFile ?: file) == targetNode.file && it.name == targetNode.name }
            }
            val targetKey = targetLocation?.let { logicNodeKey(it.name, it.sourceFile ?: file) }
            val boundary = when {
                reference.isAnchor -> "动态 Anchor 不在静态视图中展开，请点击候选卡片查看定义。"
                target == null -> null
                targetLocation == null -> "跨文件目标未在当前项目索引中展开：${displayPath(target.file)} → ${target.name}。"
                targetKey in ancestors -> "↩ 循环回到 ${targetLocation.name}，停止继续展开。"
                targetKey in expandedNodes -> "⇉ 共享节点 ${targetLocation.name} 已在上方展开，当前分支保留为链接。"
                expandedNodes.size >= MAX_LOGIC_NODES -> "已达到静态展开上限（$MAX_LOGIC_NODES 个唯一节点），当前目标保留为链接：${targetLocation.name}。"
                else -> null
            }
            if (boundary != null) {
                appendLogicNote(rows, "$candidateKey:boundary", depth, boundary)
            } else if (targetLocation != null && targetKey != null) {
                expandedNodes += targetKey
                appendVirtualLogicNode(targetLocation, rows, depth + 1, ancestors + targetKey, expandedNodes)
            }
        }
        appendLogicNote(rows, "$branchKey:otherwise", depth, "否则：本组候选均未成功，按当前节点策略重试或结束。")
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
    ) {
        val traceOutcome = currentTrace?.let { trace ->
            listOf(reference.targetName, reference.displayName)
                .asSequence()
                .map { candidateName ->
                    trace.candidateOutcomeForEntry(
                        traceNodeName.orEmpty(),
                        candidateName,
                        selectedEntry?.entryName,
                    )
                }
                .firstOrNull { outcome -> outcome != PipelineTraceCandidateOutcome.UNOBSERVED }
        }
        val heading = buildString {
            append("${index + 1}. ")
            if (reference.isJumpBack) append("↩ JumpBack · ")
            if (reference.isAnchor) append("⚓ 动态 Anchor · ")
            append(reference.displayName)
            append("  →  ")
            append(reference.targetName)
            traceOutcome?.let { append(" · ${it.label}") }
        }
        val card = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(4, 8),
            )
            alignmentX = LEFT_ALIGNMENT
        }
        card.add(
            createNavigationLabel(heading) { navigateToReference(reference) },
            BorderLayout.NORTH,
        )
        val details = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
        }
        fun addDetail(text: String, muted: Boolean = true) {
            details.add(JBLabel(text).apply {
                foreground = if (muted) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }
        val target = reference.target
        if (target != null) {
            addDetail("识别：${target.recognitionSummary ?: "未设置"}")
            target.actionSummary?.let { addDetail("动作：$it") }
            target.description?.let { addDetail("说明：$it") }
            if (reference.isCrossFile) {
                addDetail("来源：${displayPath(target.file)}", muted = false)
            }
        } else if (reference.isAnchor) {
            val anchorTarget = reference.anchorTarget
            val resolvedText = when {
                anchorTarget == null -> "运行时 Anchor：${reference.targetName}（尚未解析）"
                anchorTarget.isCleared -> "运行时 Anchor：${anchorTarget.anchorName}（当前定义会清除目标）"
                anchorTarget.targetNode != null ->
                    "运行时 Anchor：${anchorTarget.anchorName} → ${anchorTarget.targetNode.name}（动态绑定）"

                else -> "运行时 Anchor：${anchorTarget.anchorName}（目标由运行时决定）"
            }
            addDetail(resolvedText, muted = false)
        } else {
            addDetail("目标未解析：${reference.targetName}", muted = false)
        }
        if (reference.isJumpBack) {
            addDetail("执行目标后返回父流程上下文。", muted = false)
        }
        val relations = buildList {
            if (reference.isCycle) add("循环边")
            if (reference.isSharedTarget) add("共享目标")
            if (reference.isAmbiguousTarget) add("名称不唯一")
            if (reference.isAnchorUnresolved) add("Anchor 未解析")
        }
        if (relations.isNotEmpty()) {
            addDetail("关系：${relations.joinToString("、")}", muted = false)
        }
        currentTrace?.nodeStatesForEntry(selectedEntry?.entryName)?.get(reference.targetName)?.let { event ->
            addDetail("本次运行：${event.status.label}（日志第 ${event.lineNumber} 行）", muted = false)
        }
        traceOutcome?.let { outcome ->
            addDetail("候选结果：${outcome.label}", muted = false)
        }
        card.add(details, BorderLayout.CENTER)
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        container.add(card)
    }

    private fun addLogicNote(text: String, container: JPanel = logicPanel) {
        container.add(JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 8)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
        })
    }

    private fun addLogicLine(
        text: String,
        reference: PipelineReference? = null,
        range: TextRange? = null,
        container: JPanel = logicPanel,
        emphasis: Boolean = false,
    ) {
        val line = when {
            reference != null -> createNavigationLabel(text) { navigateToReference(reference) }
            range != null -> createNavigationLabel(text, range)
            else -> JBLabel(text)
        }
        if (emphasis) line.font = line.font.deriveFont(Font.BOLD)
        line.alignmentX = LEFT_ALIGNMENT
        line.maximumSize = Dimension(Int.MAX_VALUE, line.preferredSize.height)
        container.add(line)
    }

    private fun hasTaskOverride(location: PipelineNodeLocation, fieldName: String): Boolean {
        if (!overrideToggle.isSelected) return false
        val entry = selectedEntry ?: return false
        return currentModel.pipelineOverrides
            .firstOrNull { it.file == entry.file && it.targetName == location.name }
            ?.fields
            ?.any { it.name == fieldName }
            ?: false
    }

    private fun addControlFlowExplanation(location: PipelineNodeLocation) {
        detailsPanel.add(createSectionLabel("逻辑控制流 · 静态推导"))
        PipelineControlFlowExplanation.explain(location).forEach { line ->
            val component = if (line.reference == null) {
                JBLabel(line.text).apply {
                    foreground = when (line.kind) {
                        PipelineControlFlowLineKind.DESCRIPTION -> UIUtil.getContextHelpForeground()
                        PipelineControlFlowLineKind.RETRY_POLICY -> UIUtil.getLabelForeground()
                        PipelineControlFlowLineKind.TERMINAL -> UIUtil.getContextHelpForeground()
                        else -> UIUtil.getLabelForeground()
                    }
                }
            } else {
                // 候选行沿用详情页已有的导航组件，点击后仍定位到目标节点或原始引用。
                createNavigationLabel(line.text) { navigateToReference(line.reference) }
            }
            component.alignmentX = LEFT_ALIGNMENT
            component.maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height)
            detailsPanel.add(component)
        }
    }

    private fun addFieldSummary(title: String, field: PipelineFieldLocation?) {
        if (field == null) return
        detailsPanel.add(createSectionLabel(title))
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
        }
        row.add(createNavigationLabel(field.summary, field.range), BorderLayout.CENTER)
        row.add(JButton("编辑").apply {
            toolTipText = if (field.isString) {
                "只替换当前字符串值，保留 JSON 其它内容"
            } else {
                "在 JSON 子编辑器中修改当前值"
            }
            isEnabled = !currentModel.isGenerated
            isFocusable = false
            addActionListener {
                if (field.isString) editStringField(field) else editJsonField(field)
            }
        }, BorderLayout.EAST)
        detailsPanel.add(row)
    }

    private fun addAnchorDefinitions(location: PipelineNodeLocation) {
        if (location.anchorDefinitions.isEmpty()) {
            detailsPanel.add(JButton("管理项目 Anchor").apply {
                isFocusable = false
                addActionListener { showAnchorManager() }
            })
            return
        }
        detailsPanel.add(createSectionLabel("Anchor 定义"))
        location.anchorDefinitions.forEach { definition ->
            val target = definition.targetNodeName ?: "当前节点（运行时绑定）"
            val state = if (definition.isCleared) "清除目标" else "目标：$target"
            detailsPanel.add(
                createNavigationLabel("${definition.name} · $state") {
                    navigateToRange(definition.range)
                },
            )
        }
        location.anchor?.let { field ->
            detailsPanel.add(JButton("编辑 Anchor 定义").apply {
                isFocusable = false
                isEnabled = !currentModel.isGenerated
                toolTipText = "编辑当前节点的完整 anchor 字段，保留其它节点和注释"
                addActionListener {
                    if (field.isString) editStringField(field) else editJsonField(field)
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
        val observedEvents = currentTrace?.eventsForEntry(selectedEntry?.entryName).orEmpty()
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
        applyAnchorEdits(row, snapshot, listOf(PipelineTextEdit(range, dialog.value)), "编辑 Anchor 绑定")
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
    ) {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
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
                body.add(
                    createReferenceCard(
                        index = index,
                        marker = marker,
                        reference = reference,
                        states = states,
                        allowReorder = title == "后继",
                        referenceCount = references.size,
                    ),
                )
            }
        }
        val section = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        val foldMarker = createFoldMarker(body.isVisible, title) { isExpanded ->
            body.isVisible = isExpanded
            section.maximumSize = Dimension(Int.MAX_VALUE, section.preferredSize.height)
            detailsPanel.revalidate()
            detailsPanel.repaint()
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(createSectionLabel(title), BorderLayout.CENTER)
            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                if (field != null) {
                    add(JButton("+").apply {
                        toolTipText = "添加一个字符串后继"
                        isFocusable = false
                        isEnabled = !currentModel.isGenerated
                        addActionListener { addReference(field) }
                    })
                }
                add(foldMarker)
            }
            add(actions, BorderLayout.EAST)
        }
        section.add(header, BorderLayout.NORTH)
        section.add(body, BorderLayout.CENTER)
        // 嵌套在 BoxLayout 中时锁定自身高度，折叠后才能即时收缩而不留下空白。
        section.maximumSize = Dimension(Int.MAX_VALUE, section.preferredSize.height)
        detailsPanel.add(section)
    }

    private fun addReference(field: PipelineFieldLocation) {
        if (!ensureEditable()) return
        val snapshot = captureEditSnapshot()
        val targetName = Messages.showInputDialog(
            project,
            "输入要追加的目标节点名（可带 [JumpBack] 或 [Anchor] 前缀）：",
            "添加 Pipeline 后继",
            null,
            "",
            null,
        )?.trim().orEmpty()
        if (targetName.isEmpty()) return
        if (!ensureSnapshot(snapshot)) return
        val range = field.valueRange
        if (range.startOffset < 0 || range.endOffset > editor.document.textLength) return
        val currentValue = editor.document.getText(range)
        val quotedName = quoteJsonString(targetName)
        val replacement = if (currentValue.trim().startsWith("[") && currentValue.trim().endsWith("]")) {
            PipelineJsonTextEdits.appendMember(currentValue, quotedName)
        } else {
            "[$currentValue, $quotedName]"
        }
        val edit = PipelineJsonTextEdits.replacementEdit(range, currentValue, replacement)
        if (!confirmDocumentChange("添加 Pipeline 后继", currentValue, replacement, edits = listOf(edit))) return
        if (!ensureEditable()) return
        WriteCommandAction.runWriteCommandAction(
            project,
            "添加 Pipeline 后继",
            "MaaPipeline JSON Execution Lens",
            Runnable {
                editor.document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
            },
        )
    }

    private fun createReferenceCard(
        index: Int,
        marker: String,
        reference: PipelineReference,
        states: List<String>,
        allowReorder: Boolean,
        referenceCount: Int,
    ): JComponent {
        val card = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(3, 6),
            )
            alignmentX = LEFT_ALIGNMENT
        }
        val state = states.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "（$it）" }.orEmpty()
        val referenceLabel = buildString {
            if (reference.kind != PipelineReferenceKind.NEXT && reference.kind != PipelineReferenceKind.ON_ERROR) {
                append(reference.kind.label)
                reference.propertyPath?.let { append(" [$it]") }
                append("：")
            }
        }
        card.add(
            createNavigationLabel(
                "${index + 1}. $referenceLabel$marker${reference.displayName} $state",
            ) { navigateToReference(reference) },
            BorderLayout.CENTER,
        )
        reference.target?.let { target ->
            val summary = buildList {
                if (reference.isCrossFile) add("文件：${pipelinePath(target.file)}")
                target.description?.let { add("说明：$it") }
                target.recognitionSummary?.let { add("识别：$it") }
                target.actionSummary?.let { add("动作：$it") }
                add("后继：${target.nextCount}")
            }.joinToString("  ")
            card.add(JBLabel(summary).apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyTop(2)
            }, BorderLayout.SOUTH)
        }
        reference.anchorTarget?.let { anchor ->
            val summary = buildList {
                add("定义：${anchor.definingNodeName}")
                if (anchor.isCleared) {
                    add("目标：未设置")
                } else if (anchor.targetNode != null) {
                    add("目标：${anchor.targetNode.name}")
                    if (anchor.targetNode.file != file) add("文件：${displayPath(anchor.targetNode.file)}")
                } else {
                    add("目标：节点未定义")
                }
            }.joinToString("  ")
            card.add(JBLabel(summary).apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyTop(2)
            }, BorderLayout.SOUTH)
        }
        if (allowReorder) {
            val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
            }
            if (reference.isString) {
                controls.add(createMarkerButton("回", "切换 JumpBack 前缀", reference.isJumpBack, !currentModel.isGenerated) {
                    toggleReferenceMarker(reference, "[JumpBack]", it)
                })
                controls.add(createMarkerButton("锚", "切换 Anchor 前缀", reference.isAnchor, !currentModel.isGenerated) {
                    toggleReferenceMarker(reference, "[Anchor]", it)
                })
            }
            controls.add(createReorderButton("↑", "将此后继前移", index > 0 && !currentModel.isGenerated) {
                moveNextReference(index, -1)
            })
            controls.add(createReorderButton("↓", "将此后继后移", index < referenceCount - 1 && !currentModel.isGenerated) {
                moveNextReference(index, 1)
            })
            controls.add(createReorderButton("×", "删除此后继", !currentModel.isGenerated) {
                removeNextReference(index)
            })
            card.add(controls, BorderLayout.EAST)
        }
        // BoxLayout 的纵向布局会按最大高度拉伸组件；卡片高度必须锁定为自身内容高度。
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return card
    }

    private fun createMarkerButton(
        text: String,
        tooltip: String,
        selected: Boolean,
        enabled: Boolean,
        action: (Boolean) -> Unit,
    ): JToggleButton = JToggleButton(text).apply {
        toolTipText = tooltip
        isSelected = selected
        isEnabled = enabled
        isFocusable = false
        preferredSize = Dimension(JBUI.scale(26), JBUI.scale(24))
        minimumSize = preferredSize
        maximumSize = preferredSize
        addActionListener { action(isSelected) }
    }

    private fun createReorderButton(
        text: String,
        tooltip: String,
        enabled: Boolean,
        action: () -> Unit,
    ): JButton = JButton(text).apply {
        toolTipText = tooltip
        isEnabled = enabled
        isFocusable = false
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        minimumSize = preferredSize
        maximumSize = preferredSize
        addActionListener { action() }
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
        val overrides = currentModel.pipelineOverrides.filter { it.targetName == location.name }
        val entries = currentModel.pipelineEntries.filter { it.entryName == location.name }
        val diagnostics = currentModel.diagnostics.filter { diagnostic ->
            diagnostic.range.startOffset >= location.propertyRange.startOffset &&
                diagnostic.range.endOffset <= location.propertyRange.endOffset
        }
        if (entries.isEmpty() && overrides.isEmpty() && diagnostics.isEmpty()) return
        detailsPanel.add(createSectionLabel("项目诊断"))
        entries.forEach { entry ->
            detailsPanel.add(
                createNavigationLabel("任务入口：${displayPath(entry.file)} → ${entry.entryName}") {
                    navigateToFileRange(entry.file, entry.range)
                },
            )
        }
        overrides.forEach { override ->
            val fields = override.fields.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }.orEmpty()
            val suffix = fields.takeIf { it.isNotEmpty() }?.let { "（字段：$it）" }.orEmpty()
            detailsPanel.add(
                createNavigationLabel(
                    "pipeline_override：${displayPath(override.file)} → ${override.targetName}$suffix",
                ) { navigateToFileRange(override.file, override.range) },
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
        val entry = selectedEntry ?: return
        detailsPanel.add(createSectionLabel("任务有效值"))
        detailsPanel.add(
            createNavigationLabel("任务：${displayPath(entry.file)} → ${entry.entryName}") {
                navigateToFileRange(entry.file, entry.range)
            },
        )
        if (!overrideToggle.isSelected) {
            detailsPanel.add(JBLabel("当前未应用任务覆盖，下面字段均为 Pipeline 默认值").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
            return
        }
        val override = currentModel.pipelineOverrides.firstOrNull {
            it.file == entry.file && it.targetName == location.name
        }
        if (override == null) {
            detailsPanel.add(JBLabel("当前任务未覆盖此节点，所有字段使用默认值").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
            detailsPanel.add(createOverrideAddButton(location, null))
            return
        }
        if (override.fields.isEmpty()) {
            detailsPanel.add(JBLabel("当前任务存在覆盖对象，但没有可展示的字段").apply {
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
            detailsPanel.add(JBLabel("默认值：$defaultSummary").apply {
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            }
            row.add(
                createNavigationLabel("覆盖值：${field.summary}") {
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
            detailsPanel.add(JBLabel("有效值：${field.summary}").apply {
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
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
        val taskFile = entry.file
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
            val pipelineOverrideProperty = findJsonProperty(jsonFile, "pipeline_override")
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
                    (jsonFile.topLevelValue as? JsonObject)?.textRange?.let {
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
        val replacement = if (field.isString) {
            val value = Messages.showInputDialog(project, "修改覆盖字段 ${field.name}：", "编辑任务覆盖", null, field.summary, null)
                ?: return
            quoteJsonString(value)
        } else {
            val dialog = PipelineJsonFieldDialog(project, field.name, initialText)
            if (!dialog.showAndGet()) return
            dialog.value
        }
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
        val entry = selectedEntry ?: return location
        val override = currentModel.pipelineOverrides.firstOrNull {
            it.file == entry.file && it.targetName == location.name
        } ?: return location
        return override.fields.fold(location) { effective, field ->
            when (field.name) {
                "desc" -> effective.copy(description = field.summary)
                "recognition" -> effective.copy(recognition = field)
                "action" -> effective.copy(action = field)
                "anchor" -> effective.copy(anchor = field)
                "next" -> effective.copy(next = field.references)
                "on_error" -> effective.copy(onError = field.references)
                "timeout" -> effective.copy(
                    executionPolicy = effective.executionPolicy.copy(timeout = field),
                )

                "rate_limit" -> effective.copy(
                    executionPolicy = effective.executionPolicy.copy(rateLimit = field),
                )

                "max_hit" -> effective.copy(
                    executionPolicy = effective.executionPolicy.copy(maxHit = field),
                )

                else -> effective
            }
        }
    }

    private fun reachableNodeNames(): Set<String>? {
        val entry = selectedEntry ?: return null
        val indexedNodes = currentModel.projectNodes.ifEmpty { currentModel.nodes }
        val nodesByKey = indexedNodes.associateBy { node ->
            logicNodeKey(node.name, node.sourceFile ?: file)
        }
        val root = entryRootNode(entry) ?: return emptySet()
        val reachable = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(node: PipelineNodeLocation) {
            val nodeFile = node.sourceFile ?: file
            val nodeKey = logicNodeKey(node.name, nodeFile)
            if (!visited.add(nodeKey)) return
            if (nodeFile == file) reachable += node.name
            effectiveNode(node).next
                .plus(effectiveNode(node).onError)
                .mapNotNull { reference -> reference.target?.let { target ->
                    nodesByKey[logicNodeKey(target.name, target.file)]
                }
                }
                .forEach(::visit)
        }
        visit(root)
        return reachable
    }

    private fun staticPathTo(targetName: String): List<String>? {
        val entry = selectedEntry ?: return null
        val indexedNodes = currentModel.projectNodes.ifEmpty { currentModel.nodes }
        val nodesByKey = indexedNodes.associateBy { node ->
            logicNodeKey(node.name, node.sourceFile ?: file)
        }
        val root = entryRootNode(entry) ?: return null
        val targetKey = logicNodeKey(targetName, file)
        val queue = ArrayDeque<List<String>>()
        val visited = mutableSetOf<String>()
        queue.addLast(listOf(logicNodeKey(root.name, root.sourceFile ?: file)))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val currentKey = path.last()
            val current = nodesByKey[currentKey] ?: continue
            if (currentKey == targetKey) {
                return path.mapNotNull { key ->
                    nodesByKey[key]?.let(::nodePathLabel)
                }
            }
            if (!visited.add(currentKey)) continue
            effectiveNode(current).next
                .plus(effectiveNode(current).onError)
                .mapNotNull { reference -> reference.target?.let { target ->
                    logicNodeKey(target.name, target.file).takeIf(nodesByKey::containsKey)
                }
                }
                .forEach { nextKey ->
                    queue.addLast(path + nextKey)
                }
        }
        return null
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

    private fun addTraceSummary(location: PipelineNodeLocation) {
        val trace = currentTrace ?: return
        val events = trace.eventsForEntry(selectedEntry?.entryName).filter { it.nodeName == location.name }
        if (events.isEmpty()) return
        detailsPanel.add(createSectionLabel("运行轨迹 · 本次运行"))
        events.takeLast(8).forEach { event ->
            val task = event.entryName?.let { "任务 $it" }.orEmpty()
            val suffix = if (task.isEmpty()) "" else "，$task"
            detailsPanel.add(
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
                detailsPanel.add(JBLabel("候选：$candidates").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyLeft(8)
                })
            }
            event.anchorName?.let { anchor ->
                detailsPanel.add(JBLabel("运行时 Anchor：$anchor").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyLeft(8)
                })
            }
        }
    }

    private fun loadTrace() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("选择 MaaFramework 运行日志")
            .withDescription("选择 MaaEnd debug/maafw.log 或导出的同类日志文件")
        val defaultFile = project.baseDir?.findFileByRelativePath("debug/maafw.log")
        val selected = FileChooser.chooseFile(descriptor, project, defaultFile) ?: return
        val trace = runCatching { PipelineTraceParser.parse(selected) }.getOrElse { error ->
            Messages.showErrorDialog(project, "无法读取运行日志：${error.message}", "加载运行日志失败")
            return
        }
        currentTrace = trace
        updateSummary()
        nodeList.repaint()
        renderSelectedNode()
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

    private fun editStringField(field: PipelineFieldLocation) {
        if (!ensureEditable()) return
        val snapshot = captureEditSnapshot()
        val newValue = Messages.showInputDialog(
            project,
            "修改 ${field.name} 的字符串值：",
            "编辑 Pipeline 字段",
            null,
            field.summary,
            null,
        ) ?: return
        val replacement = quoteJsonString(newValue)
        val range = field.valueRange
        if (!ensureSnapshot(snapshot, listOf(PipelineTextEdit(range, replacement)))) return
        if (range.startOffset < 0 || range.endOffset > editor.document.textLength) return
        val oldValue = editor.document.getText(range)
        if (oldValue == replacement) return
        if (!ensureEditable()) return
        if (!confirmDocumentChange("编辑 Pipeline 字段", oldValue, replacement,
                edits = listOf(PipelineTextEdit(range, replacement)))) return
        if (!ensureEditable()) return

        // 只替换字符串值范围，避免重新格式化 JSON 或丢失 JSONC 注释。
        WriteCommandAction.runWriteCommandAction(
            project,
            "编辑 Pipeline 字段",
            "MaaPipeline JSON Execution Lens",
            Runnable {
            editor.document.replaceString(range.startOffset, range.endOffset, replacement)
            },
        )
    }

    private fun editJsonField(field: PipelineFieldLocation) {
        if (!ensureEditable()) return
        val snapshot = captureEditSnapshot()
        val range = field.valueRange
        if (range.startOffset < 0 || range.endOffset > editor.document.textLength) return
        val initialText = editor.document.getText(range)
        val dialog = PipelineJsonFieldDialog(project, field.name, initialText)
        if (!dialog.showAndGet()) return
        val replacement = dialog.value
        if (replacement.isEmpty() || replacement == initialText) return
        if (!ensureSnapshot(snapshot, listOf(PipelineTextEdit(range, replacement)))) return
        if (!ensureEditable()) return
        if (!confirmDocumentChange("编辑 Pipeline JSON 字段", initialText, replacement,
                edits = listOf(PipelineTextEdit(range, replacement)))) return
        if (!ensureEditable()) return

        WriteCommandAction.runWriteCommandAction(
            project,
            "编辑 Pipeline JSON 字段",
            "MaaPipeline JSON Execution Lens",
            Runnable {
                editor.document.replaceString(range.startOffset, range.endOffset, replacement)
            },
        )
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

    private fun toggleReferenceMarker(reference: PipelineReference, marker: String, enabled: Boolean) {
        if (!ensureEditable()) return
        val updatedValue = if (enabled) {
            if (reference.displayName.startsWith(marker)) {
                reference.displayName
            } else {
                marker + reference.displayName
            }
        } else {
            removeLeadingMarker(reference.displayName, marker)
        }
        if (updatedValue == reference.displayName) return
        val replacement = quoteJsonString(updatedValue)
        val range = reference.range
        if (range.startOffset < 0 || range.endOffset > editor.document.textLength) return
        if (!confirmDocumentChange("编辑 Pipeline 引用标记", editor.document.getText(range), replacement,
                edits = listOf(PipelineTextEdit(range, replacement)))) return
        if (!ensureEditable()) return

        // 前缀编辑只替换当前引用字符串，保留数组中的空白、注释和其它候选项。
        WriteCommandAction.runWriteCommandAction(
            project,
            "编辑 Pipeline 引用标记",
            "MaaPipeline JSON Execution Lens",
            Runnable {
                editor.document.replaceString(range.startOffset, range.endOffset, replacement)
            },
        )
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

    private fun moveNextReference(index: Int, direction: Int) {
        val node = currentModel.nodes.getOrNull(nodeList.selectedIndex) ?: return
        val targetIndex = index + direction
        if (targetIndex !in node.next.indices) return
        if (!ensureEditable()) return

        val currentRange = node.next[index].range
        val targetRange = node.next[targetIndex].range
        val lowerRange: TextRange
        val higherRange: TextRange
        if (currentRange.startOffset < targetRange.startOffset) {
            lowerRange = currentRange
            higherRange = targetRange
        } else {
            lowerRange = targetRange
            higherRange = currentRange
        }
        val lowerText = editor.document.getText(lowerRange)
        val higherText = editor.document.getText(higherRange)
        if (!confirmDocumentChange("调整 Pipeline next 顺序", lowerText + "\n" + higherText, higherText + "\n" + lowerText,
                edits = listOf(PipelineTextEdit(lowerRange, higherText), PipelineTextEdit(higherRange, lowerText)))) return
        if (!ensureEditable()) return

        // 两次替换按源码位置从后往前执行，防止前一个值长度变化使后一个范围失效。
        WriteCommandAction.runWriteCommandAction(
            project,
            "调整 Pipeline next 顺序",
            "MaaPipeline JSON Execution Lens",
            Runnable {
                editor.document.replaceString(higherRange.startOffset, higherRange.endOffset, lowerText)
                editor.document.replaceString(lowerRange.startOffset, lowerRange.endOffset, higherText)
            },
        )
    }

    private fun removeNextReference(index: Int) {
        val node = currentModel.nodes.getOrNull(nodeList.selectedIndex) ?: return
        val reference = node.next.getOrNull(index) ?: return
        if (!ensureEditable()) return
        val edits = if (node.nextField?.valueRange == reference.range) {
            listOf(PipelineTextEdit(reference.range, "[]"))
        } else {
            PipelineJsonTextEdits.deletionEdits(editor.document.text, reference.range)
        }
        val oldText = editor.document.getText(reference.range)
        if (!confirmDocumentChange("删除 Pipeline 后继", oldText, "（删除）", edits = edits)) return
        if (!ensureEditable()) return
        WriteCommandAction.runWriteCommandAction(
            project,
            "删除 Pipeline 后继",
            "MaaPipeline JSON Execution Lens",
            Runnable { edits.forEach { editor.document.replaceString(it.range.startOffset, it.range.endOffset, it.replacement) } },
        )
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
        val message = buildString {
            append("将写回当前 JSON/JSONC 文档：\n\n")
            if (note.isNotEmpty()) append("注意：$note\n\n")
            append("原值：\n")
            append(previewDocumentText(oldText))
            append("\n\n新值：\n")
            append(previewDocumentText(newText))
        }
        val accepted = Messages.showYesNoDialog(
            project,
            message,
            title,
            "应用",
            "取消",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        return accepted && ensureDocumentSnapshot(document, snapshot, edits)
    }

    private fun previewDocumentText(text: String): String = text
        .take(800)
        .let { value -> if (value.length < text.length) "$value…" else value }

    private fun createSectionLabel(title: String): JBLabel = JBLabel(title).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(5)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun createNavigationLabel(text: String, range: TextRange): JComponent =
        createNavigationLabel(text) { navigateToRange(range) }

    private fun createNavigationLabel(text: String, onClick: () -> Unit): JComponent = JBLabel(text).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
        border = JBUI.Borders.empty(2, 4)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        addMouseListener(object : MouseAdapter() {
            private val normalForeground = foreground

            override fun mouseEntered(event: MouseEvent) {
                foreground = JBColor.namedColor("Link.hoverForeground", normalForeground)
            }

            override fun mouseExited(event: MouseEvent) {
                foreground = normalForeground
            }

            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1) onClick()
            }
        })
    }

    private fun navigateToSelectedNode() {
        val selectedIndex = nodeList.selectedIndex
        val location = currentModel.nodes.getOrNull(selectedIndex) ?: return
        navigateToRange(location.nameRange)
    }

    private fun navigateToLogicNode(location: PipelineNodeLocation) {
        val sourceFile = location.sourceFile ?: file
        if (sourceFile != file) {
            navigateToFileRange(sourceFile, location.nameRange)
            return
        }
        val index = currentModel.nodes.indexOfFirst { node -> node.name == location.name }
        if (index < 0) {
            navigateToRange(location.nameRange)
            return
        }
        selectingFromSource = true
        try {
            nodeList.selectedIndex = index
        } finally {
            selectingFromSource = false
        }
        renderSelectedNode()
    }

    private fun navigateToRange(range: TextRange) {
        if (editor.document.textLength == 0) return
        showSourceForNavigation()
        val offset = range.startOffset.coerceIn(0, editor.document.textLength)
        // 切换标签页后再定位，避免源码组件尚未挂载时光标移动被流程页覆盖。
        ApplicationManager.getApplication().invokeLater({
            if (disposed || editor.isDisposed) return@invokeLater
            editor.caretModel.moveToOffset(offset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            editor.contentComponent.requestFocusInWindow()
        }, ModalityState.any())
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
        if (targetFile == file) {
            navigateToRange(range)
            return
        }

        val requestedOffset = range.startOffset.coerceAtLeast(0)
        ReadAction.nonBlocking<NavigationPosition?> {
            val targetDocument = FileDocumentManager.getInstance().getDocument(targetFile)
            if (targetDocument == null) {
                null
            } else {
                val offset = requestedOffset.coerceIn(0, targetDocument.textLength)
                val line = targetDocument.getLineNumber(offset)
                NavigationPosition(offset, line, offset - targetDocument.getLineStartOffset(line))
            }
        }.expireWith(this).finishOnUiThread(ModalityState.any()) { position ->
            if (disposed || !targetFile.isValid) return@finishOnUiThread
            val descriptor = if (position == null) {
                OpenFileDescriptor(project, targetFile, requestedOffset)
            } else {
                OpenFileDescriptor(project, targetFile, position.line, position.column)
            }
            val targetEditors = FileEditorManager.getInstance(project).openFile(targetFile, true)
            val pipelineEditor = targetEditors
                .filterIsInstance<PipelineFileEditor>()
                .firstOrNull()
            if (pipelineEditor != null && position != null) {
                // 自定义文件编辑器隐藏了 IDEA 默认文本编辑器，必须把偏移继续传入其内部源码编辑器。
                pipelineEditor.navigateToOffset(position.offset)
            } else {
                descriptor.navigate(true)
            }
        }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private data class NavigationPosition(
        val offset: Int,
        val line: Int,
        val column: Int,
    )

    override fun dispose() {
        disposed = true
        nodeListModel.clear()
    }
}
