package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PipelineJsonNavigationTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): com.intellij.testFramework.fixtures.TempDirTestFixture =
        com.intellij.testFramework.fixtures.IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    override fun setUp() {
        super.setUp()
        // 真实磁盘夹具的图片及跨文件引用都需要项目内容根，不能依赖其它用例遗留的注册。
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, myFixture.tempDirFixture.findOrCreateDir(""))
    }

    fun testCompositeHoverGroupsReferencesAndSelectsOnlyTheCurrentInlineCondition() {
        myFixture.addFileToProject("assets/resource/pipeline/Referenced.json", """
            {"InWorld":{"recognition":"And","all_of":[
                {"recognition":"TemplateMatch","template":"world-menu.png"},
                {"recognition":{"type":"OCR","param":{"expected":"world-text"}}}
            ]}}
        """.trimIndent())
        val source = myFixture.addFileToProject("assets/resource/pipeline/Conditions.json", """
            {"Root":{"recognition":{"type":"And","param":{"all_of":[
                "InWorld",
                {"recognition":{"type":"Or","param":{"any_of":[
                    {"recognition":{"type":"TemplateMatch","param":{"template":"inline-icon.png","green_mask":true}}},
                    {"recognition":"Custom","custom_recognition":"IconRecognition"}
                ]}}},
                {"recognition":"OCR","expected":"other-branch"},
                {"recognition":"ColorMatch","method":40,"lower":[60,255,255],"upper":[60,255,255]}
            ]}}}}
        """.trimIndent())
        val provider = PipelineJsonDocumentationProvider()
        fun hover(text: String): String {
            val element = source.findElementAt(source.text.indexOf(text))!!
            assertNotNull("应支持悬停 $text", provider.previewElement(element))
            val modern = PipelineDocumentationTargetProvider().documentationTargets(source, element.textOffset).single()
            assertNotNull("原生悬停入口应返回文档", modern.computeDocumentation())
            return provider.generateDoc(element, element)!!
        }
        val all = hover("And")
        assertTrue(all.contains("InWorld: And"))
        assertTrue(all.contains("world-menu.png")); assertTrue(all.contains("IconRecognition")); assertTrue(all.contains("other-branch"))
        assertTrue(all.contains("margin-left"))
        assertTrue(hover("all_of").contains("other-branch"))
        val reference = hover("InWorld")
        assertTrue(reference.contains("InWorld: And")); assertTrue(reference.contains("world-menu.png"))
        assertFalse(reference.contains("inline-icon.png")); assertFalse(reference.contains("other-branch"))
        val nested = hover("any_of")
        assertTrue(nested.contains("inline-icon.png")); assertTrue(nested.contains("IconRecognition"))
        assertFalse(nested.contains("world-menu.png")); assertFalse(nested.contains("other-branch"))
        val custom = hover("custom_recognition")
        assertTrue(custom.contains("IconRecognition"))
        assertFalse(custom.contains("inline-icon.png")); assertFalse(custom.contains("world-menu.png"))
        val template = hover("inline-icon.png")
        assertTrue(template.contains("inline-icon.png")); assertFalse(template.contains("IconRecognition"))
        assertFalse(hover("green_mask").contains("IconRecognition"))
        val colorLink = Regex("maa-image:(colors:[^\"]+)").find(all)!!.groupValues[1]
        val full = PipelineHoverImages.load(source.findElementAt(source.text.indexOf("And"))!!, colorLink)!!
        assertEquals(Color.GREEN.rgb, full.image.getRGB(0, 0))
    }

    fun testHoverToleratesIncompleteJsonDuringEditing() {
        val source = myFixture.addFileToProject("assets/resource/pipeline/Incomplete.json", """
            {"Broken":{"recognition":{"type":"And","param":{"all_of":[
                {"recognition":"Custom","custom_recognition":"IconRecognition","custom_recognition_param":{"t"}}
            ]}}}}
        """.trimIndent())
        val element = source.findElementAt(source.text.indexOf("custom_recognition"))!!
        val context = PipelineJsonContext.at(element)!!
        assertNull(PipelinePreviewContext(context).resolve("Broken"))
        assertNull(PipelineJsonDocumentationProvider().generateDoc(element, element))
        assertNull(PipelineHoverImages.load(element, "preview"))
        assertNull(PipelineDocumentationTargetProvider().documentationTargets(source, element.textOffset).single().computeDocumentation())
    }

    fun testIndexedActionSummaryDoesNotDropTheFourthCoordinate() {
        val psi = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"Click":{"action":"Click","target":[10,20,30,40]},
             "Check":{"recognition":"TemplateMatch","template":"icon.png","roi":[50,60,70,80]}}
        """.trimIndent())
        val model = PipelineDocumentIndexer.index(project, psi.virtualFile)
        assertTrue(model.nodes.single { it.name == "Click" }.action!!.summary.contains("[10, 20, 30, 40]"))
        assertTrue(model.nodes.single { it.name == "Check" }.recognition!!.summary.contains("[50, 60, 70, 80]"))
    }

    private fun reference(file: com.intellij.psi.PsiFile, value: String): PipelineJsonReference =
        PsiTreeUtil.findChildrenOfType(file, JsonStringLiteral::class.java).single { it.value == value }
            .references.filterIsInstance<PipelineJsonReference>().single()

    fun testNativeJsonReferencesNavigateAcrossFilesAndRespectAnchorMarkers() {
        val target = myFixture.addFileToProject("assets/resource/pipeline/Other.json", """
            {"Destination":{"recognition":"OCR"},"Slot":{"recognition":"DirectHit"}}
        """.trimIndent())
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"Start":{"anchor":{"Slot":"Destination"},"next":["[JumpBack][Anchor]Slot",{"name":"Destination"}]}}
        """.trimIndent())
        val anchor = reference(source, "[JumpBack][Anchor]Slot").multiResolve(false)
        assertEquals(1, anchor.size)
        assertEquals(target.virtualFile, anchor.single().element!!.containingFile.virtualFile)
        assertEquals(target.text.indexOf("\"Destination\""), anchor.single().element!!.textOffset)
        val direct = PsiTreeUtil.findChildrenOfType(source, JsonStringLiteral::class.java)
            .single { it.value == "Destination" && (it.parent as? com.intellij.json.psi.JsonProperty)?.name == "name" }
        assertEquals(target.virtualFile, direct.references.filterIsInstance<PipelineJsonReference>().single().resolve()!!.containingFile.virtualFile)
        val definitionTarget = PsiTreeUtil.findChildrenOfType(source, JsonStringLiteral::class.java)
            .single { it.value == "Destination" && (it.parent as? com.intellij.json.psi.JsonProperty)?.name == "Slot" }
        assertEquals(target.virtualFile, definitionTarget.references.filterIsInstance<PipelineJsonReference>().single().resolve()!!.containingFile.virtualFile)
        val ordinary = myFixture.addFileToProject("ordinary.json", """{"template":"button.png","next":"ordinary"}""")
        assertTrue(PsiTreeUtil.findChildrenOfType(ordinary, JsonStringLiteral::class.java).all { literal ->
            literal.references.none { it is PipelineJsonReference }
        })
    }

    fun testCustomGoRegistrationConstantsResolveToRunInAnotherFile() {
        myFixture.addFileToProject("agent/demo/register.go", """
            package demo
            func Register() { maa.AgentServerRegisterCustomAction(actionName, &ActualAction{}) }
        """.trimIndent())
        val implementation = myFixture.addFileToProject("agent/demo/action.go", """
            package demo
            const actionName = "FriendlyAction"
            type ActualAction struct {}
            func (a *ActualAction) Run(ctx *maa.Context, arg *maa.CustomActionArg) bool { return true }
        """.trimIndent())
        val pipeline = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"Start":{"action":"Custom","custom_action":"FriendlyAction"}}
        """.trimIndent())
        val resolved = reference(pipeline, "FriendlyAction").resolve()!!
        assertEquals(implementation.virtualFile, resolved.containingFile.virtualFile)
        assertEquals(implementation.text.indexOf("Run("), resolved.textOffset)
    }

    fun testCppCallbackAndPythonDecoratorNavigateToImplementation() {
        myFixture.addFileToProject("agent/native/main.cpp", """void Register() { MaaAgentServerRegisterCustomRecognition("NativeReco", demo::RecognizeRun, nullptr); }""")
        val cpp = myFixture.addFileToProject("agent/native/reco.cpp", """
            namespace demo {
            MaaBool MAA_CALL RecognizeRun(MaaContext* ctx, const char* args) { return true; }
            }
        """.trimIndent())
        val python = myFixture.addFileToProject("agent/python/reco.py", """
            @AgentServer.custom_recognition("PythonReco")
            class Reco(CustomRecognition):
                def run(self, context, args):
                    return True
        """.trimIndent())
        val pipeline = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"Native":{"recognition":"Custom","custom_recognition":"NativeReco"},
             "Python":{"recognition":"Custom","custom_recognition":"PythonReco"}}
        """.trimIndent())
        val native = reference(pipeline, "NativeReco").resolve()!!
        assertEquals(cpp.virtualFile, native.containingFile.virtualFile)
        assertEquals(cpp.text.indexOf("RecognizeRun("), native.textOffset)
        val interpreted = reference(pipeline, "PythonReco").resolve()!!
        assertEquals(python.virtualFile, interpreted.containingFile.virtualFile)
        assertEquals(python.text.indexOf("run("), interpreted.textOffset)
    }

    fun testTemplateAndColorHoverAndPreviewCommentProduceRealImages() {
        com.intellij.openapi.util.registry.Registry.get("doc.render.adaptive.image").setValue(true, testRootDisposable)
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics -> graphics.color = Color.BLUE; graphics.fillRect(0, 0, 120, 80); graphics.dispose() }
        val output = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val resource = myFixture.addFileToProject("assets/resource/image/button.png", "")
        WriteCommandAction.runWriteCommandAction(project, Runnable { resource.virtualFile.setBinaryContent(output) })
        val referenceImage = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
        referenceImage.createGraphics().let { graphics -> graphics.color = Color.BLUE; graphics.fillRect(0, 0, 1280, 720); graphics.dispose() }
        val random = java.util.Random(42)
        for (column in 0 until 200) for (row in 0 until 200) referenceImage.setRGB(column, row, random.nextInt())
        val referenceBytes = ByteArrayOutputStream().also { ImageIO.write(referenceImage, "png", it) }.toByteArray()
        val reference = myFixture.addFileToProject("assets/resource/image/reference.png", "")
        WriteCommandAction.runWriteCommandAction(project, Runnable { reference.virtualFile.setBinaryContent(referenceBytes) })
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"Start":{
              // @preview ../image/reference.png
              "recognition":"And","all_of":[
                {"recognition":"TemplateMatch","template":"button.png","roi":[10,10,50,40]},
                {"recognition":{"type":"ColorMatch","param":{"method":40,"lower":[60,255,255],"upper":[60,255,255]}}}
              ],"action":"Click"}}
        """.trimIndent())
        val template = reference(source, "button.png")
        assertEquals(resource.virtualFile, template.resolve()!!.containingFile.virtualFile)
        val previewNode = PipelineNodeLocation("Start", com.intellij.openapi.util.TextRange(0, 1),
            com.intellij.openapi.util.TextRange(0, 1), sourceFile = source.virtualFile,
            rawJson = (source as com.intellij.json.psi.JsonFile).topLevelValue.let {
                (it as com.intellij.json.psi.JsonObject).findProperty("Start")!!.value!!.text
            })
        val operations = PipelineVisualAssets.leaves(PipelineVisualModel.operation(previewNode)) +
            PipelineVisualModel.operation(previewNode, true)
        val templates = PipelineVisualAssets.previewTemplates(project, source.virtualFile, operations)
        assertEquals(Color.BLUE.rgb, templates[1]!!.single().image!!.getRGB(0, 0))
        assertEquals("button.png", templates[3]!!.single().name)
        assertNotNull("动态 target 单选时也应复用其来源模板", templates[3]!!.single().image)
        val registered = com.intellij.codeInsight.documentation.DocumentationManager.getProviderFromElement(template.element, template.element)
        val registeredDoc = registered.generateDoc(template.element, template.element)!!
        assertTrue("IDEA 的悬浮只保留短图片引用", registeredDoc.length < 10_000)
        assertFalse(registeredDoc.contains("data:image/png;base64,"))
        val modern = com.intellij.platform.backend.documentation.DocumentationTargetProvider.EP_NAME.extensionList
            .filterIsInstance<PipelineDocumentationTargetProvider>().single()
        val modernTarget = modern.documentationTargets(source, template.element.textOffset + 1).single() as PipelineDocumentationTarget
        val result = modernTarget.computeDocumentation()!!
        val content = result.javaClass.declaredFields.single { it.name == "content" }.apply { isAccessible = true }.get(result)
        val nativeHtml = content.javaClass.getMethod("getHtml").invoke(content) as String
        val nativeImages = Regex("src=\"([^\"]+)\"").findAll(nativeHtml).toList()
        assertTrue("新版原生悬浮必须包含图片引用", nativeImages.isNotEmpty())
        assertTrue(nativeHtml.contains("id=\"${modernTarget.popupMarker}\""))
        val resolver = content.javaClass.getMethod("getImageResolver").invoke(content) as com.intellij.lang.documentation.DocumentationImageResolver
        nativeImages.forEach { assertNotNull("IDEA 图片解析器应解析 $it", resolver.resolveImage(it.groupValues[1])) }
        val provider = PipelineJsonDocumentationProvider()
        fun checkImages(element: PsiElement): PipelineDocumentation {
            val doc = provider.generateContent(element, element)!!
            val specs = Regex("src=\"([^\"]+)\"").findAll(doc.html).map { it.groupValues[1] }.toList()
            assertTrue("悬浮文档应包含可读取图片", specs.isNotEmpty())
            specs.forEach {
                if (it.startsWith("data:image/png;base64,")) assertTrue("生成图片的内嵌 URL 应足够短", it.length < 2800)
                else {
                    val url = java.net.URI(it).toURL()
                    assertEquals("file", url.protocol)
                    assertNotNull("自适应加载器必须能直接读取图片 URL", ImageIO.read(url))
                }
                assertNotNull(doc.images[it])
                assertNotNull("旧版 IDEA 图片入口也应能解析", provider.getLocalImageForElement(element, it))
            }
            return doc
        }
        val templateDoc = checkImages(template.element)
        assertTrue(templateDoc.html.contains("button.png"))
        assertTrue("模板应直接引用原图文件", templateDoc.images.keys.single().endsWith("/button.png"))
        assertNativeImageRenders(templateDoc, Color.BLUE)
        val lower = source.findElementAt(source.text.indexOf("60,255"))!!
        val colors = checkImages(lower)
        assertTrue(colors.html.contains("HSV"))
        assertNativeImageRenders(colors, Color.GREEN)
        val comment = PsiTreeUtil.findChildOfType(source, com.intellij.psi.PsiComment::class.java)!!
        val preview = checkImages(comment)
        val full = PipelineVisualAssets.scaled(PipelineVisualAssets.overlay(referenceImage, operations, templatePreviews = templates), 420, 240)
        val actual = preview.images.values.single()
        assertEquals("Hover 应显示完整说明卡片", full.width, actual.width)
        assertEquals(full.height, actual.height)
        assertTrue("Hover 必须使用与大图一致的完整说明，不能走仅编号模式",
            full.getRGB(0, 0, full.width, full.height, null, 0, full.width)
                .contentEquals(actual.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width)))
        val previewReport = java.nio.file.Path.of("build", "reports", "hover-full-annotations.png")
        java.nio.file.Files.createDirectories(previewReport.parent)
        ImageIO.write(actual, "png", previewReport.toFile())
        assertTrue("带 ROI 的复杂缩略图应使用真实缓存文件", preview.images.keys.single().startsWith("file:"))
        assertNativeImageRenders(preview, Color.BLUE)
        assertTrue(preview.html.contains("参考图"))
        assertTrue(preview.html.contains("打开大图"))
        assertTrue(preview.html.contains("psi_element://maa-image:preview"))
        val roi = source.findElementAt(source.text.indexOf("10,10,50,40"))!!
        assertTrue(checkImages(roi).html.contains("maa-image:preview"))
        assertFalse(preview.html.contains("box_index")); assertFalse(preview.html.contains("实际结果框"))
        val popupPane = com.intellij.ui.components.JBHtmlPane().apply { text = nativeHtml }
        com.intellij.openapi.util.Disposer.register(testRootDisposable, popupPane)
        assertSame(popupPane, PipelineDocumentationPopup.pane(popupPane, modernTarget.popupMarker))
        assertNull(PipelineDocumentationPopup.pane(popupPane, "another-preview"))
        val large = PipelineHoverImages.load(comment, "preview")!!
        assertEquals(1280, large.image.width)
        assertEquals(3, large.operations.size)
        assertTrue(large.operations.any { it.type == "ColorMatch" })
        val largeColor = PipelineHoverImages.load(lower, "colors")!!
        assertEquals(Color.GREEN.rgb, largeColor.image.getRGB(0,0))
        val largeTemplate = PipelineHoverImages.load(template.element,
            "template:" + java.net.URLEncoder.encode(resource.virtualFile.url, java.nio.charset.StandardCharsets.UTF_8))!!
        assertEquals(Color.BLUE.rgb, largeTemplate.image.getRGB(0,0))
    }

    private fun assertNativeImageRenders(document: PipelineDocumentation, color: Color) {
        val pane = com.intellij.codeInsight.documentation.DocumentationHintEditorPane(project, emptyMap(), document.images::get)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, pane)
        pane.text = document.html
        pane.setSize(600, 1200)
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("原生文档组件未绘制实际图片", {
            val rendered = BufferedImage(600, 1200, BufferedImage.TYPE_INT_RGB)
            val graphics = rendered.createGraphics()
            try { pane.paint(graphics) } finally { graphics.dispose() }
            rendered.getRGB(0, 0, 600, 1200, null, 0, 600).count { it == color.rgb } > 500
        }, 15)
        pane.setSize(300, 1200)
        val sizes = (0 until 10).map {
            val preferred = pane.preferredSize
            pane.setSize(preferred.width.coerceIn(300, 480), preferred.height.coerceAtLeast(1))
            pane.doLayout()
            pane.preferredSize
        }
        assertEquals("悬浮图片加载后尺寸应收敛，不能持续交替缩放", 1, sizes.takeLast(4).distinct().size)
    }

    fun testSourceIndexDoesNotTreatCommentedRegistrationsAsImplementations() {
        val symbols = PipelineNavigationIndex.symbols("""
            // maa.AgentServerRegisterCustomAction("Fake", &Wrong{})
            /* maa.AgentServerRegisterCustomRecognition("FakeReco", &Wrong{}) */
            func (a *Real) Run(ctx int) bool { return true }
        """.trimIndent(), "go")
        assertFalse(symbols.keys.any { it.startsWith("custom:") })
        assertTrue(symbols.containsKey("run:Real"))
    }
}
