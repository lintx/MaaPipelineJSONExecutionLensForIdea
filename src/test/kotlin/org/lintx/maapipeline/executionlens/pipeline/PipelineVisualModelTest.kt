package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import java.awt.Color
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineVisualModelTest {
    private fun node(raw: String, name: String = "Test") = PipelineNodeLocation(name, TextRange(0, 1), TextRange(0, 1), rawJson = raw)

    @Test
    fun readsV1V2AndReferencedCombinationsWithoutLosingParameters() {
        val target = node("""{"recognition":"TemplateMatch","template":"A.png","roi":[1,2,3,4]}""", "Icon")
        val composite = PipelineVisualModel.operation(node("""{"recognition":{"type":"And","param":{"box_index":1,"all_of":["Icon",{"type":"OCR","param":{"expected":["确认"],"roi":"icon"}}]}}}""")) {
            if (it == "Icon") target else null
        }
        assertEquals("And", composite.type)
        assertEquals(listOf("TemplateMatch", "OCR"), composite.children.map { it.type })
        assertEquals("[1, 2, 3, 4]", PipelineVisualModel.region(composite.children[0]).summary)
        assertEquals("[\"确认\"]", composite.children[1].text("expected"))
        assertEquals("1", composite.text("box_index"))
    }

    @Test
    fun distinguishesDynamicBoxFromItsSearchAreaAndKeepsFourOffsets() {
        val source = node("""{"recognition":"TemplateMatch","roi":[10,20,30,40],"action":"Click","target_offset":[1,2,3,4]}""")
        val dynamic = PipelineVisualModel.region(PipelineVisualModel.operation(source, true))
        assertNull(dynamic.coordinates)
        assertEquals(listOf(10, 20, 30, 40), dynamic.searchCoordinates)
        assertTrue(dynamic.explanation.contains("[1, 2, 3, 4]"))
        val fixed = PipelineVisualModel.region(PipelineVisualModel.operation(node("""{"action":"Click","target":[1,2,3,4],"target_offset":[4,3,2,1]}"""), true))
        assertEquals(listOf(5, 5, 5, 5), fixed.coordinates)
        assertEquals(Rectangle(80, 60, 20, 40), PipelineRegionInfo.rectangle(listOf(-20, -40, 0, 0), 100, 100))
        assertEquals(Rectangle(20, 20, 10, 20), PipelineRegionInfo.rectangle(listOf(30, 40, -10, -20), 100, 100))
    }

    @Test
    fun convertsHsvAndGrayAndDoesNotInventHueWrapping() {
        val hsv = PipelineColorSpec(40, listOf(PipelineColorRange(listOf(60, 255, 255), listOf(60, 255, 255))))
        assertEquals(listOf(Color.GREEN), hsv.samples(hsv.ranges.single()))
        val invalid = PipelineColorRange(listOf(170, 0, 0), listOf(10, 255, 255))
        assertTrue(hsv.samples(invalid).isEmpty())
        val gray = PipelineColorSpec(6, listOf(PipelineColorRange(listOf(10), listOf(20))))
        assertTrue(gray.samples(gray.ranges.single()).all { it.red == it.green && it.green == it.blue && it.red in 10..20 })
        val other = PipelineColorSpec(44, hsv.ranges)
        assertTrue(other.samples(other.ranges.single()).isEmpty())
        assertTrue(PipelineColorSpec(4, emptyList(), true).explanation.contains("不是逐像素"))
    }

    @Test
    fun rgbRangePreviewContainsOnlyMatchingSamplesAndNoGrayPadding() {
        val range = PipelineColorRange(listOf(90, 35, 35), listOf(191, 83, 85))
        val preview = assertNotNull(PipelineVisualAssets.colorImage(PipelineColorSpec(4, listOf(range))))
        assertEquals(36, preview.height)
        for (vertical in 0 until preview.height) for (horizontal in 0 until preview.width) {
            val sample = Color(preview.getRGB(horizontal, vertical))
            assertTrue(sample.red in 90..191 && sample.green in 35..83 && sample.blue in 35..85)
        }
        val nonRectangularGrid = PipelineColorRange(listOf(90, 35, 35), listOf(91, 37, 39))
        val other = assertNotNull(PipelineVisualAssets.colorImage(PipelineColorSpec(4, listOf(nonRectangularGrid))))
        assertTrue(other.getRGB(0, 0, other.width, other.height, null, 0, other.width).all {
            Color(it).let { sample -> sample.red in 90..91 && sample.green in 35..37 && sample.blue in 35..39 }
        })
    }

    @Test
    fun onlyAcceptsExplicitPreviewCommentAtStartOfNodeObject() {
        assertEquals("images/example.png", PipelineVisualModel.preview("{\n // @preview images/example.png\n \"action\":\"Click\"\n}"))
        assertNull(PipelineVisualModel.preview("""{"desc":"// @preview secret.png"}"""))
        assertNull(PipelineVisualModel.preview("{\n \"action\":\"Click\",\n // @preview late.png\n \"next\":[]\n}"))
    }

    @Test
    fun mergesCoincidentRegionsWithoutConfusingDynamicSearchWithActualTarget() {
        val source = node("""{"recognition":"And","box_index":1,"all_of":[
            {"recognition":"OCR","roi":[10,10,60,70]},
            {"recognition":"TemplateMatch","roi":[10,40,60,40]}
        ],"action":"Click"}""")
        val operations = PipelineVisualAssets.leaves(PipelineVisualModel.operation(source)) + PipelineVisualModel.operation(source, true)
        val annotations = PipelinePreviewModel.annotations(operations)
        assertEquals(listOf(1, 2, 3), annotations.map { it.number })
        assertEquals(2, annotations[2].sourceNumber)
        assertTrue(annotations[2].title.contains("TemplateMatch 的识别结果"))
        assertTrue(annotations[2].region.resultSteps.any { it.contains("box_index=1") })
        assertTrue(annotations[2].details().any { it.contains("整个范围不等于动作 target") })
        val regions = PipelineVisualAssets.overlayRegions(100, 100, operations)
        assertEquals(2, regions.size)
        assertEquals(Rectangle(10, 10, 60, 70), regions[0].rectangle)
        assertEquals(Rectangle(10, 40, 60, 40), regions[1].rectangle)
        assertEquals(2, regions[1].descriptions.size)
        assertTrue(regions[1].descriptions.any { it.contains("Click") && it.contains("运行时确定") })
        assertTrue(!regions[1].dynamicOnly)
        val original = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val isolated = PipelineVisualAssets.overlay(original, operations, selectedAnnotation = 1)
        assertEquals(original.getRGB(40, 40), isolated.getRGB(40, 40))
        assertTrue(isolated.getRGB(40, 79) != original.getRGB(40, 79))
        assertEquals(original.height, isolated.height)
        val target = PipelineVisualAssets.overlay(original, operations, selectedAnnotation = 3)
        assertEquals(original.getRGB(10, 10), target.getRGB(10, 10))
        assertTrue((45..70).any { target.getRGB(10, it) != original.getRGB(10, it) })
        val combined = PipelineVisualAssets.overlay(original, operations)
        val sharedColors = (50..75).map { Color(combined.getRGB(10, it)) }
        assertTrue(sharedColors.any { it.red > 200 && it.green < 150 })
        assertTrue(sharedColors.any { it.red > 200 && it.green > 200 })
    }

    @Test
    fun keepsRuntimeTargetWithoutInventingOneSourceForOrOrDirectHit() {
        val source = node("""{"recognition":"Or","any_of":[
            {"recognition":"OCR","roi":[10,10,20,30]},
            {"recognition":"TemplateMatch","roi":[30,30,20,30]}
        ],"action":"Click"}""")
        val operations = PipelineVisualAssets.leaves(PipelineVisualModel.operation(source)) + PipelineVisualModel.operation(source, true)
        val target = PipelinePreviewModel.annotations(operations).last()
        assertEquals(3, target.number)
        assertNull(target.sourceNumber)
        assertNull(target.region.coordinates)
        assertNull(target.region.searchCoordinates)
        assertTrue(target.details().any { it.contains("Or 首个成功子条件") })
        val direct = PipelineVisualModel.region(PipelineVisualModel.operation(node("""{"action":"Click"}"""), true))
        assertNull(direct.searchCoordinates)
        assertTrue(direct.resultSteps.any { it.contains("无图像匹配框") })
    }

    @Test
    fun preservesFixedTargetAndNamedResultSourceWithOffsets() {
        val recognized = node("""{"recognition":"OCR","expected":"确认","roi":[10,20,30,40]}""", "Result")
        val action = PipelineVisualModel.operation(node("""{"action":"Click","target":"Result","target_offset":[1,2,3,4]}"""), true)
        val annotations = PipelinePreviewModel.annotations(listOf(PipelineVisualModel.operation(recognized), action)) { if (it == "Result") recognized else null }
        assertEquals(1, annotations[1].sourceNumber)
        assertTrue(annotations[1].details().any { it.contains("target_offset") && it.contains("[1, 2, 3, 4]") })
        assertTrue(annotations[1].details().any { it.contains("来源 expected：确认") })
        val fixed = PipelinePreviewModel.annotations(listOf(action.copy(parameters = mapOf("target" to com.google.gson.JsonParser.parseString("[1,2,3,4]")))))
        assertNull(fixed.single().sourceNumber)
        assertEquals(listOf(1, 2, 3, 4), fixed.single().region.coordinates)
    }
}
