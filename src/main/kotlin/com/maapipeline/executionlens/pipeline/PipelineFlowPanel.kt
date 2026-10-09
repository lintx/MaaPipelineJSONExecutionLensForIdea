package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
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
        detailsPanel.add(createNavigationLabel(field.summary, field.range))
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
            val state = when {
                !reference.targetExists -> "（当前文件未定义，可能跨文件）"
                reference.isCycle -> "（回边）"
                reference.isSharedTarget -> "（共享节点）"
                else -> ""
            }
            val target = currentModel.nodes.firstOrNull { it.name == reference.targetName }
            val navigationRange = target?.nameRange ?: reference.range
            detailsPanel.add(
                createNavigationLabel(
                    "${index + 1}. $marker${reference.displayName} $state",
                    navigationRange,
                ),
            )
        }
    }

    private fun createSectionLabel(title: String): JBLabel = JBLabel(title).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(5)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun createNavigationLabel(text: String, range: TextRange): JComponent = JBLabel(text).apply {
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
                if (event.button == MouseEvent.BUTTON1) navigateToRange(range)
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

    override fun dispose() {
        disposed = true
        nodeListModel.clear()
    }
}
