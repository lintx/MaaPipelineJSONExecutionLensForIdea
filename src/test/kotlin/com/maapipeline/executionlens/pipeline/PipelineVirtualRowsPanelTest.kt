package com.maapipeline.executionlens.pipeline

import java.awt.Dimension
import java.awt.Point
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineVirtualRowsPanelTest {
    @Test
    fun variableHeightRowsRespectExactViewportBoundaries() {
        val geometry = PipelineRowGeometry(listOf(10, 30, 20))
        assertEquals(60, geometry.totalHeight)
        assertEquals(0, geometry.rowAt(-1))
        assertEquals(1, geometry.rowAt(10))
        assertEquals(2, geometry.rowAt(40))
        assertEquals(2, geometry.rowAt(100))
        assertEquals(1..1, geometry.visibleRows(10, 30))
        assertEquals(0..2, geometry.visibleRows(10, 30, 10))
        assertTrue(PipelineRowGeometry(emptyList()).visibleRows(0, 400).isEmpty())
    }

    @Test
    fun largeListCreatesOnlyViewportRowsAndRecyclesOffscreenComponents() {
        lateinit var panel: PipelineVirtualRowsPanel
        lateinit var viewport: JViewport
        val created = mutableListOf<Int>()
        SwingUtilities.invokeAndWait {
            panel = PipelineVirtualRowsPanel()
            viewport = JViewport().apply {
                extentSize = Dimension(600, 300)
                setView(panel)
            }
            panel.setRows((0 until 10_000).map { index ->
                PipelineVirtualRow("row-$index", 20) {
                    created += index
                    JPanel().apply { preferredSize = Dimension(600, 20) }
                }
            })
            panel.addNotify()
        }
        SwingUtilities.invokeAndWait {
            assertTrue(panel.componentCount in 1..50)
            assertTrue(created.size < 50)
            viewport.viewPosition = Point(0, 140_000)
        }
        SwingUtilities.invokeAndWait {
            assertTrue(panel.componentCount in 1..50)
            assertTrue(created.any { it in 6_985..7_030 })
            assertTrue(created.size < 100)
            panel.removeNotify()
        }
    }

    @Test
    fun measuredHeightKeepsVisibleRowAnchored() {
        lateinit var panel: PipelineVirtualRowsPanel
        lateinit var viewport: JViewport
        SwingUtilities.invokeAndWait {
            panel = PipelineVirtualRowsPanel()
            viewport = JViewport().apply {
                extentSize = Dimension(600, 100)
                setView(panel)
            }
            panel.setRows((0 until 100).map { index ->
                PipelineVirtualRow("row-$index", 20) {
                    JPanel().apply { preferredSize = Dimension(600, if (index == 19) 40 else 20) }
                }
            })
            viewport.viewPosition = Point(0, 405)
            panel.addNotify()
        }
        SwingUtilities.invokeAndWait {
            assertEquals(425, viewport.viewPosition.y)
            panel.removeNotify()
        }
    }
}
