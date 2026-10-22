package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

internal class PipelineFlowSearch(private val rows: PipelineRowsPanel) : JPanel(BorderLayout(6, 0)) {
    private val query = SearchTextField(false)
    private val count = JBLabel()
    private var matches = emptyList<String>()
    private var current = -1

    init {
        isVisible = false
        isOpaque = false
        query.textEditor.emptyText.text = "查找流程"
        add(query, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(count)
            fun button(icon: javax.swing.Icon, tooltip: String, action: () -> Unit) {
                add(JButton(icon).apply {
                    toolTipText = tooltip
                    isFocusable = false
                    addActionListener { action() }
                })
            }
            button(AllIcons.Actions.PreviousOccurence, "上一个匹配") { move(-1) }
            button(AllIcons.Actions.NextOccurence, "下一个匹配") { move(1) }
            button(AllIcons.Actions.Close, "关闭查找") { close() }
        }, BorderLayout.EAST)
        query.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) { refresh(); if (matches.isNotEmpty()) move(1) }
        })
        query.textEditor.addActionListener { move(1) }
    }

    fun install(scope: JComponent, parent: Disposable) {
        fun register(id: String, action: () -> Unit) {
            object : DumbAwareAction() {
                override fun actionPerformed(event: AnActionEvent) = action()
            }.registerCustomShortcutSet(ActionManager.getInstance().getAction(id).shortcutSet, scope, parent)
        }
        register(IdeActions.ACTION_FIND, ::open)
        register(IdeActions.ACTION_FIND_NEXT) { if (!isVisible) open() else move(1) }
        register(IdeActions.ACTION_FIND_PREVIOUS) { if (!isVisible) open() else move(-1) }
        object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) = close()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)), this, parent)
    }

    fun open() { isVisible = true; revalidate(); query.textEditor.requestFocusInWindow(); query.textEditor.selectAll(); refresh() }
    private fun close() { isVisible = false; rows.findRows(""); revalidate(); rows.requestFocusInWindow() }
    fun refresh() {
        val previous = matches.getOrNull(current)
        matches = rows.findRows(if (isVisible) query.text else "")
        current = matches.indexOf(previous)
        updateCount()
    }
    private fun move(direction: Int) {
        if (matches.isEmpty()) return
        current = if (current < 0) { if (direction > 0) 0 else matches.lastIndex }
            else Math.floorMod(current + direction, matches.size)
        rows.activateRow(matches[current])
        rows.followPlaybackRow(matches[current])
        updateCount()
    }
    private fun updateCount() { count.text = if (matches.isEmpty()) "0" else "${current + 1}/${matches.size}" }
}
