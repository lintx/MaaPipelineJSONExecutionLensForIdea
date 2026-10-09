package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ReadAction
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.ListSelectionModel
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
    private val detailsPanel = JPanel()
    private var currentModel = PipelineDocumentModel(file, -1L, emptyList(), hasSyntaxErrors = true)
    private var disposed = false
    private var refreshing = false
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
        }
        val splitPane = OnePixelSplitter(false, 0.28f).apply {
            firstComponent = navigator
            secondComponent = details
            border = JBUI.Borders.emptyTop(2)
        }
        add(summaryLabel, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)
        editor.document.addDocumentListener(documentListener, this)
        refreshFromDocument(editor.document)
    }

    private fun refreshFromDocument(document: Document) {
        PsiDocumentManager.getInstance(project).performWhenAllCommitted {
            if (disposed) return@performWhenAllCommitted
            val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> {
                PipelineDocumentIndexer.index(project, file)
            }
            val selectedIndex = nodeList.selectedIndex
            refreshing = true
            currentModel = model
            nodeListModel.removeAllElements()
            model.nodes.forEach { nodeListModel.addElement(it.name) }
            if (model.nodes.isNotEmpty()) {
                nodeList.selectedIndex = selectedIndex.coerceIn(0, model.nodes.lastIndex)
            }
            val suffix = if (model.hasSyntaxErrors) "（JSON 语法暂不可完整解析）" else ""
            summaryLabel.text = "当前文件：${model.nodes.size} 个 Pipeline 节点 $suffix"
            refreshing = false
            renderSelectedNode()
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
            location.description?.let { detailsPanel.add(JBLabel("说明：$it")) }
            addFieldSummary("识别", location.recognition)
            addFieldSummary("动作", location.action)
            addFieldSummary("锚点", location.anchor)
            detailsPanel.add(JSeparator())
            detailsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            addReferences("后继", location.next)
            addReferences("错误后继", location.onError)
        }
        detailsPanel.revalidate()
        detailsPanel.repaint()
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
        if (field.isString) {
            row.add(JButton("编辑").apply {
                toolTipText = "只替换当前字符串值，保留 JSON 其它内容"
                isFocusable = false
                addActionListener { editStringField(field) }
            }, BorderLayout.EAST)
        }
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
                if (!reference.targetExists) add("项目中未定义")
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
        card.add(
            createNavigationLabel(
                "${index + 1}. $marker${reference.displayName} $state",
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
        if (allowReorder) {
            val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
            }
            controls.add(createReorderButton("↑", "将此后继前移", index > 0) {
                moveNextReference(index, -1)
            })
            controls.add(createReorderButton("↓", "将此后继后移", index < referenceCount - 1) {
                moveNextReference(index, 1)
            })
            card.add(controls, BorderLayout.EAST)
        }
        return card
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

    private fun editStringField(field: PipelineFieldLocation) {
        if (currentModel.version != editor.document.modificationStamp) return
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
        if (currentModel.version != editor.document.modificationStamp) return

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

    private fun moveNextReference(index: Int, direction: Int) {
        val node = currentModel.nodes.getOrNull(nodeList.selectedIndex) ?: return
        val targetIndex = index + direction
        if (targetIndex !in node.next.indices) return
        if (currentModel.version != editor.document.modificationStamp) return

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
        if (currentModel.version != editor.document.modificationStamp) return
        showSourceForNavigation()
        editor.caretModel.moveToOffset(range.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.contentComponent.requestFocusInWindow()
    }

    private fun navigateToReference(reference: PipelineReference) {
        val target = reference.target
        if (target != null && target.file != file) {
            OpenFileDescriptor(project, target.file, target.nameRange.startOffset).navigate(true)
            return
        }
        navigateToRange(target?.nameRange ?: reference.range)
    }

    override fun dispose() {
        disposed = true
        nodeListModel.clear()
    }
}
