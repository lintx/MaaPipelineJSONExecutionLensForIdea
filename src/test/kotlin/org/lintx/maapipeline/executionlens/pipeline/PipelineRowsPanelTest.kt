package org.lintx.maapipeline.executionlens.pipeline

import java.awt.Dimension
import java.awt.Point
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertNotSame

class PipelineRowsPanelTest {
    @Test
    fun playbackFollowUsesTwoFifthsAndClampsToExistingContent() {
        lateinit var panel: PipelineRowsPanel
        lateinit var viewport: JViewport
        SwingUtilities.invokeAndWait {
            panel = PipelineRowsPanel()
            viewport = JViewport().apply { extentSize = Dimension(600, 200); setView(panel) }
            panel.setRows((0 until 100).map { index ->
                PipelineRow("row-$index") { JPanel().apply { preferredSize = Dimension(600, 20) } }
            })
            panel.followPlaybackRow("row-25")
        }
        SwingUtilities.invokeAndWait {
            assertEquals(420, viewport.viewPosition.y)
            panel.revealRowIfNeeded("row-25")
        }
        SwingUtilities.invokeAndWait {
            assertEquals(420, viewport.viewPosition.y, "目标已经可见时不移动视口")
            panel.followPlaybackRow("row-1")
        }
        SwingUtilities.invokeAndWait {
            assertEquals(0, viewport.viewPosition.y)
            panel.followPlaybackRow("row-99")
        }
        SwingUtilities.invokeAndWait {
            assertEquals(1800, viewport.viewPosition.y)
            panel.followPlaybackRow("row-25")
            panel.followPlaybackRow(null)
        }
        SwingUtilities.invokeAndWait {
            assertEquals(1800, viewport.viewPosition.y)
            assertEquals(2000, panel.preferredSize.height)
        }
    }

    @Test
    fun semanticUpdatesOnlyReplaceChangedRowsAndKeepScrollAnchor() {
        lateinit var panel: PipelineRowsPanel
        lateinit var viewport: JViewport
        val revisions = (0 until 100).associate { "row-$it" to "original" }.toMutableMap()
        fun rows(keys: List<String>) = keys.map { key ->
            val revision = revisions.getValue(key)
            PipelineRow(key, revision) {
                JPanel().apply { name = key; preferredSize = Dimension(600, 20); toolTipText = revision }
            }
        }
        val keys = revisions.keys.toList()
        lateinit var unchanged: java.awt.Component
        lateinit var changed: java.awt.Component
        SwingUtilities.invokeAndWait {
            panel = PipelineRowsPanel()
            viewport = JViewport().apply { extentSize = Dimension(600, 100); setView(panel) }
            panel.setRows(rows(keys))
            viewport.doLayout()
            panel.doLayout()
            viewport.viewPosition = Point(0, 405)
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
        }
    }
}
