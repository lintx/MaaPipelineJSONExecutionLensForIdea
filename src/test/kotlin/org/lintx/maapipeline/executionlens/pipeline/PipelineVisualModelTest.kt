package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.util.TextRange
import java.awt.Color
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineVisualModelTest {
    private fun node(raw: String, name: String = "Test") = PipelineNodeLocation(name, TextRange(0, 1), TextRange(0, 1), rawJson = raw)

    @Test
    fun defaultPreviewRequiresARegionUnlessAnExplicitPreviewImageExists() {
        val noRegion = node("""{"action":"Click"}""")
        val noRegionOperations = listOf(PipelineVisualModel.operation(noRegion), PipelineVisualModel.operation(noRegion, true))
        assertFalse(PipelinePreviewModel.hasPreviewRegion(noRegionOperations))
        assertTrue(PipelinePreviewModel.hasPreviewRegion(listOf(PipelineVisualModel.operation(node("""{"recognition":"OCR"}""")))))

        val annotated = node("""{
            // @roi [10,20,30,40]
            "recognition":"OCR"
        }""")
        assertTrue(PipelinePreviewModel.hasPreviewRegion(listOf(PipelineVisualModel.operation(annotated))))
        assertTrue(PipelineVisualModel.preview("""{
            // @preview images/sample.png
            "recognition":"OCR"
        }""") != null)
    }

    @Test
    fun controllerAnnotationsPreferTheNamedControllerThenItsTypeThenDefaults() {
        val source = node("""{
            // @preview adb mobile.png
            // @preview CloudADB cloud.png
            // @preview desktop.png
            // @roi adb [10,20,30,40]
            // @roi CloudADB [20,30,40,50]
            // @roi [1,2,3,4]
            // @roi target [5,6,7,8]
            // @roi adb target [50,60,70,80]
            "recognition":"OCR","action":"Click"
        }""")
        fun selected(id: String, type: String) = source.copy(environmentId = id, controllerType = type)
        val mobile = selected("Mobile", "Adb")
        assertEquals("mobile.png", PipelinePreviewHints.preview(mobile)?.path)
        assertEquals(listOf(10,20,30,40), PipelineVisualModel.region(PipelineVisualModel.operation(mobile)).coordinates)
        assertEquals(listOf(50,60,70,80), PipelineVisualModel.region(PipelineVisualModel.operation(mobile, true)).coordinates)
        val cloud = selected("CloudADB", "Adb")
        assertEquals("cloud.png", PipelinePreviewHints.preview(cloud)?.path)
        assertEquals(listOf(20,30,40,50), PipelineVisualModel.region(PipelineVisualModel.operation(cloud)).coordinates)
        val desktop = selected("Desktop", "Win32")
        assertEquals("desktop.png", PipelinePreviewHints.preview(desktop)?.path)
        assertEquals(listOf(1,2,3,4), PipelineVisualModel.region(PipelineVisualModel.operation(desktop)).coordinates)
        assertEquals(listOf(5,6,7,8), PipelineVisualModel.region(PipelineVisualModel.operation(desktop, true)).coordinates)
        val nested = source.copy(rawJson = """{"recognition":{"type":"OCR","param":{
            "roi":[/* @roi [1,2,3,4] */ /* @roi adb [10,20,30,40] */ 0,0,0,0]}}}""",
            environmentId = "ADB", controllerType = "Adb")
        assertEquals(listOf(10,20,30,40), PipelineVisualModel.region(PipelineVisualModel.operation(nested)).coordinates)
    }

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
    fun negativeSizesUseTheCurrentCornerAndActionOffsetsFollowFrameworkOrder() {
        fun region(raw: String, action: Boolean = true) = PipelineVisualModel.region(PipelineVisualModel.operation(node(raw), action))
        val pictured = region("""{"action":"Click","target":[683,411,28,34],"target_offset":[100,100,-56,0]}""")
        assertEquals(Rectangle(755,511,28,34), pictured.rectangle(1280,720))
        assertTrue(PipelinePreviewModel.annotations(listOf(PipelineVisualModel.operation(node("""{"action":"Click","target":[683,411,28,34],"target_offset":[100,100,-56,0]}"""), true)))
            .single().imageLines(pictured.rectangle(1280,720)).contains("target=[755, 511, 28, 34]"))
        assertEquals(Rectangle(70,40,10,20), PipelineRegionInfo.rectangle(listOf(-20,-40,-10,-20),100,100))
        assertEquals(Rectangle(65,66,30,12), region("""{"action":"Click","target":[80,70,-20,-10],"target_offset":[5,6,10,2]}""").rectangle(100,100))
        assertEquals(Rectangle(75,68,10,8), region("""{"recognition":"OCR","roi":[80,70,-20,-10],"roi_offset":[5,6,10,2]}""", false).rectangle(100,100))
        assertEquals(Rectangle(0,0,10,10), region("""{"action":"Click","target":[20,20,10,10],"target_offset":[-30,-40,0,0]}""").rectangle(100,100))
        assertEquals(Rectangle(90,80,10,10), region("""{"recognition":"OCR","roi":[20,20,10,10],"roi_offset":[-30,-40,0,0]}""", false).rectangle(100,100))
        assertEquals(Rectangle(10,20,90,80), region("""{"action":"Click","target":[0,0,0,0],"target_offset":[10,20,-10,-20]}""").rectangle(100,100))
        assertNull(region("""{"action":"Click","target":[10,10,20,20],"target_offset":[0,0,-20,0]}""").rectangle(100,100))
        assertEquals(Rectangle(11,22,4,5), region("""{"action":"Click","target":[10,20],"target_offset":[1,2,3,4]}""").rectangle(100,100))
    }

    @Test
    fun comparesOriginalAndFinalOffsetRegionsWithoutAddingSemanticTargets() {
        val operation = PipelineVisualModel.operation(node("""{
            // @roi [10,20,30,40]
            "recognition":"OCR","roi":[1,2,3,4],"roi_offset":[20,10,5,6]
        }"""))
        val annotation = PipelinePreviewModel.annotations(listOf(operation)).single()
        assertEquals(listOf(10, 20, 30, 40), annotation.region.originalCoordinates)
        assertEquals(listOf(30, 30, 35, 46), annotation.region.coordinates)
        val regions = PipelineVisualAssets.overlayRegions(100, 100, listOf(operation))
        assertEquals(listOf(Rectangle(10, 20, 30, 40), Rectangle(30, 30, 35, 46)), regions.map { it.rectangle })
        assertEquals(listOf(true, false), regions.map { it.dashed })
        assertEquals(listOf(1, 1), regions.flatMap { it.annotations }.map { it.number })

        val zero = operation.copy(parameters = operation.parameters +
            ("roi_offset" to com.google.gson.JsonParser.parseString("[0,0,0,0]")))
        val merged = PipelineVisualAssets.overlayRegions(100, 100, listOf(zero)).single()
        assertEquals(1, merged.annotations.size)
        assertEquals(2, merged.marks.size)
        assertTrue(!merged.dashed)
        val clipped = operation.copy(parameters = operation.parameters +
            ("roi_offset" to com.google.gson.JsonParser.parseString("[-20,-30,0,0]")))
        assertEquals(2, PipelineVisualAssets.overlayRegions(100, 100, listOf(clipped)).size)

        val simulated = operation.copy(simulatedRegions = mapOf("roi" to listOf(30, 30, 35, 46)))
        assertEquals(annotation.region.coordinates, PipelineVisualModel.region(simulated).coordinates)
        assertEquals(annotation.region.originalCoordinates, PipelineVisualModel.region(simulated).originalCoordinates)
        val defaultRoi = operation.copy(parameters = mapOf("roi_offset" to com.google.gson.JsonParser.parseString("[10,20,-10,-20]")), roiHints = emptyMap())
        assertEquals(listOf(0, 0, 0, 0), PipelineVisualModel.region(defaultRoi).originalCoordinates)
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
