package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelinePropertyCatalogTest {
    private fun node(raw: String) = PipelineNodeLocation("Node", TextRange.EMPTY_RANGE, TextRange.EMPTY_RANGE, rawJson = raw)

    @Test
    fun defaultsAndExplicitValuesStayDistinctAndExtensionsArePreserved() {
        val resource = PipelineVisualModel.parse("""{"Default":{"rate_limit":0,"attach":{"base":1,"shared":1}}}""")!!
        val source = node("""{"rate_limit":0,"attach":{"shared":2},"extension":{"active":true}}""")
        val properties = PipelinePropertyCatalog.properties(source, resource).associateBy { it.name }
        assertEquals("0", properties.getValue("rate_limit").defaultValue.toString())
        assertFalse(properties.getValue("rate_limit").inherited)
        assertTrue(properties.getValue("timeout").inherited)
        assertEquals("20000", properties.getValue("timeout").value.toString())
        assertEquals("""{"base":1,"shared":2}""", properties.getValue("attach").value.toString())
        assertTrue(properties.containsKey("extension"))
        assertNull(PipelinePropertyCatalog.policySummary(source, resource))
        assertEquals("1000", PipelinePropertyCatalog.policyValue(node("{}"), PipelineVisualModel.parse("{}")!!, "rate_limit"))
        val operation = PipelineVisualModel.operation(node("""{"recognition":{"type":"TemplateMatch","param":{"template":"a.png"}}}"""))
        val defaults = PipelinePropertyCatalog.operationProperties(operation,
            PipelineVisualModel.parse("""{"TemplateMatch":{"threshold":0.9}}""")!!).associateBy { it.name }
        assertEquals("0.9", defaults.getValue("threshold").value.toString())
        assertTrue(defaults.getValue("threshold").inherited)
        assertTrue(defaults.getValue("threshold").resourceDefault)
        assertFalse(defaults.getValue("template").inherited)
    }
}
