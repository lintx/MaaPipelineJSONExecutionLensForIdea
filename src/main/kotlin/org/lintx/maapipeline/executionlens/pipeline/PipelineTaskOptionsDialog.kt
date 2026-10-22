package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class PipelineTaskOptionsDialog(project: Project, private val entry: PipelineEntryLocation,
    selected: Map<String, List<String>>, inputValues: Map<String, String>) : DialogWrapper(project) {
    private val selectedValues = selected.mapValues { it.value.toList() }.toMutableMap()
    private val textValues = inputValues.toMutableMap()
    private val controls = linkedMapOf<String, JPanel>()
    private val inputFields = linkedMapOf<String, Pair<PipelineTaskInput, JBTextField>>()
    private val query = JBTextField()
    val selections: Map<String, List<String>> get() = selectedValues
    val inputs: Map<String, String> get() = textValues + inputFields.mapValues { it.value.second.text }
    private val options = buildList {
        val visited = mutableSetOf<String>()
        val byName = entry.taskOptions.associateBy { it.name }
        fun visit(name: String) {
            if (!visited.add(name)) return
            val option = byName[name] ?: return
            add(option)
            (option.options + option.cases.flatMap { it.options }).forEach(::visit)
        }
        entry.optionNames.forEach(::visit)
    }

    init { title = "任务选项 · ${entry.label}"; setOKButtonText("应用到流程视图"); init(); updateActive() }

    override fun createCenterPanel(): JComponent {
        val body = JPanel(com.intellij.ui.components.panels.VerticalLayout(0))
        body.add(JBLabel("仅当前分支的选项生效；不会修改任务 JSON。").apply { border = JBUI.Borders.emptyBottom(8) })
        options.forEach { option ->
            val row = JPanel(BorderLayout(8, 4)).apply { border = JBUI.Borders.empty(5) }
            row.add(JBLabel("${option.label} · ${option.name}"), BorderLayout.NORTH)
            if (option.cases.isNotEmpty()) {
                val chosen = selectedValues[option.name] ?: option.defaults
                val renderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int,
                        selected: Boolean, focus: Boolean): Component {
                        super.getListCellRendererComponent(list, value, index, selected, focus)
                        text = (value as? PipelineOptionCase)?.let { "${it.label} [${it.name}]" }.orEmpty()
                        return this
                    }
                }
                if (option.type == "multiselect" || chosen.size > 1) {
                    val list = JBList(option.cases).apply {
                        cellRenderer = renderer
                        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                        visibleRowCount = minOf(option.cases.size, 5)
                        selectedIndices = option.cases.indices.filter { option.cases[it].name in chosen }.toIntArray()
                        addListSelectionListener { event ->
                            if (!event.valueIsAdjusting) {
                                this@PipelineTaskOptionsDialog.selectedValues[option.name] = selectedValuesList.map { it.name }
                                updateActive()
                            }
                        }
                    }
                    com.intellij.ui.ListSpeedSearch(list) { "${it.name} ${it.label} ${it.description}" }
                    row.add(JBScrollPane(list), BorderLayout.CENTER)
                } else {
                    val chooser = javax.swing.JButton(option.cases.firstOrNull { it.name in chosen }?.label ?: "选择…")
                    chooser.addActionListener {
                        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(option.cases)
                            .setTitle(option.label).setRenderer(renderer).setFilterAlwaysVisible(true)
                            .setNamerForFiltering { "${it.name} ${it.label} ${it.description}" }
                            .setItemChosenCallback { item ->
                                selectedValues[option.name] = listOf(item.name)
                                chooser.text = item.label
                                updateActive()
                            }.createPopup().showUnderneathOf(chooser)
                    }
                    row.add(chooser, BorderLayout.CENTER)
                }
            }
            if (option.inputs.isNotEmpty()) {
                val fields = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
                option.inputs.forEach { input ->
                    val key = "${option.path}/${input.name}"
                    val field = JBTextField(textValues[key] ?: input.defaultValue)
                    inputFields[key] = input to field
                    fields.add(JPanel(BorderLayout(8, 0)).apply {
                        add(JBLabel(input.label), BorderLayout.WEST)
                        add(field, BorderLayout.CENTER)
                    })
                }
                row.add(fields, BorderLayout.SOUTH)
            }
            controls[option.path] = row
            body.add(row)
        }
        query.emptyText.text = "搜索选项 ID、名称、说明或选项值"
        query.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) {
                options.forEach { option ->
                    controls[option.path]?.isVisible = PipelineTaskContext.matches(query.text, option.name, option.label,
                        option.description, option.cases.joinToString(" ") { "${it.name} ${it.label} ${it.description}" })
                }
                body.revalidate(); body.repaint()
            }
        })
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            preferredSize = Dimension(JBUI.scale(620), JBUI.scale(470))
            add(query, BorderLayout.NORTH)
            add(JBScrollPane(body), BorderLayout.CENTER)
        }
    }

    private fun updateActive() {
        val active = PipelineTaskContext.activePaths(entry.contextPath, entry.optionNames, entry.taskOptions, selectedValues)
        fun enable(component: Component, enabled: Boolean) {
            component.isEnabled = enabled
            if (component is java.awt.Container) component.components.forEach { enable(it, enabled) }
        }
        controls.forEach { (path, row) -> enable(row, path in active) }
    }

    override fun doValidate(): ValidationInfo? {
        for ((_, pair) in inputFields) {
            val (input, field) = pair
            if (!field.isEnabled) continue
            val value = field.text
            val patternValid = input.verify.isEmpty() || runCatching { Regex(input.verify).matches(value) }.getOrDefault(false)
            val typeValid = when (input.pipelineType) {
                "int" -> value.toLongOrNull() != null
                "float" -> value.toDoubleOrNull()?.isFinite() == true
                "bool" -> value.toBooleanStrictOrNull() != null
                else -> true
            }
            if (!patternValid || !typeValid) return ValidationInfo("${input.label} 的值不符合格式或类型要求", field)
        }
        return null
    }
}
