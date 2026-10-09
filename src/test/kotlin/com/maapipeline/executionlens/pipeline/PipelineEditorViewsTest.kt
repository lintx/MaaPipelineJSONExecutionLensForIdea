package com.maapipeline.executionlens.pipeline

import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PipelineEditorViewsTest {
    @Test
    fun flowPageUsesTheBodyInsteadOfAnEmptyHeaderPage() {
        SwingUtilities.invokeAndWait {
            val source = JPanel()
            val flow = JPanel()
            val views = PipelineEditorViews(source, flow)
            views.setSize(325, 700)
            views.tabs.selectedIndex = 1
            views.doLayout()
            views.tabs.doLayout()
            val page = views.tabs.selectedComponent as JPanel
            page.doLayout()

            assertSame(views.tabs, (views.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
            assertSame(page, flow.parent)
            assertEquals(0, flow.y)
            assertTrue(flow.height > 600)
            assertTrue(page.y < 60)
        }
    }

    @Test
    fun navigationRevealsSourceButPreservesSplitView() {
        SwingUtilities.invokeAndWait {
            val source = JPanel()
            val flow = JPanel()
            val views = PipelineEditorViews(source, flow)
            views.tabs.selectedIndex = 1
            views.showSourceForNavigation()
            assertEquals(0, views.tabs.selectedIndex)

            views.tabs.selectedIndex = 2
            views.showSourceForNavigation()
            assertEquals(2, views.tabs.selectedIndex)
            assertTrue(SwingUtilities.isDescendingFrom(source, views.tabs.selectedComponent))
            assertTrue(SwingUtilities.isDescendingFrom(flow, views.tabs.selectedComponent))
        }
    }
}
