package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

internal class PipelineReferenceDialog(project: Project, private val nodes: List<PipelineNodeLocation>,
    initial: String = "") : DialogWrapper(project) {
    private val input = JBTextField(initial.replace(Regex("^(?:\\[(?:JumpBack|Anchor)])+"), ""))
    private val jump = JBCheckBox("JumpBack", initial.contains("[JumpBack]"))
    private val anchor = JBCheckBox("Anchor", initial.contains("[Anchor]"))
    private val model = DefaultListModel<Pair<String, String>>()
    private val candidates = JBList(model)
    private val nodeCandidates = nodes.map { it.name to it.description.orEmpty() }
    private val anchorCandidates = nodes.flatMap { node -> node.anchorDefinitions.map { it.name to node.name } }.distinctBy { it.first }
    val value: String get() = (if (jump.isSelected) "[JumpBack]" else "") +
        (if (anchor.isSelected) "[Anchor]" else "") + input.text.trim()

    init {
        title = if (initial.isEmpty()) "添加节点引用" else "修改节点引用"
        setOKButtonText(if (initial.isEmpty()) "添加" else "应用")
        candidates.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                super.getListCellRendererComponent(list, value, index, selected, focus)
                @Suppress("UNCHECKED_CAST") val item = value as? Pair<String, String>
                text = item?.let { it.first + it.second.takeIf(String::isNotBlank)?.let { description -> "   $description" }.orEmpty() }.orEmpty()
                return this
            }
        }
        candidates.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                candidates.selectedValue?.let { input.text = it.first }
                if (event.clickCount == 2) doOKAction()
            }
        })
        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = refresh()
        })
        input.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(event: java.awt.event.KeyEvent) {
                if (event.keyCode == java.awt.event.KeyEvent.VK_DOWN && model.size > 0) {
                    candidates.requestFocusInWindow()
                    candidates.selectedIndex = 0
                    event.consume()
                }
            }
        })
        candidates.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(event: java.awt.event.KeyEvent) {
                if (event.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                    candidates.selectedValue?.let { input.text = it.first }
                    input.requestFocusInWindow()
                    event.consume()
                }
            }
        })
        anchor.addActionListener { refresh() }
        init()
        refresh()
    }

    private fun refresh() {
        val values = if (anchor.isSelected) anchorCandidates else nodeCandidates
        val filtered = values.asSequence().filter { PipelineTaskContext.matches(input.text, it.first, it.second) }.take(100).toList()
        model.removeAllElements()
        model.addAll(filtered)
    }

    override fun getPreferredFocusedComponent(): JComponent = input
    override fun doValidate(): ValidationInfo? = if (input.text.isBlank()) ValidationInfo("请输入节点或 Anchor 名称", input) else null
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        preferredSize = JBUI.size(580, 290)
        add(JPanel(BorderLayout()).apply {
            add(input, BorderLayout.CENTER)
            add(JPanel().apply { add(jump); add(anchor) }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(candidates), BorderLayout.CENTER)
    }
}
