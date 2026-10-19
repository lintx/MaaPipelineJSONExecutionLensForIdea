package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assume
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JTabbedPane

class PipelineWorkspaceIntegrationTest : FileEditorManagerTestCase() {
    private fun fixture() = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
        {"Start":{"recognition":"DirectHit","next":["Caller","Done"]},
         "Caller":{"action":"Custom","custom_action":"SubTask","custom_action_param":{"sub":["Child"]}},
         "Child":{"next":["Done"]},"Done":{"recognition":"DirectHit"}}
    """.trimIndent()).virtualFile.also {
        myFixture.addFileToProject("assets/interface.json", """
            {"resource":[{"path":["./resource"]}],"import":["tasks/Tasks.json"],
             "languages":{"zh_cn":"zh.json","en_us":"en.json"}}
        """.trimIndent())
        myFixture.addFileToProject("assets/zh.json", """{"task.transfer":"库存转移"}""")
        myFixture.addFileToProject("assets/en.json", """{"task.transfer":"Inventory transfer"}""")
        myFixture.addFileToProject("assets/tasks/Tasks.json", """
            {"task":[{"name":"Transfer","label":"${'$'}task.transfer","entry":"Start","option":["Return"]},
                {"name":"Other","entry":"Start"},{"name":"Missing","entry":"Absent"}],
             "option":{"Return":{"default_case":"No","cases":[{"name":"No"},
                {"name":"Yes","pipeline_override":{"Start":{"next":["Done"]}}}]}}}
        """.trimIndent())
    }

    fun testFlowConditionSummariesKeepTypesCompositionAndCustomNamesWithoutParameters() {
        val file = fixture()
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {
              "Start":{"recognition":"And","all_of":[
                {"recognition":"OCR","expected":["很长的退出确认文字"],"roi":[350,250,550,100]},
                {"recognition":{"type":"TemplateMatch","param":{"template":"private/path/button.png"}}}
              ],"next":["Nested","Move"]},
              "Nested":{"recognition":{"type":"Or","param":{"any_of":[
                {"recognition":"ColorMatch","lower":[0,0,0]},
                {"recognition":"Custom","custom_recognition":"SceneCheck","custom_recognition_param":{"secret":"not for header"}},
                {"recognition":"OCR","inverse":true},
                "ReferencedCondition"
              ]}}},
              "Move":{}, "Direct":{"recognition":"DirectHit"},
              "Feature":{"recognition":"FeatureMatch"},
              "Classify":{"recognition":"NeuralNetworkClassify"},
              "Detect":{"recognition":"NeuralNetworkDetect"},
              "Inverted":{"recognition":{"type":"DirectHit","param":{"inverse":true}}}
            }
        """.trimIndent())
        replaceFixtureFile("assets/tasks/Tasks.json", """
            {"task":[{"name":"Transfer","entry":"Start","pipeline_override":{
              "Start":{"recognition":{"type":"Custom","param":{"custom_recognition":"OverrideCheck"}}}
            }}]}
        """.trimIndent())
        ReadAction.run<RuntimeException> {
            val model = PipelineDocumentIndexer.index(project, file)
            fun node(name: String) = model.nodes.single { it.name == name }
            assertEquals("And(OCR, TemplateMatch)", PipelineConditionSummary.flowCondition(node("Start")))
            assertEquals("Or(ColorMatch, 自定义(SceneCheck), Not(OCR), 引用(ReferencedCondition))",
                PipelineConditionSummary.flowCondition(node("Nested")))
            assertEquals("无条件执行", PipelineConditionSummary.flowCondition(node("Move")))
            assertEquals("无条件执行", PipelineConditionSummary.flowCondition(node("Direct")))
            assertEquals("Not(无条件执行)", PipelineConditionSummary.flowCondition(node("Inverted")))
            listOf("Feature" to "FeatureMatch", "Classify" to "NeuralNetworkClassify", "Detect" to "NeuralNetworkDetect")
                .forEach { (name, expected) -> assertEquals(expected, PipelineConditionSummary.flowCondition(node(name))) }
            assertTrue(node("Start").recognition!!.summary.contains("很长的退出确认文字"))
            assertTrue(node("Start").recognition!!.summary.contains("350"))
            val details = node("Start").recognition!!.readingDetails
            assertEquals(listOf(0, 1, 1), details.map { it.depth })
            assertEquals("And（全部满足）", details.first().text)
            assertTrue(details[1].text.startsWith("OCR · 文本="))
            assertTrue(details[1].text.contains("350"))
            assertTrue(details[2].text.contains("button.png"))
            assertEquals("Or(ColorMatch, 自定义(SceneCheck), Not(OCR), 引用(ReferencedCondition))",
                PipelineConditionSummary.flowCandidateCondition(node("Start").next.first()))
            val entry = model.pipelineEntries.single()
            val overrides = PipelineTaskContext.activeOverrides(entry, model.pipelineOverrides, emptyMap())
            val effective = PipelineDocumentIndexer.effectiveNodes(project, model, overrides, emptyMap(), entry.taskOptions)
            assertEquals("自定义(OverrideCheck)", PipelineConditionSummary.flowCondition(effective.getValue("${file.url}#Start")))
            assertEquals(listOf(PipelineConditionDetail("自定义 · 实现=OverrideCheck")),
                effective.getValue("${file.url}#Start").recognition!!.readingDetails)
            assertEquals(node("Nested").recognition!!.readingDetails, node("Start").next.first().target!!.recognitionDetails)
        }
    }

    fun testTaskCatalogKeepsMetadataMissingEntriesAndSelectedOverridesSeparate() {
        val file = fixture()
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        assertEquals(3, model.pipelineEntries.size)
        val entry = model.pipelineEntries.single { it.taskId == "Transfer" }
        assertTrue(PipelineTaskContext.matches("库存 转移", entry.searchText))
        assertTrue(PipelineTaskContext.matches("inventory", entry.searchText))
        assertNotNull(model.pipelineEntries.single { it.taskId == "Missing" }.issue)
        assertFalse(entry.stableId == model.pipelineEntries.single { it.taskId == "Other" }.stableId)
        assertEmpty(PipelineTaskContext.activeOverrides(entry, model.pipelineOverrides, emptyMap()))
        val overrides = PipelineTaskContext.activeOverrides(entry, model.pipelineOverrides, mapOf("Return" to listOf("Yes")))
        val effective = ReadAction.compute<Map<String, PipelineNodeLocation>, RuntimeException> {
            PipelineDocumentIndexer.effectiveNodes(project, model, overrides, emptyMap(), entry.taskOptions)
        }
        assertEquals(listOf("Done"), effective.getValue("${file.url}#Start").next.map { it.targetName })
        assertEquals(listOf("Caller", "Done"), model.projectNodes.single { it.name == "Start" }.next.map { it.targetName })
        val child = model.projectNodes.single { it.name == "Caller" }.embeddedReferences.single { it.kind == PipelineReferenceKind.SUBTASK }
        assertEquals("Child", child.target?.name)
    }

    fun testTaskSearchIncludesInactiveDescendantOptionsFromImportedFilesAndAllTranslations() {
        val file = fixture()
        replaceFixtureFile("assets/interface.json", """
            {"resource":[{"path":["./resource"]}],"import":["tasks/Tasks.json","tasks/Options.json"],
             "languages":{"zh_cn":"zh.json","en_us":"en.json"}}
        """.trimIndent())
        myFixture.addFileToProject("assets/tasks/Options.json", """
            {"option":{"Daily":{"label":"日用消耗","cases":[{"name":"Yes","option":["Items"]}]},
              "Items":{"label":"${'$'}items.label","description":"${'$'}items.description",
                "inputs":[{"name":"Count","label":"数量","description":"输入采购数量"}],
                "cases":[{"name":"Soap","label":"肥皂","option":["Daily"]}]}}}
        """.trimIndent())
        replaceFixtureFile("assets/tasks/Tasks.json", """
            {"task":[{"name":"Transfer","entry":"Start","option":["Return"]},
              {"name":"Other","entry":"Start"}],"option":{"Return":{"default_case":"No",
              "cases":[{"name":"No"},{"name":"Yes","option":["Daily"]}]}}}
        """.trimIndent())
        replaceFixtureFile("assets/zh.json", """{"items.label":"购买物品","items.description":"采购说明"}""")
        replaceFixtureFile("assets/en.json", """{"items.label":"Buy goods","items.description":"Shopping options"}""")
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val entry = model.pipelineEntries.single { it.taskId == "Transfer" }
        assertTrue(PipelineTaskContext.matches("日用消耗 购买物品", entry.searchText))
        assertTrue(PipelineTaskContext.matches("buy goods", entry.searchText))
        assertTrue(PipelineTaskContext.matches("采购说明 肥皂 输入采购数量", entry.searchText))
        assertFalse(PipelineTaskContext.matches("购买物品", model.pipelineEntries.single { it.taskId == "Other" }.searchText))
    }

    fun testTaskChooserFiltersInBulkWithoutOneListEventPerResult() {
        val file = fixture()
        val entries = (1..400).map { index -> PipelineEntryLocation(file, "Start", TextRange(0, 1),
            taskId = "Task$index", label = "任务 $index", searchText = "常用 购买物品 $index") }
        val dialog = PipelineTaskChooserDialog(project, entries, emptyList())
        Disposer.register(testRootDisposable, dialog.disposable)
        val list = descendants(dialog.chooserContent).filterIsInstance<com.intellij.ui.components.JBList<*>>().single()
        val query = descendants(dialog.chooserContent).filterIsInstance<com.intellij.ui.components.JBTextField>().single()
        var events = 0
        list.model.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(event: javax.swing.event.ListDataEvent) { events++ }
            override fun intervalRemoved(event: javax.swing.event.ListDataEvent) { events++ }
            override fun contentsChanged(event: javax.swing.event.ListDataEvent) { events++ }
        })
        query.text = "购买物品"
        assertEquals(400, list.model.size)
        assertTrue("过滤不应逐项发送事件：$events", events <= 4)
        query.text = "不存在"
        assertEquals(0, list.model.size)
        assertNull(dialog.selectedEntry)
    }

    fun testEmbeddedSourceUsesOriginalDocumentsAndDoesNotOpenFileTabs() {
        val first = fixture()
        val second = myFixture.addFileToProject("assets/resource/pipeline/Other.json", "{\n  \"Other\":{\"next\":[]}\n}").virtualFile
        val manager = FileEditorManager.getInstance(project)
        val openBefore = manager.openFiles.toSet()
        val host = PipelineWorkspaceSource(project)
        Disposer.register(testRootDisposable, host)
        host.showFile(first, TextRange(1, 2), false)
        host.showFile(second, TextRange(5, 10), false)
        val original = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(second)
        }!!
        val hosted = host.editorFor(second)
        assertSame(original, hosted.editor.document)
        assertEquals(5, hosted.editor.caretModel.offset)
        assertEquals(1, hosted.editor.caretModel.logicalPosition.line)
        assertEquals(openBefore, manager.openFiles.toSet())
        val before = original.text
        WriteCommandAction.runWriteCommandAction(project, "修改原始 JSON", null, Runnable { original.insertString(1, " ") })
        assertTrue(UndoManager.getInstance(project).isUndoAvailable(hosted))
        UndoManager.getInstance(project).undo(hosted)
        assertEquals(before, original.text)
        assertFalse(PipelineFileEditorProvider().accept(project, first))
        assertTrue(PipelineFileEditorProvider().accept(project, PipelineWorkspaceState.getInstance(project).file))
    }

    fun testIncrementalContentAndTopologyUpdatesMatchFullIndex() {
        val seed = fixture()
        val files = (0 until 20).map { group ->
            val nodes = (group * 100 until (group + 1) * 100).joinToString(",") { number ->
                "\"Synthetic$number\":{\"recognition\":\"DirectHit\",\"next\":[\"Synthetic${(number + 1) % 2000}\"]}"
            }
            myFixture.addFileToProject("assets/resource/pipeline/Group$group.json", "{$nodes}").virtualFile
        }
        fun full() = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, seed) }
        val original = full()
        val changed = files[10]
        val document = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(changed)
        }!!
        fun replace(old: String, new: String) {
            WriteCommandAction.runWriteCommandAction(project, "编辑测试节点", null, Runnable {
                val start = document.text.indexOf(old)
                assertTrue(start >= 0)
                document.replaceString(start, start + old.length, new)
            })
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        replace("DirectHit", "OCR")
        val started = System.nanoTime()
        val incremental = ReadAction.compute<PipelineDocumentModel, RuntimeException> {
            PipelineDocumentIndexer.index(project, seed, original, setOf(changed))
        }
        val incrementalMs = (System.nanoTime() - started) / 1_000_000
        val fullStarted = System.nanoTime()
        val expected = full()
        val fullMs = (System.nanoTime() - fullStarted) / 1_000_000
        println("2004 节点内容更新：增量 ${incrementalMs}ms，完整关联 ${fullMs}ms（不含去抖与 UI）")
        assertEquals(expected, incremental)
        assertSame(original.graphIndex, incremental.graphIndex)
        assertSame(original.pipelineEntries.first().taskOptions, incremental.pipelineEntries.first().taskOptions)
        assertSame(original.projectNodes.single { it.name == "Synthetic500" }, incremental.projectNodes.single { it.name == "Synthetic500" })
        assertEquals(incremental.projectNodes.single { it.name == "Synthetic1000" }.recognition?.summary,
            incremental.projectNodes.single { it.name == "Synthetic999" }.next.single().target?.recognitionSummary)
        replace("Synthetic1001\"", "Synthetic500\"")
        val topology = ReadAction.compute<PipelineDocumentModel, RuntimeException> {
            PipelineDocumentIndexer.index(project, seed, incremental, setOf(changed))
        }
        assertEquals(full(), topology)
        assertNotSame(incremental.graphIndex, topology.graphIndex)
    }

    fun testMetadataAndAddedDefinitionsInvalidateTheIncrementalCatalog() {
        val file = fixture()
        fun index(previous: PipelineDocumentModel? = null, changed: Set<com.intellij.openapi.vfs.VirtualFile> = emptySet()) =
            ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file, previous, changed) }
        val original = index()
        val locale = myFixture.tempDirFixture.getFile("assets/zh.json")!!
        assertTrue(locale in original.indexedVersions)
        replaceFixtureFile("assets/zh.json", """{"task.transfer":"转移测试更新"}""")
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val translated = index(original, setOf(locale))
        assertEquals(index(), translated)
        assertTrue(translated.pipelineEntries.single { it.taskId == "Transfer" }.searchText.contains("转移测试更新"))
        val added = myFixture.addFileToProject("assets/resource/pipeline/New.json", """{"Absent":{"next":["Start"]}}""").virtualFile
        val newDefinition = index(translated, setOf(added))
        assertEquals(index(), newDefinition)
        assertNull(newDefinition.pipelineEntries.single { it.taskId == "Missing" }.issue)
    }

    fun testEditsInTwoDocumentsUpdateTogetherWithoutMovingTheSourceCaret() {
        val file = fixture()
        val other = myFixture.addFileToProject("assets/resource/pipeline/Other.json",
            """{"Helper":{"doc":"before"}}""").virtualFile
        val catalog = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val entry = catalog.pipelineEntries.single { it.taskId == "Transfer" }
        val child = catalog.projectNodes.single { it.name == "Child" }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.data.lastContext = entry.stableId
        workspace.data.lastSourceUrl = file.url
        workspace.reading(entry.stableId).apply {
            selectedNode = "${file.url}#Child"
            sourceUrl = file.url
            sourceOffset = child.nameRange.startOffset + 2
            rightTab = 1
        }
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
        PlatformTestUtil.waitWithEventsDispatching("源码未恢复", {
            descendants(host.component).filterIsInstance<PipelineWorkspaceSource>().any { it.currentFile == file }
        }, 30)
        val selector = descendants(host.component).filterIsInstance<JComboBox<*>>().single()
        val source = descendants(host.component).filterIsInstance<PipelineWorkspaceSource>().single()
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        val textEditor = source.editorFor(file).editor
        val otherDocument = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(other)
        }!!
        val started = System.nanoTime()
        WriteCommandAction.runWriteCommandAction(project, "连续编辑两份 JSON", null, Runnable {
            val start = textEditor.document.text.indexOf("DirectHit")
            textEditor.document.replaceString(start, start + 9, "OCR")
            val description = otherDocument.text.indexOf("before")
            otherDocument.replaceString(description, description + 6, "after")
        })
        val expectedCaret = textEditor.caretModel.offset
        PlatformTestUtil.waitWithEventsDispatching("多文件变更没有合并同步", {
            val nodes = (0 until selector.model.size).map { selector.model.getElementAt(it) as PipelineNodeLocation }
            nodes.any { it.name == "Start" && it.rawJson.contains("\"OCR\"") } &&
                nodes.any { it.name == "Helper" && it.rawJson.contains("after") }
        }, 30)
        com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        println("双文档编辑到视图更新：${(System.nanoTime() - started) / 1_000_000}ms（测试夹具，含提交、合并等待与 UI 回调）")
        assertEquals(file, source.currentFile)
        assertEquals(expectedCaret, textEditor.caretModel.offset)
        assertEquals("Child", (selector.selectedItem as PipelineNodeLocation).name)
        manager.closeFile(workspace.file)
    }

    fun testEmbeddedSourceRoutesNativeUndoAndRedoActionsToTheVisibleJsonEditor() {
        com.intellij.ide.impl.HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        val file = fixture()
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
        PlatformTestUtil.waitWithEventsDispatching("嵌入编辑器未加载", {
            descendants(host.component).any { it is PipelineWorkspaceSource }
        }, 30)
        val source = descendants(host.component).filterIsInstance<PipelineWorkspaceSource>().single()
        descendants(host.component).filterIsInstance<JTabbedPane>().single().selectedIndex = 1
        source.showFile(file, TextRange(1, 1), false)
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        val hosted = source.editorFor(file)
        val document = hosted.editor.document
        val before = document.text
        WriteCommandAction.runWriteCommandAction(project, "输入 JSON 内容", null, Runnable { document.insertString(1, " ") })
        val context = com.intellij.ide.DataManager.getInstance().getDataContext(hosted.editor.contentComponent)
        assertSame(hosted, com.intellij.openapi.actionSystem.PlatformDataKeys.FILE_EDITOR.getData(context))
        assertSame(hosted.editor, com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.getData(context))
        assertEquals(file, com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE.getData(context))
        fun invoke(actionId: String) {
            val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(actionId)
            val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null,
                com.intellij.openapi.actionSystem.ActionPlaces.KEYBOARD_SHORTCUT, context)
            action.update(event)
            assertTrue("原生动作应可用：$actionId", event.presentation.isEnabled)
            action.actionPerformed(event)
        }
        invoke(com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO)
        assertEquals(before, document.text)
        invoke(com.intellij.openapi.actionSystem.IdeActions.ACTION_REDO)
        assertEquals(before.substring(0, 1) + " " + before.substring(1), document.text)
        manager.closeFile(workspace.file)
    }

    fun testReadingStateRoundTripsAcrossProjectPersistence() {
        val storage = PipelineWorkspaceStorage().apply {
            lastContext = "file:///tasks/A.json#task:Transfer"
            reopen = true
            recentTasks.add(lastContext)
            sessions[lastContext] = PipelineReadingState().apply {
                selectedNode = "file:///pipeline/A.json#Node"
                rightTab = 1
                rowKey = "flow:stable"
                rowOffset = 8
                collapsed.add("flow:folded")
                expandedBranches.add("flow:shared")
                selections["Return"] = mutableListOf("Yes")
                inputs["/option/Times/Count"] = "3"
            }
        }
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(storage), PipelineWorkspaceStorage::class.java)
        val reading = restored.sessions.getValue(restored.lastContext)
        assertEquals(storage.recentTasks, restored.recentTasks)
        assertEquals("Yes", reading.selections.getValue("Return").single())
        assertEquals("flow:stable", reading.rowKey)
        assertEquals(8, reading.rowOffset)
        assertEquals(1, reading.rightTab)
        assertEquals(listOf("flow:shared"), reading.expandedBranches)
    }

    fun testWorkspaceRestoresTaskAndDoesNotFollowOrdinaryJsonTabs() {
        val file = fixture()
        val catalog = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val transfer = catalog.pipelineEntries.single { it.taskId == "Transfer" }
        val other = catalog.pipelineEntries.single { it.taskId == "Other" }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.data.lastContext = transfer.stableId
        workspace.data.lastSourceUrl = file.url
        workspace.reading(transfer.stableId).apply {
            selectedNode = "${file.url}#Child"
            rightTab = 1
            sourceUrl = file.url
            sourceOffset = 3
        }
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
        PlatformTestUtil.waitWithEventsDispatching("工作区没有恢复", {
            descendants(host.component).filterIsInstance<JComboBox<*>>().any { (it.selectedItem as? PipelineNodeLocation)?.name == "Child" }
        }, 30)
        val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
        val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single()
        assertEquals(listOf("节点详情", "原始 JSON"), (0 until tabs.tabCount).map(tabs::getTitleAt))
        assertEquals(1, tabs.selectedIndex)
        assertEquals(3, descendants(panel).filterIsInstance<PipelineWorkspaceSource>().single().editorFor(file).editor.caretModel.offset)
        val openBefore = manager.openFiles.toSet()
        panel.activateTask(other)
        PlatformTestUtil.waitWithEventsDispatching("任务切换没有保存", {
            workspace.data.lastContext == other.stableId && workspace.reading(other.stableId).rootNode.endsWith("#Start")
        }, 30)
        assertEquals(openBefore, manager.openFiles.toSet())
        manager.openFile(file, true)
        assertEquals(other.stableId, workspace.data.lastContext)
        panel.activateTask(transfer)
        PlatformTestUtil.waitWithEventsDispatching("任务阅读状态丢失", {
            descendants(panel).filterIsInstance<JComboBox<*>>().any { (it.selectedItem as? PipelineNodeLocation)?.name == "Child" }
        }, 30)
        manager.closeFile(workspace.file)
        workspace.open()
        PlatformTestUtil.waitWithEventsDispatching("重新打开工作区没有恢复选择", {
            manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().any { editor ->
                descendants(editor.component).filterIsInstance<JComboBox<*>>().any { (it.selectedItem as? PipelineNodeLocation)?.name == "Child" }
            }
        }, 30)
        manager.closeFile(workspace.file)
        manager.closeFile(file)
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::descendants)

    fun testDynamicCandidatesOpenColdCrossFileEditorsAndCyclesCloseTheirBlocks() {
        val file = fixture()
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"next":["[Anchor]Resume","Loop"]},"Loop":{"next":["Start"]}}
        """.trimIndent())
        val other = myFixture.addFileToProject("assets/resource/pipeline/Other.json", """
            {
              "Setup":{"anchor":{"Resume":"Target"}},
              "Target":{"recognition":"OCR","expected":["继续"],"action":"Click"}
            }
        """.trimIndent()).virtualFile
        val catalog = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val entry = catalog.pipelineEntries.single { it.taskId == "Transfer" }
        val target = catalog.projectNodes.single { it.name == "Target" }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.data.lastContext = entry.stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
        PlatformTestUtil.waitWithEventsDispatching("流程未加载", {
            descendants(host.component).any { it is PipelineVirtualRowsPanel } && workspace.reading(entry.stableId).rootNode.endsWith("#Start")
        }, 30)
        val logic = descendants(host.component).filterIsInstance<PipelineVirtualRowsPanel>().single()
        val viewport = logic.parent as javax.swing.JViewport
        viewport.extentSize = java.awt.Dimension(1000, 4000)
        val attach = !logic.isDisplayable
        if (attach) logic.addNotify()
        try {
            fun labels() = descendants(logic).filterIsInstance<javax.swing.JLabel>()
            PlatformTestUtil.waitWithEventsDispatching("可能目标按钮未加载", {
                descendants(logic).filterIsInstance<javax.swing.JButton>().any { it.text.contains("个可能目标") }
            }, 30)
            val tabs = descendants(host.component).filterIsInstance<JTabbedPane>().single()
            val source = descendants(host.component).filterIsInstance<PipelineWorkspaceSource>().single()
            tabs.selectedIndex = 1
            val openBefore = manager.openFiles.toSet()
            descendants(logic).filterIsInstance<javax.swing.JButton>().single { it.text.contains("个可能目标") }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("候选未展开", {
                labels().any { it.text == "→ Target" }
            }, 30)
            val label = labels().single { it.text == "→ Target" }
            assertTrue(descendants(label.parent).filterIsInstance<javax.swing.JLabel>().any { it.text == "可能目标（运行时绑定）" })
            val click = java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 3, 3, 1, false, java.awt.event.MouseEvent.BUTTON1)
            label.mouseListeners.forEach { it.mouseClicked(click) }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals(other, source.currentFile)
            assertEquals(target.nameRange.startOffset, source.editorFor(other).editor.caretModel.offset)
            assertEquals(2, source.editorFor(other).editor.caretModel.logicalPosition.line)
            assertEquals(openBefore, manager.openFiles.toSet())
            assertTrue(labels().none { it.text.contains("本轮没有成功后继") || it.text.startsWith("否则：") })
            assertEquals(labels().count { it.text == "{" }, labels().count { it.text == "}" })
            assertTrue(logic.componentCount < 60)
            val cycle = labels().single { it.text.contains("· ↩ 循环") }
            val header = cycle.parent.parent as javax.swing.JPanel
            val controls = (header.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.SOUTH)
            assertNotNull("循环候选应和普通节点一样把按钮放在标题下方", controls)
            header.setSize(700, header.preferredSize.height)
            header.doLayout()
            assertEquals(0, controls.x)
            assertEquals(header.width, controls.width)
            descendants(logic).filterIsInstance<javax.swing.JButton>().single { it.text == "↩ 回到此层" }.doClick()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("Start", (descendants(host.component).filterIsInstance<JComboBox<*>>().single().selectedItem as PipelineNodeLocation).name)
        } finally {
            if (attach) logic.removeNotify()
            manager.closeFile(workspace.file)
        }
    }

    private fun replaceFixtureFile(path: String, text: String) {
        val file = myFixture.tempDirFixture.getFile(path)!!
        WriteCommandAction.runWriteCommandAction(project, "更新测试夹具", null, Runnable {
            file.setBinaryContent(text.toByteArray(Charsets.UTF_8))
        })
    }

    fun testKnownAnchorUsesAnOrdinaryInlineNodeAndKeepsTheOriginalEditableReference() {
        val file = fixture()
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"recognition":"And","all_of":[
               {"recognition":"OCR","expected":["退出确认"],"roi":[350,250,550,100]},
               {"recognition":"TemplateMatch","template":"some/long/path.png"}],
               "anchor":{"Resume":"Target","Unused":"Done"},"next":["[JumpBack][Anchor]Resume","Done"]},"Done":{}}
        """.trimIndent())
        val other = myFixture.addFileToProject("assets/resource/pipeline/Other.json", """
            {"Target":{"action":"TouchMove","anchor":{"Resume":""}},
             "Elsewhere":{"anchor":{"Resume":"Different"}},"Different":{"anchor":{"Resume":""}}}
        """.trimIndent()).virtualFile
        val catalog = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val entry = catalog.pipelineEntries.single { it.taskId == "Transfer" }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.data.lastContext = entry.stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
        PlatformTestUtil.waitWithEventsDispatching("流程未加载", {
            descendants(host.component).any { it is PipelineVirtualRowsPanel } && workspace.reading(entry.stableId).rootNode.endsWith("#Start")
        }, 30)
        val logic = descendants(host.component).filterIsInstance<PipelineVirtualRowsPanel>().single()
        (logic.parent as javax.swing.JViewport).extentSize = java.awt.Dimension(1000, 4000)
        val attach = !logic.isDisplayable
        if (attach) logic.addNotify()
        try {
            fun labels() = descendants(logic).filterIsInstance<javax.swing.JLabel>()
            PlatformTestUtil.waitWithEventsDispatching("已知 Anchor 没有直接展开", {
                labels().any { it.text.contains("→ Target · 运行时绑定") }
            }, 30)
            val label = labels().single { it.text.contains("→ Target · 运行时绑定") }
            assertTrue(label.toolTipText.contains("清除时跳过"))
            assertTrue(label.toolTipText.startsWith("<html>"))
            assertTrue(label.toolTipText.contains("父 next 第一项重试"))
            assertTrue(label.toolTipText.contains("父节点：Start"))
            assertTrue(labels().any { it.text == "if And(OCR, TemplateMatch)" })
            assertTrue(descendants(label.parent).filterIsInstance<javax.swing.JLabel>()
                .any { it.text == "1. [↶ 执行后重试] if Resume 已绑定" })
            assertTrue(labels().any { it.text == "绑定 Anchor：Resume → Target" })
            val clearLabel = labels().single { it.text == "清除 Anchor：Resume" }
            val actionIndex = labels().indexOfFirst { it.text.startsWith("执行动作 TouchMove") }
            assertTrue(actionIndex >= 0 && labels().indexOf(clearLabel) > actionIndex)
            assertTrue(labels().none { it.text.contains("Unused") })
            assertTrue(labels().none { it.text.contains("可能目标") || it.text.contains("设置 Anchor") || it.text.contains("存在清除") })
            assertTrue(descendants(logic).filterIsInstance<javax.swing.JButton>().none { it.text.contains("可能目标") })
            val header = label.parent.parent as javax.swing.JPanel
            val markers = descendants(header).filterIsInstance<javax.swing.JToggleButton>()
            assertTrue(markers.single { it.text == "锚" }.isSelected)
            val source = descendants(host.component).filterIsInstance<PipelineWorkspaceSource>().single()
            val tabs = descendants(host.component).filterIsInstance<JTabbedPane>().single()
            tabs.selectedIndex = 1
            val click = java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 3, 3, 1, false, java.awt.event.MouseEvent.BUTTON1)
            label.mouseListeners.forEach { it.mouseClicked(click) }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals(other, source.currentFile)
            val target = catalog.projectNodes.single { it.name == "Target" }
            val sourceEditor = source.editorFor(other).editor
            sourceEditor.caretModel.moveToOffset(0)
            val navigate = java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), java.awt.event.InputEvent.CTRL_DOWN_MASK, 3, 3, 1, false, java.awt.event.MouseEvent.BUTTON1)
            label.mouseListeners.forEach { it.mouseClicked(navigate) }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals(target.nameRange.startOffset, sourceEditor.caretModel.offset)
            val document = source.editorFor(file).editor.document
            val previousDialog = com.intellij.openapi.ui.TestDialogManager.setTestDialog { com.intellij.openapi.ui.Messages.YES }
            try { markers.single { it.text == "回" }.doClick() }
            finally { com.intellij.openapi.ui.TestDialogManager.setTestDialog(previousDialog) }
            assertTrue(document.text.contains("\"[Anchor]Resume\""))
            assertFalse(document.text.contains("[JumpBack]"))
            assertFalse(document.text.contains("[Anchor]Target"))
            WriteCommandAction.runWriteCommandAction(project, "修改上游 Anchor 目标", null, Runnable {
                val start = document.text.indexOf("\"Resume\":\"Target\"") + "\"Resume\":\"".length
                document.replaceString(start, start + "Target".length, "Different")
            })
            PlatformTestUtil.waitWithEventsDispatching("Anchor 改绑后流程未更新", {
                labels().any { it.text.contains("→ Different · 运行时绑定") } &&
                    labels().none { it.text.contains("→ Target · 运行时绑定") }
            }, 30)
            assertTrue(labels().any { it.text == "清除 Anchor：Resume" })
            val different = labels().single { it.text.contains("→ Different · 运行时绑定") }
            assertTrue(descendants(different.parent).filterIsInstance<javax.swing.JLabel>().any { it.text == "{" })
            assertTrue(descendants(different.parent).filterIsInstance<javax.swing.JLabel>()
                .any { it.text == "1. 无条件执行" })
        } finally {
            if (attach) logic.removeNotify()
            manager.closeFile(workspace.file)
        }
    }

    fun testRealMaaEndAccountSwitchHasOneOptionalMouseResetTargetAfterJumpBack() {
        val root = System.getenv("MAAEND_PATH")
        Assume.assumeTrue("未配置真实 MaaEnd 只读测试目标", !root.isNullOrBlank())
        VfsRootAccess.allowRootAccess(testRootDisposable, root!!)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath("$root/assets/resource/pipeline/AccountSwitch.json")
        Assume.assumeTrue("目标不含账号切换示例", file != null)
        val before = file!!.contentsToByteArray()
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val entry = model.pipelineEntries.single { it.taskId == "AccountSwitch" }
        val overrides = PipelineTaskContext.activeOverrides(entry, model.pipelineOverrides, emptyMap())
        val effective = ReadAction.compute<Map<String, PipelineNodeLocation>, RuntimeException> {
            PipelineDocumentIndexer.effectiveNodes(project, model, overrides, emptyMap(), entry.taskOptions)
        }
        val graph = PipelineReadingGraph(model.projectNodes) { effective[PipelineReadingGraph.key(it)] ?: it }
        val source = model.projectNodes.single { it.name == "__AccountSwitchLogoutConfirm" }
        val value = effective[PipelineReadingGraph.key(source)] ?: source
        val reference = value.next.single { it.isAnchor && it.targetName == "MouseMoveResetAnchor" }
        val flow = graph.anchorFlow
        val resolution = flow.resolve(reference, flow.forBranch(value.next, "next", flow.afterNode(source, PipelineAnchorContext())))
        assertEquals("MouseMoveReset", resolution.inlineTarget?.name)
        assertTrue(resolution.value.mayBeUnset)
        assertFalse(resolution.value.unknown)
        assertTrue(before.contentEquals(file.contentsToByteArray()))
    }

    fun testSourceCaretAndUnrelatedEditsPreserveFlowComponents() {
        val file = fixture()
        val unrelated = myFixture.addFileToProject("assets/resource/pipeline/Unrelated.json",
            """{"Unrelated":{"recognition":"DirectHit","doc":"before"}}""").virtualFile
        val catalog = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val entry = catalog.pipelineEntries.single { it.taskId == "Transfer" }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.data.lastContext = entry.stableId
        workspace.data.lastSourceUrl = file.url
        workspace.reading(entry.stableId).selections["Return"] = mutableListOf("Yes")
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
        val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
        val selector = descendants(panel).filterIsInstance<JComboBox<*>>().single()
        PlatformTestUtil.waitWithEventsDispatching("流程未加载", {
            workspace.reading(entry.stableId).rootNode.endsWith("#Start") && selector.selectedItem != null
        }, 30)
        val logic = descendants(panel).filterIsInstance<PipelineVirtualRowsPanel>().single()
        val viewport = logic.parent as javax.swing.JViewport
        viewport.extentSize = java.awt.Dimension(900, 1000)
        val attach = !logic.isDisplayable
        if (attach) logic.addNotify()
        try {
            PlatformTestUtil.waitWithEventsDispatching("流程可见行未创建", { logic.componentCount > 5 }, 30)
            val originalRows = logic.components.toList()
            val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single()
            val originalDetails = descendants(tabs.getComponentAt(0)).filterIsInstance<javax.swing.JLabel>()
            val source = descendants(panel).filterIsInstance<PipelineWorkspaceSource>().single()
            tabs.selectedIndex = 1
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            val textEditor = source.editorFor(file).editor
            val child = catalog.projectNodes.single { it.name == "Child" }
            textEditor.caretModel.moveToOffset(child.nameRange.startOffset)
            repeat(8) { textEditor.caretModel.moveToOffset(child.nameRange.startOffset + it % 3) }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("Child", (selector.selectedItem as PipelineNodeLocation).name)
            assertEquals(originalRows, logic.components.toList())
            assertEquals(originalDetails, descendants(tabs.getComponentAt(0)).filterIsInstance<javax.swing.JLabel>())
            tabs.selectedIndex = 0
            assertTrue(descendants(tabs.getComponentAt(0)).filterIsInstance<javax.swing.JLabel>().any { it.text == "Child" })
            assertEquals(originalRows, logic.components.toList())

            val previousModel = selector.model
            val document = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(unrelated)
            }!!
            WriteCommandAction.runWriteCommandAction(project, "修改无关节点", null, Runnable {
                val start = document.text.indexOf("before")
                document.replaceString(start, start + 6, "after")
            })
            PlatformTestUtil.waitWithEventsDispatching("无关文件修改未索引", { selector.model !== previousModel }, 30)
            com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.waitWithEventsDispatching("更新后流程尚未就绪", {
                descendants(logic).filterIsInstance<javax.swing.JLabel>().any { it.text?.contains("Start") == true }
            }, 30)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals(originalRows, logic.components.toList())
            val updated = (0 until selector.model.size).map { selector.model.getElementAt(it) as PipelineNodeLocation }
                .single { it.name == "Unrelated" }
            assertTrue(updated.rawJson.contains("after"))
        } finally {
            if (attach) logic.removeNotify()
            manager.closeFile(workspace.file)
        }
    }

    fun testFirstOpenAndMissingSavedTaskDoNotSilentlyPickTheFirstTask() {
        val file = fixture()
        val workspace = PipelineWorkspaceState.getInstance(project)
        val manager = FileEditorManager.getInstance(project)
        workspace.data.lastContext = ""
        workspace.data.lastSourceUrl = file.url
        workspace.open()
        fun readyHost(): PipelineFileEditor {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            PlatformTestUtil.waitWithEventsDispatching("工作区未就绪", {
                descendants(host.component).filterIsInstance<javax.swing.JLabel>().any { it.text?.startsWith("项目资源：") == true }
            }, 30)
            return host
        }
        assertNull(descendants(readyHost().component).filterIsInstance<JComboBox<*>>().single().selectedItem)
        assertEquals("", workspace.data.lastContext)
        manager.closeFile(workspace.file)
        workspace.data.lastContext = "${file.url}#task:Removed"
        workspace.open()
        val restored = readyHost()
        assertNull(descendants(restored.component).filterIsInstance<JComboBox<*>>().single().selectedItem)
        assertTrue(descendants(restored.component).filterIsInstance<javax.swing.JLabel>()
            .any { it.text?.contains("上次任务或流程已不存在") == true })
        manager.closeFile(workspace.file)
    }

    fun testRealMaaEndInventoryTaskCanResolveDeclaredSubtasksAndDynamicCandidates() {
        val root = System.getenv("MAAEND_PATH")
        Assume.assumeTrue("未配置真实 MaaEnd 只读测试目标", !root.isNullOrBlank())
        VfsRootAccess.allowRootAccess(testRootDisposable, root!!)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath("$root/assets/resource/pipeline/ItemTransfer.json")
        Assume.assumeTrue("目标不含库存转移示例", file != null)
        val before = file!!.contentsToByteArray()
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val entry = model.pipelineEntries.single { it.taskId == "ItemTransfer" }
        val fullStarted = System.nanoTime()
        val fullRefresh = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val fullMs = (System.nanoTime() - fullStarted) / 1_000_000
        val updateStarted = System.nanoTime()
        val contentRefresh = ReadAction.compute<PipelineDocumentModel, RuntimeException> {
            PipelineDocumentIndexer.index(project, file, model, setOf(file))
        }
        val updateMs = (System.nanoTime() - updateStarted) / 1_000_000
        println("真实 MaaEnd ${model.projectNodes.size} 节点热缓存关联：完整 ${fullMs}ms，增量 ${updateMs}ms（只读，无内容变更，不含 PSI 提交和 UI）")
        assertEquals(fullRefresh, contentRefresh)
        val shopping = model.pipelineEntries.single { it.taskId == "AutoStockStaple" }
        assertTrue(PipelineTaskContext.matches("购买物品", shopping.searchText))
        assertTrue(PipelineTaskContext.matches("四号谷地 日用消耗 购买物品", shopping.searchText))
        assertNull(entry.issue)
        val nodes = model.projectNodes.associateBy { it.name }
        val caller = nodes.getValue("ItemTransferInBackpack")
        assertTrue(caller.embeddedReferences.any { it.kind == PipelineReferenceKind.SUBTASK &&
            it.target?.name == "ItemTransferStashBackpackSubTask" })
        assertTrue(nodes.values.any { node -> node.anchorDefinitions.any {
            it.targetNodeName == "ItemTransferOriginDispatch"
        } })
        val active = PipelineTaskContext.activeOverrides(entry, model.pipelineOverrides, emptyMap())
        val effective = ReadAction.compute<Map<String, PipelineNodeLocation>, RuntimeException> {
            PipelineDocumentIndexer.effectiveNodes(project, model, active, emptyMap(), entry.taskOptions)
        }
        assertFalse(effective["${file.url}#ItemTransferReturnGate"]?.enabled
            ?: nodes.getValue("ItemTransferReturnGate").enabled)
        val graph = PipelineReadingGraph(model.projectNodes) { effective[PipelineReadingGraph.key(it)] ?: it }
        val path = graph.path(nodes.getValue("ItemTransferInBackpack"), nodes.getValue("ItemTransferStashBackpackSubTask"))
        assertNotNull(path)
        assertEquals("子任务", path!![1].first)
        assertTrue(graph.reachable(nodes.getValue("ItemTransferInBackpack"))
            .contains(PipelineReadingGraph.key(nodes.getValue("ItemTransferOriginDispatch"))))
        assertTrue(before.contentEquals(file.contentsToByteArray()))
    }
}
