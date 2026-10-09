package com.maapipeline.executionlens.pipeline

import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane

internal class PipelineEditorViews(
    private val source: JComponent,
    private val flow: JComponent,
) : JPanel(BorderLayout()) {
    private val sourcePage = JPanel(BorderLayout())
    private val flowPage = JPanel(BorderLayout())
    private val splitPage = JPanel(BorderLayout())
    private val splitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
        resizeWeight = 0.5
        border = null
    }
    val tabs = JTabbedPane().apply {
        addTab("源码", sourcePage)
        addTab("流程", flowPage)
        addTab("分栏", splitPage)
    }

    init {
        // 标签栏和实际页面属于同一主体，避免空标签页在顶部额外占用高度。
        add(tabs, BorderLayout.CENTER)
        tabs.addChangeListener { showSelectedView() }
        showSelectedView()
    }

    fun showSourceForNavigation() {
        if (tabs.selectedIndex == 1) tabs.selectedIndex = 0
    }

    private fun showSelectedView() {
        sourcePage.removeAll()
        flowPage.removeAll()
        splitPage.removeAll()
        splitter.leftComponent = null
        splitter.rightComponent = null
        when (tabs.selectedIndex) {
            0 -> sourcePage.add(source, BorderLayout.CENTER)
            1 -> flowPage.add(flow, BorderLayout.CENTER)
            2 -> {
                splitter.leftComponent = source
                splitter.rightComponent = flow
                splitPage.add(splitter, BorderLayout.CENTER)
                splitter.setDividerLocation(0.5)
            }
        }
        revalidate()
        repaint()
    }
}
