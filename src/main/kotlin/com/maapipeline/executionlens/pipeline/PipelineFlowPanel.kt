package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
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
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JButton
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
    private val nodeListModel = DefaultListModel<String>()
    private val nodeList = JBList(nodeListModel)
    private val summaryLabel = JBLabel()
    private val entrySelector = JComboBox<PipelineEntryLocation>()
    private val traceButton = JButton("加载运行日志")
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
    private var selectedEntry: PipelineEntryLocation? = null
    private var refreshGeneration = 0L
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
                renderSelectedNode()
                if (!refreshing) navigateToSelectedNode()
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
                text = entry?.let { "${displayPath(it.file)} → ${it.entryName}" } ?: "（无任务入口）"
                return this
            }
        }
        entrySelector.toolTipText = "选择任务入口，并定位到对应 Pipeline 节点"
        entrySelector.addActionListener {
            if (syncingEntrySelector) return@addActionListener
            val entry = entrySelector.selectedItem as? PipelineEntryLocation ?: return@addActionListener
            selectedEntry = entry
            nodeList.repaint()
            val index = currentModel.nodes.indexOfFirst { it.name == entry.entryName }
            if (index < 0) return@addActionListener
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
                val state = nodeName?.let { currentTrace?.latestNodeStates?.get(it) }
                val reachability = if (nodeName != null && nodeName !in reachableNodeNames().orEmpty()) "○ " else ""
                text = reachability + when (state?.status) {
                    PipelineTraceStatus.SUCCEEDED -> "✓ $nodeName"
                    PipelineTraceStatus.FAILED -> "✗ $nodeName"
                    PipelineTraceStatus.STARTING -> "… $nodeName"
                    null -> nodeName
                }
                return this
            }
        }
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        detailsPanel.border = JBUI.Borders.empty(10, 12)
        summaryLabel.foreground = UIUtil.getContextHelpForeground()
        summaryLabel.border = JBUI.Borders.empty(0, 8, 5, 8)
        nodeList.fixedCellHeight = JBUI.scale(26)
        nodeList.border = JBUI.Borders.empty(3, 0)
        val navigator = JBScrollPane(nodeList).apply {
            minimumSize = Dimension(180, 0)
            border = JBUI.Borders.empty()
            viewportBorder = null
        }
        val details = JBScrollPane(detailsPanel).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        traceButton.apply {
            isFocusable = false
            toolTipText = "选择 MaaFramework debug/maafw.log，只读显示实际执行轨迹"
            addActionListener { loadTrace() }
        }
        val summaryBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            val entryPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(JBLabel("任务入口："))
                add(entrySelector)
            }
            add(entryPanel, BorderLayout.WEST)
            add(summaryLabel, BorderLayout.CENTER)
            add(traceButton, BorderLayout.EAST)
        }
        val splitPane = OnePixelSplitter(false, 0.28f).apply {
            firstComponent = navigator
            secondComponent = details
            border = JBUI.Borders.emptyTop(2)
        }
        add(summaryBar, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)
        editor.document.addDocumentListener(documentListener, this)
        refreshFromDocument(editor.document)
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
                    entrySelector.isEnabled = model.pipelineEntries.isNotEmpty()
                    selectedEntry = restoredEntry
                    if (restoredEntry != null) {
                        entrySelector.selectedItem = restoredEntry
                    }
                } finally {
                    syncingEntrySelector = false
                }
                val preferredNodeName = restoredEntry?.entryName ?: previousNodeName
                val preferredIndex = preferredNodeName?.let { name ->
                    model.nodes.indexOfFirst { it.name == name }
                } ?: -1
                if (model.nodes.isNotEmpty()) {
                    nodeList.selectedIndex = when {
                        preferredIndex >= 0 -> preferredIndex
                        else -> selectedIndex.coerceIn(0, model.nodes.lastIndex)
                    }
                }
                val suffix = if (model.hasSyntaxErrors) "（JSON 语法暂不可完整解析）" else ""
                val generatedSuffix = if (model.isGenerated) "（生成文件，编辑已禁用）" else ""
                summaryLabel.text = buildString {
                    append("当前文件：${model.nodes.size} 个 Pipeline 节点 $suffix$generatedSuffix")
                    currentTrace?.let { trace ->
                        append("　日志：${trace.events.size} 个事件，已执行 ${trace.executedNodeNames.size} 个节点")
                    }
                }
                nodeList.repaint()
                refreshing = false
                renderSelectedNode()
            }
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    private fun renderSelectedNode() {
        detailsPanel.removeAll()
        val location = currentModel.nodes.getOrNull(nodeList.selectedIndex)
        if (location == null) {
            detailsPanel.add(JBLabel("请选择一个节点查看语义摘要"))
        } else {
            detailsPanel.add(JBLabel(location.name).apply {
                font = font.deriveFont(Font.BOLD)
                foreground = UIUtil.getLabelForeground()
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            val reachable = reachableNodeNames()
            if (reachable != null && location.name !in reachable) {
                detailsPanel.add(JBLabel("路径分析：从当前任务入口未解析到此节点（仅计算当前文件内的静态路径）").apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            }
            location.description?.let { detailsPanel.add(JBLabel("说明：$it")) }
            addFieldSummary("识别", location.recognition)
            addFieldSummary("动作", location.action)
            addFieldSummary("锚点", location.anchor)
            addNodeDiagnostics(location)
            addTaskOverrideSummary(location)
            addTraceSummary(location)
            detailsPanel.add(JSeparator())
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            addControlFlowExplanation(effectiveNode(location))
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            addReferences("后继", location.next)
            addReferences("错误后继", location.onError)
            addReferences("嵌套节点引用", location.embeddedReferences)
        }
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun addControlFlowExplanation(location: PipelineNodeLocation) {
        detailsPanel.add(createSectionLabel("逻辑控制流"))
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

    private fun addReferences(title: String, references: List<PipelineReference>) {
        detailsPanel.add(createSectionLabel(title))
        if (references.isEmpty()) {
            detailsPanel.add(JBLabel("（无）").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
            return
        }
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
            detailsPanel.add(
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
        val override = currentModel.pipelineOverrides.firstOrNull {
            it.file == entry.file && it.targetName == location.name
        }
        if (override == null) {
            detailsPanel.add(JBLabel("当前任务未覆盖此节点，所有字段使用默认值").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
            return
        }
        if (override.fields.isEmpty()) {
            detailsPanel.add(JBLabel("当前任务存在覆盖对象，但没有可展示的字段").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
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
            detailsPanel.add(
                createNavigationLabel("覆盖值：${field.summary}（点击定位）") {
                    navigateToFileRange(override.file, field.range)
                },
            )
            detailsPanel.add(JBLabel("有效值：${field.summary}").apply {
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }
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
        val entry = selectedEntry ?: return location
        val override = currentModel.pipelineOverrides.firstOrNull {
            it.file == entry.file && it.targetName == location.name
        } ?: return location
        return override.fields.fold(location) { effective, field ->
            when (field.name) {
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
        val nodesByName = currentModel.nodes.associateBy { it.name }
        val root = nodesByName[entry.entryName] ?: return emptySet()
        val reachable = linkedSetOf<String>()
        fun visit(node: PipelineNodeLocation) {
            if (!reachable.add(node.name)) return
            effectiveNode(node).next
                .plus(effectiveNode(node).onError)
                .mapNotNull { reference ->
                    reference.target
                        ?.takeIf { target -> target.file == file }
                        ?.let { target -> nodesByName[target.name] }
                }
                .forEach(::visit)
        }
        visit(root)
        return reachable
    }

    private fun addTraceSummary(location: PipelineNodeLocation) {
        val trace = currentTrace ?: return
        val events = trace.eventsForNode(location.name)
        if (events.isEmpty()) return
        detailsPanel.add(createSectionLabel("运行轨迹"))
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
        summaryLabel.text = "当前文件：${currentModel.nodes.size} 个 Pipeline 节点　日志：${trace.events.size} 个事件，已执行 ${trace.executedNodeNames.size} 个节点"
        nodeList.repaint()
        renderSelectedNode()
    }

    private fun openTraceFile(lineNumber: Int) {
        val trace = currentTrace ?: return
        OpenFileDescriptor(project, trace.file, lineNumber.coerceAtLeast(1) - 1, 0).navigate(true)
    }

    private fun ensureEditable(): Boolean {
        if (currentModel.isGenerated) {
            Messages.showWarningDialog(project, "当前文件由生成器维护，插件不会直接修改它。", "无法编辑 Pipeline")
            return false
        }
        if (currentModel.version != editor.document.modificationStamp) {
            Messages.showWarningDialog(
                project,
                "源码在流程视图刷新前发生了变化，请重新选择节点后再编辑。",
                "Pipeline 索引已过期",
            )
            return false
        }
        return true
    }

    private fun editStringField(field: PipelineFieldLocation) {
        if (!ensureEditable()) return
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
        if (range.startOffset < 0 || range.endOffset > editor.document.textLength) return
        val oldValue = editor.document.getText(range)
        if (oldValue == replacement) return
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
        val range = field.valueRange
        if (range.startOffset < 0 || range.endOffset > editor.document.textLength) return
        val initialText = editor.document.getText(range)
        val dialog = PipelineJsonFieldDialog(project, field.name, initialText)
        if (!dialog.showAndGet()) return
        val replacement = dialog.value
        if (replacement.isEmpty() || replacement == initialText) return
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
