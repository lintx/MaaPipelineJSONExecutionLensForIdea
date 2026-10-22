package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.SearchTextField
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.speedSearch.ListWithFilter
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

internal fun <Item> pipelineSearchPopup(
    items: List<Item>, owner: JComponent, title: String,
    display: (Item) -> String, searchable: (Item) -> String = display,
    selected: Item? = null, choose: (Item) -> Unit,
): JBPopup {
    val width = owner.width.takeIf { it > 0 } ?: JBUI.scale(420)
    val list = JBList(items)
    fun search(component: Component?): SearchTextField? = when (component) {
        is SearchTextField -> component
        is Container -> component.components.firstNotNullOfOrNull(::search)
        else -> null
    }
    val builder = object : PopupChooserBuilder<Item>(list) {
        override fun getPreferableFocusComponent(): JComponent {
            val field = search(scrollPane.parent)?.textEditor ?: return super.getPreferableFocusComponent()
            field.isFocusable = true
            field.emptyText.text = "输入以筛选…"
            return field
        }
    }
    val popup = builder.setTitle(title).setFilterAlwaysVisible(true).setRequestFocus(true)
        .setAutoPackHeightOnFiltering(false).setResizable(false)
        .setNamerForFiltering { searchable(it) }
        .setRenderer(object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int,
                selected: Boolean, focus: Boolean): Component {
                super.getListCellRendererComponent(list, value, index, selected, focus)
                @Suppress("UNCHECKED_CAST")
                text = value?.let { display(it as Item) }.orEmpty()
                toolTipText = text
                border = JBUI.Borders.empty(5, 10)
                preferredSize = Dimension((width - JBUI.scale(24)).coerceAtLeast(1), preferredSize.height)
                return this
            }
        }).apply { if (selected != null) setSelectedValue(selected, true) }
        .setVisibleRowCount(minOf(items.size.coerceAtLeast(1), 12))
        .setItemChosenCallback { choose(it) }.createPopup()
    popup.setSize(Dimension(width, popup.content.preferredSize.height.coerceAtMost(JBUI.scale(400))))
    search(popup.content)?.textEditor?.let { field ->
        field.isFocusable = true
        val filter = builder.scrollPane.parent as ListWithFilter<*>
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                SwingUtilities.invokeLater {
                    if (!popup.isDisposed && filter.speedSearch.filter != field.text) {
                        filter.speedSearch.updatePattern(field.text)
                        filter.speedSearch.update()
                    }
                }
            }
        })
        field.addActionListener {
            list.selectedValue?.let { value -> popup.cancel(); choose(value) }
        }
        // 原生过滤器默认由列表接收键盘；聚焦输入框后仍沿用列表的上下选择动作。
        listOf(KeyEvent.VK_UP to "selectPreviousRow", KeyEvent.VK_DOWN to "selectNextRow").forEach { (key, action) ->
            field.inputMap.put(KeyStroke.getKeyStroke(key, 0), action)
            field.actionMap.put(action, object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    list.actionMap.get(action)?.actionPerformed(ActionEvent(list, ActionEvent.ACTION_PERFORMED, action))
                }
            })
        }
    }
    return popup
}
