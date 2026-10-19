package com.maapipeline.executionlens.pipeline

import java.awt.Dimension
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineFlowHeadingTest {
    @Test
    fun longConditionsYieldToTheNodeNameAndTheOpeningBrace() {
        fun label(text: String, width: Int) = JLabel(text).apply { preferredSize = Dimension(width, 24) }
        val condition = label("if And(OCR, TemplateMatch)", 900)
        val target = label("→ __AccountSwitchLogoutConfirm", 240)
        val brace = label("{", 16)
        val heading = PipelineFlowHeading(condition, target, brace)
        heading.setSize(500, 24)
        heading.doLayout()
        assertEquals(240, target.width)
        assertTrue(condition.width < condition.preferredSize.width)
        assertEquals(16, brace.width)
        assertEquals(heading.width, brace.x + brace.width)
        heading.setSize(200, 24)
        heading.doLayout()
        assertTrue(target.width > condition.width)
        assertEquals(16, brace.width)
        assertEquals(heading.width, brace.x + brace.width)
        assertTrue(condition.width >= 0)
    }

    @Test
    fun shortHeadingsKeepTheirPartsTogetherAndAllowTinyWidths() {
        val condition = JLabel("if OCR").apply { preferredSize = Dimension(60, 24) }
        val target = JLabel("→ Target").apply { preferredSize = Dimension(70, 24) }
        val brace = JLabel("{").apply { preferredSize = Dimension(12, 24) }
        val heading = PipelineFlowHeading(condition, target, brace)
        heading.setSize(1000, 24)
        heading.doLayout()
        assertEquals(60, target.x)
        assertEquals(130, brace.x)
        heading.setSize(5, 24)
        heading.doLayout()
        assertEquals(5, brace.width)
        assertEquals(0, target.width)
        assertEquals(0, condition.width)
    }
}
