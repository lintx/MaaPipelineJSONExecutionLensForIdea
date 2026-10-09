package com.maapipeline.executionlens.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class PipelineNodeScannerTest {
    @Test
    fun scansJsoncTopLevelNodesWithoutFlatteningNestedKeys() {
        val names = PipelineNodeScanner.scanNodeNames(
            """
            {
              // 保留 JSONC 注释场景
              "OpenGame": {
                "action": { "type": "StartApp" },
                "next": ["CloseGame"]
              },
              "CloseGame": {
                "recognition": { "type": "TemplateMatch" }
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("OpenGame", "CloseGame"), names)
    }
}
