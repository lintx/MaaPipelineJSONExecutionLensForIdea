package com.maapipeline.executionlens.pipeline

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
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics -> graphics.color = Color.BLUE; graphics.fillRect(0, 0, 120, 80); graphics.dispose() }
        val output = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val resource = myFixture.addFileToProject("assets/resource/image/button.png", "")
        WriteCommandAction.runWriteCommandAction(project, Runnable { resource.virtualFile.setBinaryContent(output) })
        val source = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {"Start":{
              // @preview assets/resource/image/button.png
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
        assertTrue("IDEA 的悬浮提供器应调用插件图片预览", registeredDoc.contains("maa-preview:"))
        val modern = com.intellij.platform.backend.documentation.DocumentationTargetProvider.EP_NAME.extensionList
            .filterIsInstance<PipelineDocumentationTargetProvider>().single()
        val result = modern.documentationTargets(source, template.element.textOffset + 1).single().computeDocumentation()!!
        val content = result.javaClass.declaredFields.single { it.name == "content" }.apply { isAccessible = true }.get(result)
        val nativeHtml = content.javaClass.getMethod("getHtml").invoke(content) as String
        val imageResolver = content.javaClass.getMethod("getImageResolver").invoke(content) as com.intellij.lang.documentation.DocumentationImageResolver
        val nativeImages = Regex("src=\"([^\"]+)\"").findAll(nativeHtml).toList()
        assertTrue("新版原生悬浮必须收到实际图片映射", nativeImages.isNotEmpty())
        nativeImages.forEach { assertNotNull(imageResolver.resolveImage(it.groupValues[1])) }
        val provider = PipelineJsonDocumentationProvider()
        fun checkImages(element: PsiElement): String {
            val doc = provider.generateDoc(element, element)!!
            val specs = Regex("src=\"([^\"]+)\"").findAll(doc).map { it.groupValues[1] }.toList()
            assertTrue("悬浮文档应包含可读取图片", specs.isNotEmpty())
            specs.forEach { assertNotNull(provider.getLocalImageForElement(element, it)) }
            return doc
        }
        assertTrue(checkImages(template.element).contains("button.png"))
        val lower = source.findElementAt(source.text.indexOf("60,255"))!!
        assertTrue(checkImages(lower).contains("HSV"))
        val comment = PsiTreeUtil.findChildOfType(source, com.intellij.psi.PsiComment::class.java)!!
        val preview = checkImages(comment)
        assertTrue(preview.contains("参考图"))
        assertTrue(preview.contains("TemplateMatch") && preview.contains("ColorMatch"))
        assertTrue(preview.replace("<br>", "").contains("实际结果框在运行时确定"))
        val previewText = preview.replace("<br>", "")
        assertTrue(previewText.contains("3. Click") && previewText.contains("1. TemplateMatch 的识别结果"))
        assertTrue(previewText.contains("And.box_index=0"))
        assertTrue(previewText.contains("归一化相关系数"))
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
