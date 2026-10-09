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
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class PipelineFlowPanel(
    private val project: Project,
    private val file: VirtualFile,
    private val editor: Editor,
    private val showSourceForNavigation: () -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val nodeListModel = DefaultListModel<String>()
    private val nodeList = JBList(nodeListModel)
    private val summaryLabel = JBLabel()
    private var currentModel = PipelineDocumentModel(file, -1L, emptyList(), hasSyntaxErrors = true)
    private var disposed = false
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            refreshFromDocument(event.document)
        }
    }

    init {
        border = JBUI.Borders.empty(8)
        nodeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        nodeList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) navigateToSelectedNode()
        }
        add(summaryLabel, BorderLayout.NORTH)
        add(JBScrollPane(nodeList), BorderLayout.CENTER)
        editor.document.addDocumentListener(documentListener, this)
        refreshFromDocument(editor.document)
    }

    private fun refreshFromDocument(document: Document) {
        PsiDocumentManager.getInstance(project).performWhenAllCommitted {
            if (disposed) return@performWhenAllCommitted
            val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> {
                PipelineDocumentIndexer.index(project, file)
            }
            currentModel = model
            nodeListModel.removeAllElements()
            model.nodes.forEach { nodeListModel.addElement(it.name) }
            val suffix = if (model.hasSyntaxErrors) "（JSON 语法暂不可完整解析）" else ""
            summaryLabel.text = "当前文件：${model.nodes.size} 个 Pipeline 节点 $suffix"
        }
    }

    private fun navigateToSelectedNode() {
        val selectedIndex = nodeList.selectedIndex
        val location = currentModel.nodes.getOrNull(selectedIndex) ?: return
        if (currentModel.version != editor.document.modificationStamp) return
        showSourceForNavigation()
        editor.caretModel.moveToOffset(location.nameRange.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.contentComponent.requestFocusInWindow()
    }

    override fun dispose() {
        disposed = true
        nodeListModel.clear()
    }
}
