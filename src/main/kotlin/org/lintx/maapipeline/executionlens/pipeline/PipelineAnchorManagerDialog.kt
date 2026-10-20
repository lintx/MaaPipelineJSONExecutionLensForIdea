package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.json.JsonFileType
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.TextRange
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Action
import javax.swing.AbstractAction

internal class PipelineAnchorManagerDialog(
    project: Project,
    private val rows: List<PipelineAnchorManagerRow>,
    private val onNavigate: (VirtualFile, TextRange) -> Unit,
    private val onAddDefinition: () -> Unit,
    private val onRename: (PipelineAnchorManagerRow) -> Unit,
    private val onRemove: (PipelineAnchorManagerRow) -> Unit,
    private val onEditField: (PipelineAnchorManagerRow) -> Unit,
) : DialogWrapper(project, true) {
    private val rowList = JBList(rows).apply {
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val row = value as? PipelineAnchorManagerRow
                text = row?.let {
                    buildString {
                        append(it.name)
                        append("  · ${it.definitionText}  · ${it.targetText}  · ${it.references.size} 个引用")
                        it.warningText?.let { warning -> append("  · $warning") }
                    }
                }.orEmpty()
                return this
            }
        }
    }
    private val referenceModel = DefaultListModel<PipelineAnchorUse>()
    private val referenceList = JBList(referenceModel).apply {
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? PipelineAnchorUse)?.let { "${it.ownerNodeName} · ${it.kind} · ${it.file.path}" }.orEmpty()
                return this
            }
        }
    }
    private val selectionInfo = JBLabel()
    private val runtimeInfo = JBLabel()
    private val definitionButton = JButton("定位定义")
    private val targetButton = JButton("定位目标")
    private val renameButton = JButton("重命名此定义")
    private val removeButton = JButton("删除此定义")
    private val editButton = JButton("编辑绑定字段")

    init {
        title = "Pipeline Anchor 总览"
        setOKButtonText("关闭")
        init()
        rowList.addListSelectionListener { updateSelection() }
        rowList.selectedIndex = if (rows.isEmpty()) -1 else 0
        updateSelection()
    }

    private fun updateSelection() {
        val row = rowList.selectedValue
        referenceModel.clear()
        row?.references?.forEach(referenceModel::addElement)
        selectionInfo.text = row?.definitionFile?.path ?: "没有静态定义；可定位下方引用"
        runtimeInfo.text = row?.runtimeText ?: ""
        definitionButton.isEnabled = row?.definitionFile != null
        targetButton.isEnabled = row?.targetFile != null && row.targetRange != null
        renameButton.isEnabled = row?.canEdit == true
        removeButton.isEnabled = row?.canEdit == true
        editButton.isEnabled = row?.canEdit == true
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(8)
        add(JBLabel("Anchor 是运行时绑定关系；这里汇总定义、引用和静态解析风险。"), BorderLayout.NORTH)
        add(OnePixelSplitter(true, 0.55f).apply {
            firstComponent = JBScrollPane(rowList)
            secondComponent = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JPanel(java.awt.GridLayout(0, 1)).apply {
                    add(selectionInfo)
                    add(runtimeInfo)
                    add(JBLabel("引用位置（双击或按 Enter 跳转）："))
                }, BorderLayout.NORTH)
                add(JBScrollPane(referenceList), BorderLayout.CENTER)
            }
            preferredSize = Dimension(JBUI.scale(920), JBUI.scale(460))
        }, BorderLayout.CENTER)
        referenceList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2) navigateToReference()
            }
        })
        referenceList.registerKeyboardAction(
            { navigateToReference() },
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(definitionButton.apply {
                isFocusable = false
                addActionListener {
                    val row = rowList.selectedValue ?: return@addActionListener
                    val targetFile = row.definitionFile ?: return@addActionListener
                    val targetRange = row.definitionRange ?: return@addActionListener
                    onNavigate(targetFile, targetRange)
                }
            })
            add(targetButton.apply {
                isFocusable = false
                addActionListener {
                    val row = rowList.selectedValue ?: return@addActionListener
                    onNavigate(row.targetFile ?: return@addActionListener, row.targetRange ?: return@addActionListener)
                }
            })
            add(renameButton.apply {
                isFocusable = false
                addActionListener {
                    val row = rowList.selectedValue ?: return@addActionListener
                    close(OK_EXIT_CODE)
                    onRename(row)
                }
            })
            add(removeButton.apply {
                isFocusable = false
                addActionListener {
                    val row = rowList.selectedValue ?: return@addActionListener
                    close(OK_EXIT_CODE)
                    onRemove(row)
                }
            })
            add(editButton.apply {
                isFocusable = false
                addActionListener {
                    val row = rowList.selectedValue ?: return@addActionListener
                    close(OK_EXIT_CODE)
                    onEditField(row)
                }
            })
            add(JButton("新增到当前节点").apply {
                isFocusable = false
                addActionListener {
                    close(OK_EXIT_CODE)
                    onAddDefinition()
                }
            })
        }, BorderLayout.SOUTH)
    }

    private fun navigateToReference() {
        val reference = referenceList.selectedValue ?: return
        onNavigate(reference.file, reference.range)
    }
}

internal class PipelineConflictDialog(
    private val project: Project,
    private val baseline: String,
    private val current: String,
    private val proposed: String,
    private val onApplyMerged: ((String) -> Unit)? = null,
) : DialogWrapper(project, true) {
    init {
        title = "Pipeline 外部修改冲突"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(8)
        add(JBLabel("文档在操作期间发生了变化，自动写回已停止；可审阅差异或打开三方合并器。"), BorderLayout.NORTH)
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "外部修改冲突",
            listOf(current, baseline, proposed).map { factory.create(it, JsonFileType.INSTANCE) },
            listOf("当前文档", "操作开始时的基线", "拟修改结果"),
        )
        val diffPanel = DiffManager.getInstance().createRequestPanel(null, disposable, null)
        diffPanel.setRequest(request)
        add(diffPanel.component.apply { preferredSize = JBUI.size(1100, 550) }, BorderLayout.CENTER)
    }

    override fun createLeftSideActions(): Array<Action> = if (onApplyMerged == null) emptyArray() else arrayOf(
        object : AbstractAction("打开三方合并器") {
            override fun actionPerformed(event: java.awt.event.ActionEvent) {
                close(CANCEL_EXIT_CODE)
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    val output = EditorFactory.getInstance().createDocument(current)
                    val request = DiffRequestFactory.getInstance().createMergeRequest(
                        project, JsonFileType.INSTANCE, output,
                        listOf(current, baseline, proposed),
                        "合并 Pipeline 外部修改", listOf("当前文档", "基线", "拟修改结果"),
                    ) { result ->
                        if (result != MergeResult.CANCEL) {
                            val merged = when (result) {
                                MergeResult.LEFT -> current
                                MergeResult.RIGHT -> proposed
                                else -> output.text
                            }
                            onApplyMerged.invoke(merged)
                        }
                    }
                    DiffManager.getInstance().showMerge(project, request)
                }, ModalityState.nonModal())
            }
        },
    )
}
