package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.speedSearch.FilteringListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import java.util.Locale

internal class PipelineTaskChooserDialog(project: Project, private val entries: List<PipelineEntryLocation>,
    private val recent: List<String>, initialQuery: String = "") : DialogWrapper(project) {
    private val query = JBTextField(initialQuery)
    private val recentRanks = recent.withIndex().associate { it.value to it.index }
    private val sortedEntries = entries.sortedWith(compareBy<PipelineEntryLocation> {
        recentRanks[it.stableId] ?: Int.MAX_VALUE
    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    private val searchTexts = entries.associate { entry -> entry.stableId to
        listOf(entry.searchText, entry.taskId, entry.label, entry.entryName).joinToString(" ").lowercase(Locale.ROOT) }
    private val model = FilteringListModel(CollectionListModel(sortedEntries))
    private val list = JBList(model)
    private val wordSeparator = Regex("\\s+")
    internal val chooserContent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        preferredSize = Dimension(JBUI.scale(680), JBUI.scale(460))
        add(query, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
    }
    val selectedEntry: PipelineEntryLocation? get() = list.selectedValue

    init {
        title = "选择 Pipeline 任务"
        setOKButtonText("查看流程")
        list.cellRenderer = object : ColoredListCellRenderer<PipelineEntryLocation>() {
            override fun customizeCellRenderer(list: JList<out PipelineEntryLocation>, entry: PipelineEntryLocation?,
                index: Int, selected: Boolean, hasFocus: Boolean) {
                if (entry == null) return
                if (entry.stableId in recentRanks) append("最近 · ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(entry.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${entry.taskId} → ${entry.entryName} · ${entry.file.name}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                entry.issue?.let { append("  $it", SimpleTextAttributes.ERROR_ATTRIBUTES) }
                border = JBUI.Borders.empty(6, 8)
                toolTipText = entry.description.ifEmpty { entry.file.path }
            }
        }
        list.fixedCellHeight = JBUI.scale(36)
        list.emptyText.text = "没有匹配的任务"
        Disposer.register(disposable) { model.dispose() }
        query.emptyText.text = "搜索任务 ID、名称、注释、UI 文本或入口"
        query.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = filter()
            override fun removeUpdate(event: DocumentEvent) = filter()
            override fun changedUpdate(event: DocumentEvent) = filter()
        })
        list.addListSelectionListener { isOKActionEnabled = list.selectedValue != null }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2 && list.selectedValue != null) doOKAction()
            }
        })
        init()
        filter()
    }

    private fun filter() {
        val selected = list.selectedValue?.stableId
        val words = query.text.trim().lowercase(Locale.ROOT).split(wordSeparator).filter(String::isNotEmpty)
        model.setFilter { entry -> words.all { it in searchTexts.getValue(entry.stableId) } }
        val restored = (0 until model.size).firstOrNull { model.getElementAt(it).stableId == selected }
        if (model.size > 0) list.selectedIndex = restored ?: 0
        isOKActionEnabled = list.selectedValue != null
    }

    override fun getPreferredFocusedComponent(): JComponent = query
    override fun createCenterPanel(): JComponent = chooserContent
}
