package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PipelineJsonSupportTest : BasePlatformTestCase() {
    fun testRegisteredParameterCompletionWithDevelopmentProjectSchema() {
        val projectPath = System.getenv("MAAEND_PATH") ?: return
        val schemaRoot = java.nio.file.Path.of(projectPath, "tools", "schema")
        if (!java.nio.file.Files.isDirectory(schemaRoot)) return
        // 真实 Schema 的多层分支与必填约束会影响未完成 JSON 的补全，不能只用简化参数样例验证。
        java.nio.file.Files.walk(schemaRoot).use { files ->
            files.filter { java.nio.file.Files.isRegularFile(it) && it.toString().endsWith(".json") }.forEach { source ->
                myFixture.addFileToProject("tools/schema/" + schemaRoot.relativize(source).toString().replace('\\', '/'),
                    java.nio.file.Files.readString(source, Charsets.UTF_8))
            }
        }
        com.jetbrains.jsonSchema.ide.JsonSchemaService.Impl.get(project).reset()
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, schemaRoot.toString())
        val localSchema = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(schemaRoot.resolve("pipeline.schema.json"))!!
        val localBundle = PipelineProjectSchemaBundle.build(localSchema)!!
        val service = com.jetbrains.jsonSchema.ide.JsonSchemaService.Impl.get(project)
        val runtimeSchema = service.getSchemaObjectForSchemaFile(localBundle.file)!!
        val customReference = runtimeSchema.getDefinitionByName("CustomRecognitionSchema")!!
        assertTrue("不能依赖仅在测试模式启用的跨文件解析", customReference.ref!!.startsWith("#"))
        assertNotNull(customReference.resolveRefSchema(service))
        val variants = listOf(
            """"recognition":"Custom","custom_recognition":"IconRecognition","custom_recognition_param":{"grid<caret>"}""",
            """"recognition":"Custom","custom_recognition":"IconRecognition","custom_recognition_param":{"ty<caret>"}""",
            """"recognition":{"type":"Custom","param":{"custom_recognition":"IconRecognition","custom_recognition_param":{"ty<caret>"}}}""",
            """"recognition":{"type":"And","param":{"all_of":[{"custom_recognition":"IconRecognition","custom_recognition_param":{"ty<caret>"}}]}}""",
            """"recognition":"And","all_of":[{"recognition":"Custom","custom_recognition":"IconRecognition","custom_recognition_param":{"TY<caret>"}}]""",
            """"recognition":"And","all_of":[{"type":"Custom","param":{"custom_recognition":"IconRecognition","custom_recognition_param":{"ty<caret>"}}}]""",
            """"recognition":{"type":"And","param":{"all_of":[{"recognition":{"type":"Custom","param":{"custom_recognition":"IconRecognition","custom_recognition_param":{"ty<caret>"}}}}]}}"""
        )
        for (body in variants) complete("""{"Start":{$body}}""", "grid_type")
    }

    fun testNestedV1AndV2CompletionsUseTheNearestOperation() {
        val fields = listOf(
            """"recognition":{"type":"And","param":{"all_of":[{"recog<caret>"}]}}""" to "recognition",
            """"recognition":{"type":"And","param":{"all_of":[{"recognition":{"ty<caret>"}}]}}""" to "type",
            """"recognition":{"type":"And","param":{"all_of":[{"recognition":{"type":"TemplateMatch","param":{"temp<caret>"}}}]}}""" to "template",
            """"recognition":"And","all_of":[{"recognition":"TemplateMatch","method":<caret>}]""" to "10001",
            """"recognition":{"type":"And","param":{"all_of":[{"recognition":{"type":"ColorMatch","param":{"method":<caret>}}}]}}""" to "40",
            """"recognition":{"type":"And","param":{"all_of":[{"recognition":{"type":"And","param":{"all_of":["A","B"],"box_index":<caret>}}}]}}""" to "1",
            """"action":{"type":"Click","param":{"targ<caret>"}}""" to "target"
        )
        for ((body, candidate) in fields) complete("""{"Start":{$body}}""", candidate)
    }

    fun testProjectSchemaCompletesRegisteredParametersAndEnumsInBothOperationFormats() {
        val schema = myFixture.addFileToProject("tools/schema/pipeline.schema.json", """
            {"${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
             "type":"object","additionalProperties":{"${'$'}ref":"#/${'$'}defs/node"},
             "${'$'}defs":{
               "recognition":{"properties":{"custom_recognition":{"enum":["FixtureRecognition"]}},
                 "allOf":[{"if":{"required":["custom_recognition"],"properties":{"custom_recognition":{"const":"FixtureRecognition"}}},
                   "then":{"properties":{"custom_recognition_param":{"${'$'}ref":"components/fixture.schema.json#/${'$'}defs/recognition"}}}}]},
               "action":{"properties":{"custom_action":{"enum":["FixtureAction"]}},
                 "allOf":[{"if":{"required":["custom_action"],"properties":{"custom_action":{"const":"FixtureAction"}}},
                   "then":{"properties":{"custom_action_param":{"${'$'}ref":"components/fixture.schema.json#/${'$'}defs/action"}}}}]},
               "node":{"allOf":[{"${'$'}ref":"#/${'$'}defs/recognition"},{"${'$'}ref":"#/${'$'}defs/action"}],
                 "properties":{
                   "recognition":{"anyOf":[{"type":"string"},{"type":"object","properties":{"type":{"type":"string"},"param":{"${'$'}ref":"#/${'$'}defs/recognition"}}}]},
                   "action":{"anyOf":[{"type":"string"},{"type":"object","properties":{"type":{"type":"string"},"param":{"${'$'}ref":"#/${'$'}defs/action"}}}]},
                   "all_of":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/node"}}
                 }}
             }}
        """.trimIndent())
        myFixture.addFileToProject("tools/schema/components/fixture.schema.json", """
            {"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","${'$'}defs":{
              "recognition":{"type":"object","properties":{
                "grid_type":{"type":"string","enum":["trade","transfer"]},
                "nested":{"type":"object","properties":{"threshold":{"type":"number","default":0.85}}}}},
              "action":{"type":"object","properties":{"mode":{"type":"string","enum":["camera","character"]}}}
            }}
        """.trimIndent())
        val service = com.jetbrains.jsonSchema.ide.JsonSchemaService.Impl.get(project)
        service.reset()
        val source = myFixture.addFileToProject("assets/resource/pipeline/Custom.json", """{"Start":{"recognition":"Custom"}}""")
        val mapped = service.getSchemaFilesForFile(source.virtualFile).single()
        val snapshot = PipelineVisualModel.parse(String(mapped.contentsToByteArray(), Charsets.UTF_8))!!
        val expectedOrigin = if (schema.virtualFile.fileSystem.protocol == "file") schema.virtualFile.toNioPath().toUri().toASCIIString()
            else java.net.URI(schema.virtualFile.url.replace(" ", "%20")).toASCIIString()
        assertEquals("schema 基址必须是标准 URI", expectedOrigin, snapshot.get("\$id").asString)
        assertTrue("项目 schema 必须交给 IDEA 原生服务", mapped.name == "MaaPipeline.project.schema.json")
        val bodies = listOf(
            """"recognition":"Custom","custom_recognition":"FixtureRecognition","custom_recognition_param":{"grid<caret>"}""" to "grid_type",
            """"recognition":{"type":"Custom","param":{"custom_recognition":"FixtureRecognition","custom_recognition_param":{"grid<caret>"}}}""" to "grid_type",
            """"recognition":"And","all_of":[{"recognition":{"type":"Custom","param":{"custom_recognition":"FixtureRecognition","custom_recognition_param":{"grid_type":"tr<caret>"}}}}]""" to "trade",
            """"recognition":"Custom","custom_recognition":"FixtureRecognition","custom_recognition_param":{"grid_type":"ans<caret>"}""" to "transfer",
            """"recognition":"Custom","custom_recognition":"FixtureRecognition","custom_recognition_param":{"nested":{"thre<caret>"}}""" to "threshold",
            """"action":"Custom","custom_action":"FixtureAction","custom_action_param":{"mo<caret>"}""" to "mode",
            """"action":{"type":"Custom","param":{"custom_action":"FixtureAction","custom_action_param":{"mode":"cam<caret>"}}}""" to "camera"
        )
        for ((body, candidate) in bodies) complete("""{"Start":{$body}}""", candidate)
        val ordinary = myFixture.addFileToProject("ordinary.json", """{"custom_action_param":{}}""")
        assertTrue("项目 Pipeline schema 不应关联普通 JSON", service.getSchemaFilesForFile(ordinary.virtualFile).isEmpty())
    }

    fun testNodeReferencesCompleteInEverySupportedFieldAndNestedForm() {
        val target = "__ScenePrivateLoginContinue"
        myFixture.addFileToProject("assets/resource/pipeline/Definitions.json", """
            {"$target":{"recognition":"ColorMatch"},"${target}Extra":{},
             "Binding":{"anchor":{"Resume":"$target","ResumeOther":"${target}Extra"}}}
        """.trimIndent())
        val references = listOf(
            """"recognition":"And","all_of":["SPC<caret>"]""",
            """"recognition":"Or","any_of":["SPC<caret>"]""",
            """"recognition":{"type":"And","param":{"all_of":["SPC<caret>"]}}""",
            """"recognition":"And","all_of":[{"type":"Or","param":{"any_of":["SPC<caret>"]}}]""",
            """"on_error":"SPC<caret>"""",
            """"on_error":["SPC<caret>"]""",
            """"on_error":{"name":"SPC<caret>","jump_back":true}""",
            """"on_error":[{"name":"SPC<caret>"}]""",
            """"next":{"name":"SPC<caret>"}""",
            """"recognition":"OCR","roi":"SPC<caret>"""",
            """"recognition":"OCR","color_filter":"SPC<caret>"""",
            """"action":"Click","target":"SPC<caret>"""",
            """"action":{"type":"Swipe","param":{"begin":"SPC<caret>"}}""",
            """"action":"Swipe","end":["SPC<caret>"]""",
            """"action":{"type":"MultiSwipe","param":{"swipes":[{"end":"SPC<caret>"}]}}""",
            """"action":"Click","pre_wait_freezes":{"target":"SPC<caret>"}""",
            """"anchor":{"Resume":"SPC<caret>"}""",
            """"action":"Custom","custom_action":"SubTask","custom_action_param":{"sub":["SPC<caret>"]}"""
        )
        for (fields in references) {
            val file = complete("""{"Start":{$fields}}""", target)
            assertTrue("引用应写入完整节点名：$fields", file.text.contains("\"$target\""))
        }
        complete("""{"task":[{"name":"Task","entry":"SPC<caret>"}]}""", target)
        complete("""{"task":[{"entry":"Start","pipeline_override":{"SPC<caret>":{}}}]}""", target)
        val anchorForms = listOf(
            """"on_error":{"name":"Res<caret>","anchor":true}""",
            """"next":["[JumpBack][Anchor]Res<caret>"]""",
            """"recognition":"OCR","roi":"[Anchor]Res<caret>""",
            """"action":"Click","target":"[Anchor]Res<caret>"""
        )
        for (fields in anchorForms) {
            val marker = if ("[JumpBack]" in fields) "[JumpBack][Anchor]" else if ("[Anchor]" in fields) "[Anchor]" else ""
            complete("""{"Start":{$fields}}""", "${marker}Resume")
        }
        complete("""{"Start":{"recognition":"And","all_of":[
            {"sub_name":"icon","recognition":"TemplateMatch"},
            {"recognition":"OCR","roi":"ic<caret>"},
            {"sub_name":"future","recognition":"OCR"}]}}""", "icon")
        for (fields in listOf(
            """"recognition":"And","all_of":[{"recognition":"OCR","expected":"SPC<caret>"}]""",
            """"action":"Custom","custom_action_param":{"target":"SPC<caret>"}""",
            """"recognition":"And","all_of":[{"recognition":"TemplateMatch","template":"SPC<caret>"}]"""
        )) {
            myFixture.configureByText("Completion.json", """{"Start":{$fields}}""")
            myFixture.complete(CompletionType.BASIC)
            assertTrue("普通内容不能收到节点候选：$fields", myFixture.lookupElements.orEmpty().none { it.lookupString == target })
        }
    }

    fun testCustomCompletionResolvesRegistrationConstantsAndInsertsValidV1AndV2Operations() {
        myFixture.addFileToProject("agent/demo/register.go", """
            package demo
            func Register() {
                maa.AgentServerRegisterCustomRecognition(recognitionName, &Recognizer{})
                maa.AgentServerRegisterCustomAction(actionName, &Action{})
                maa.AgentServerRegisterCustomRecognition("FriendlyRecognitionExtra", &Recognizer{})
                maa.AgentServerRegisterCustomAction("FriendlyActionExtra", &Action{})
            }
        """.trimIndent())
        myFixture.addFileToProject("agent/demo/names.go", """
            package demo
            const recognitionName = "FriendlyRecognition"
            const actionName = "FriendlyAction"
        """.trimIndent())
        myFixture.addFileToProject("agent/other/names.go", """
            package other
            const recognitionName = "UnregisteredRecognition"
        """.trimIndent())
        assertTrue("不能从其他包误取同名常量", "UnregisteredRecognition" !in PipelineNavigationIndex.customNames(project, "recognition"))
        for (kind in listOf("recognition", "action")) {
            val name = if (kind == "recognition") "FriendlyRecognition" else "FriendlyAction"
            val bodies = listOf(
                """"$kind":"friend<caret>","custom_${kind}_param":{"keep":true}""" to emptyList(),
                """"$kind":{"type":"friend<caret>","param":{"custom_$kind":"Old","custom_${kind}_param":{"keep":true}}}""" to listOf(kind, "param"),
                """"$kind":{"type":"friend<caret>"}""" to listOf(kind, "param")
            )
            for ((fields, path) in bodies) {
                val file = complete("""{"Start":{$fields}}""", name)
                val root = PipelineVisualModel.parse(file.text)!!.getAsJsonObject("Start")
                val type = if (path.isEmpty()) root.get(kind) else root.getAsJsonObject(kind).get("type")
                assertEquals("Custom", type.asString)
                val parameters = path.fold(root) { owner, key -> owner.getAsJsonObject(key) }
                assertEquals(name, parameters.get("custom_$kind").asString)
                if ("keep" in fields) assertTrue(parameters.getAsJsonObject("custom_${kind}_param").get("keep").asBoolean)
            }
            complete("""{"Start":{"$kind":"Custom","custom_$kind":"friend<caret>"}}""", name)
        }
        val inline = complete("""{"Start":{"recognition":"And","all_of":[{"type":"friend<caret>"}]}}""", "FriendlyRecognition")
        val child = PipelineVisualModel.parse(inline.text)!!.getAsJsonObject("Start").getAsJsonArray("all_of")[0].asJsonObject
        assertEquals("Custom", child.get("type").asString)
        assertEquals("FriendlyRecognition", child.getAsJsonObject("param").get("custom_recognition").asString)
    }

    private fun complete(text: String, candidate: String): JsonFile {
        myFixture.configureByText("Completion.json", text)
        myFixture.complete(CompletionType.BASIC)
        val item = myFixture.lookupElements?.firstOrNull { it.lookupString == candidate }
        if (myFixture.lookupElements != null) {
            assertNotNull("缺少候选 $candidate：$text；实际 ${myFixture.lookupElements?.map { it.lookupString }}", item)
            myFixture.lookup.currentItem = item
            myFixture.finishLookup('\n')
        } else {
            assertTrue("唯一候选应已插入：$text", myFixture.editor.document.text.contains(candidate))
        }
        return myFixture.file as JsonFile
    }

    fun testNodeCompletionMatchesCaseFragmentsAndCamelHumpsAndInsertsCanonicalNames() {
        val target = "__ScenePrivateLoginContinue"
        myFixture.addFileToProject("assets/resource/pipeline/Definitions.json",
            """{"$target":{"desc":"继续登录"},"${target}Extra":{}}""")
        for (prefix in listOf("ScenePri", "Private", "SPC", "__scenepri", "private", "sceneprivate")) {
            assertTrue("匹配器应接受 $prefix", PipelineCompletionMatcher(prefix).prefixMatches(target))
            myFixture.configureByText("Completion.json", """{"Start":{"next":["$prefix<caret>"]}}""")
            myFixture.complete(CompletionType.BASIC)
            val candidate = myFixture.lookupElements?.firstOrNull { it.lookupString == target }
            assertNotNull("输入 $prefix 应匹配 $target；候选 ${myFixture.lookupElements?.map { it.lookupString }}", candidate)
            myFixture.lookup.currentItem = candidate
            myFixture.finishLookup('\n')
            assertTrue("输入 $prefix 后应写入完整节点名，实际为 ${myFixture.editor.document.text}",
                myFixture.editor.document.text.contains("\"next\":[\"$target\"]"))
        }
    }

    fun testDescriptionsAndNativeReferenceHintsUseIndexedUsagesWithoutChangingJson() {
        myFixture.addFileToProject("assets/interface.json", """{"resource":[{"path":["./resource"]}],"import":["tasks/Tasks.json"]}""")
        myFixture.addFileToProject("assets/tasks/Tasks.json", """{"task":[{"name":"Run","entry":"Target"}]}""")
        myFixture.addFileToProject("assets/resource/pipeline/Other.json", """
            {"Other":{"next":["Target"],"all_of":["Target"],"target":"Target","template":"Target"}}
        """.trimIndent())
        val file = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
            {
              "Start":{"desc":"任务入口","next":["Target","[JumpBack]Target",{"name":"Target","jump_back":true},{"name":"Resume","anchor":true}]},
              "Target":{"desc":"目标说明","anchor":{"Resume":"Target"},"recognition":"OCR"}
            }
        """.trimIndent()) as JsonFile
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val lookup = PipelineJsonSymbolLookup(project, file)
        assertEquals("目标说明", lookup.description("Target"))
        assertEquals("目标说明", lookup.description("Resume", anchor = true))
        val usages = lookup.usages("Target")
        assertEquals(8, usages.size)
        assertTrue(usages.any { it.containingFile.name == "Other.json" })
        assertTrue(usages.any { it.containingFile.name == "Tasks.json" })
        assertTrue(usages.none { (it.parent as? com.intellij.json.psi.JsonProperty)?.name == "template" })
        val before = myFixture.editor.document.text
        val hints = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : com.intellij.codeInsight.hints.InlayHintsSink by com.intellij.codeInsight.hints.InlayHintsSinkImpl(myFixture.editor) {
            override fun addInlineElement(offset: Int, relatesToPrecedingText: Boolean,
                presentation: com.intellij.codeInsight.hints.presentation.InlayPresentation, placeAtTheEndOfLine: Boolean) {
                hints += offset to placeAtTheEndOfLine
            }
        }
        val provider = PipelineJsonDescriptionHints()
        val collector = provider.getCollectorFor(file, myFixture.editor, provider.createSettings(), sink)!!
        val literals = PsiTreeUtil.findChildrenOfType(file, com.intellij.json.psi.JsonStringLiteral::class.java)
        literals.forEach { collector.collect(it, myFixture.editor, sink) }
        assertTrue(literals.filter { it.value in setOf("Target", "[JumpBack]Target", "Resume") &&
            (it.parent as? com.intellij.json.psi.JsonProperty)?.nameElement != it }.all { it.textRange.endOffset to true in hints })
        val root = file.topLevelValue as JsonObject
        assertTrue("定义处不重复显示 desc", root.propertyList.none { property -> hints.any { it.first == property.nameElement.textRange.endOffset } })
        myFixture.doHighlighting()
        assertTrue("说明必须显示在行尾", myFixture.editor.inlayModel.getAfterLineEndElementsInRange(0, before.length).isNotEmpty())
        assertTrue(com.intellij.codeInsight.hints.InlayHintsProviderExtension.findProviders().any { it.provider is PipelineJsonDescriptionHints })
        val blocks = myFixture.editor.inlayModel.getBlockElementsInRange(0, before.length)
        assertEquals("定义上方必须实际显示原生引用提示", root.propertyList.size, blocks.size)
        assertEquals(before, myFixture.editor.document.text)
    }

    fun testComplianceOnlyRelaxesMaaFilesAndCompletionInsertsValidPropertiesAndValues() {
        val pipeline = myFixture.addFileToProject("assets/resource/pipeline/Main.json", "{\"A\":{ // comment\n \"action\":\"Click\",},}")
        val ordinary = myFixture.addFileToProject("ordinary.json", "{ // comment\n \"a\":1,}")
        val provider = PipelineJsonCompliance()
        assertTrue(provider.isCommentAllowed(PsiTreeUtil.findChildOfType(pipeline, PsiComment::class.java)!!))
        assertFalse(provider.isCommentAllowed(PsiTreeUtil.findChildOfType(ordinary, PsiComment::class.java)!!))
        assertTrue(provider.isTrailingCommaAllowed((pipeline as JsonFile).topLevelValue!!))
        assertFalse(provider.isTrailingCommaAllowed((ordinary as JsonFile).topLevelValue!!))
        val parsed = PipelineVisualModel.parse("{\"recognition\":\"OCR\",\"expected\":[\"// text,}\",],/* tail */}")!!
        assertEquals("OCR", parsed.get("recognition").asString)
        assertEquals(1, parsed.getAsJsonArray("expected").size())
        assertEquals("// text,}", parsed.getAsJsonArray("expected")[0].asString)
        myFixture.configureByText("Completion.json", """{"A":{"action":"Click","recog<caret>"}}""")
        myFixture.complete(CompletionType.BASIC)
        val lookup = myFixture.lookupElements?.firstOrNull { it.lookupString == "recognition" }
        assertNotNull(lookup)
        myFixture.lookup.currentItem = lookup
        myFixture.finishLookup('\n')
        val root = (myFixture.file as JsonFile).topLevelValue as JsonObject
        assertEquals("\"DirectHit\"", (root.findProperty("A")!!.value as JsonObject).findProperty("recognition")!!.value!!.text)
        myFixture.configureByText("Completion.json", """{"A":{"action":<caret>}}""")
        myFixture.complete(CompletionType.BASIC)
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "Click" }
        myFixture.finishLookup('\n')
        assertTrue(myFixture.file.text.contains("\"action\":\"Click\""))
    }

    fun testOffsetIsAppliedToCommentBaseAndDefaultBackgroundRemainsAvailable() {
        val text = """{"recognition":"OCR","roi":[/* @roi [10,20,30,40] */1,2,3,4],"roi_offset":[2,3,4,5]}"""
        val node = PipelineNodeLocation("A", com.intellij.openapi.util.TextRange.EMPTY_RANGE, com.intellij.openapi.util.TextRange.EMPTY_RANGE, rawJson = text)
        val region = PipelineVisualModel.region(PipelineVisualModel.operation(node))
        assertEquals(listOf(12, 23, 34, 45), region.coordinates)
        assertTrue(region.fromComment)
        val settings = PipelinePreviewSettings.getInstance()
        val saved = settings.state
        try {
            settings.loadState(PipelinePreviewDefaults())
            val source = myFixture.addFileToProject("A.json", "{}").virtualFile
            val image = settings.background(source)!!.second
            assertEquals(1280, image.width)
            assertEquals(720, image.height)
            assertEquals(0xFFFFFF, image.getRGB(0, 0) and 0xFFFFFF)
        } finally { settings.loadState(saved) }
    }
}
