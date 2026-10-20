package org.lintx.maapipeline.executionlens.pipeline

import java.awt.Dimension
import java.awt.Point
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertNotSame

class PipelineVirtualRowsPanelTest {
    @Test
    fun semanticUpdatesOnlyReplaceChangedVisibleRowsAndKeepScrollAnchor() {
        lateinit var panel: PipelineVirtualRowsPanel
        lateinit var viewport: JViewport
        val revisions = (0 until 100).associate { "row-$it" to "original" }.toMutableMap()
        fun rows(keys: List<String>) = keys.map { key ->
            val revision = revisions.getValue(key)
            PipelineVirtualRow(key, 20, revision) {
                JPanel().apply { name = key; preferredSize = Dimension(600, 20); toolTipText = revision }
            }
        }
        val keys = revisions.keys.toList()
        lateinit var unchanged: java.awt.Component
        lateinit var changed: java.awt.Component
        SwingUtilities.invokeAndWait {
            panel = PipelineVirtualRowsPanel()
            viewport = JViewport().apply { extentSize = Dimension(600, 100); setView(panel) }
            panel.setRows(rows(keys))
            viewport.viewPosition = Point(0, 405)
            panel.addNotify()
        }
        SwingUtilities.invokeAndWait {
            unchanged = panel.components.single { it.name == "row-20" }
            changed = panel.components.single { it.name == "row-21" }
            panel.setRows(rows(keys))
            assertSame(unchanged, panel.components.single { it.name == "row-20" })
            revisions["row-21"] = "updated navigation range"
            panel.setRows(rows(keys))
        }
        SwingUtilities.invokeAndWait {
            assertSame(unchanged, panel.components.single { it.name == "row-20" })
            assertNotSame(changed, panel.components.single { it.name == "row-21" })
            assertEquals("updated navigation range", (panel.components.single { it.name == "row-21" } as JPanel).toolTipText)
            assertEquals(405, viewport.viewPosition.y)
            revisions["inserted"] = "new"
            panel.setRows(rows(listOf("inserted") + keys))
        }
        SwingUtilities.invokeAndWait {
            assertSame(unchanged, panel.components.single { it.name == "row-20" })
            assertEquals("row-20" to 5, panel.scrollAnchor())
            assertEquals(425, viewport.viewPosition.y)
            panel.removeNotify()
        }
    }

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
