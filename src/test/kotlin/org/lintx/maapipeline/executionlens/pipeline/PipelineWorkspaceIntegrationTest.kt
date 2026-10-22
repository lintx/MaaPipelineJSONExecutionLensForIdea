package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JTabbedPane
import javax.swing.JLabel
import javax.swing.text.JTextComponent

class PipelineWorkspaceIntegrationTest : FileEditorManagerTestCase() {
    fun testControllerResourcesMergePartialNodesAndResolveAddedNodes() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/interface.json", """
            {"resource":[{"path":["./resource"]}],"import":["tasks/Tasks.json"],
             "controller":[{"name":"Desktop","type":"Win32"},
               {"name":"ADB","type":"Adb","attach_resource_path":["./resource_adb"]},
               {"name":"Cloud","type":"Adb","attach_resource_path":["./resource_adb","./resource_cloud"]}]}
        """.trimIndent())
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"desc":"基础说明","action":"Click","target":[10,20,30,40],"next":["Child"]},
             "Child":{"recognition":"OCR","expected":"desktop","roi":[1,2,3,4]}}
        """.trimIndent())
        val adb = myFixture.addFileToProject("assets/resource_adb/pipeline/Overrides.json", """
            {"Start":{"target":[50,60,70,80]},"Child":{"expected":"adb","next":["Added"]},
             "Added":{"desc":"移动端节点","anchor":{"Mobile":"Child"}}}
        """.trimIndent()).virtualFile
        myFixture.addFileToProject("assets/resource_cloud/pipeline/Cloud.json", """{"Child":{"expected":"cloud"}}""")
        fun index(id: String, previous: PipelineDocumentModel? = null, changes: Set<com.intellij.openapi.vfs.VirtualFile> = emptySet()) =
            ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file, previous, changes, id) }
        val desktop = index("Desktop")
        val mobile = index("ADB", desktop)
        assertEquals(3, mobile.projectNodes.size)
        val start = mobile.projectNodes.single { it.name == "Start" }
        val child = mobile.projectNodes.single { it.name == "Child" }
        assertEquals(file, start.sourceFile)
        assertEquals("基础说明", start.description)
        assertEquals("Click", PipelineVisualModel.operation(start, true).type)
        assertTrue(start.rawJson.contains("[50,60,70,80]"))
        assertEquals("OCR", PipelineVisualModel.operation(child).type)
        assertEquals("Added", child.next.single().target?.name)
        assertEquals(adb, child.next.single().sourceFile)
        assertEquals(2, mobile.resourceOverrides.size)
        assertTrue(mobile.projectNodes.none { node -> node.next.any { it.isAmbiguousTarget } })
        assertTrue(index("Cloud").projectNodes.single { it.name == "Child" }.rawJson.contains("cloud"))
        val overlayDocument = FileDocumentManager.getInstance().getDocument(adb)!!
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            overlayDocument.setText("""{"Start":{"target":[1,1,2,2]},"Child":{"expected":"changed"},"Added":{}}""")
        })
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(overlayDocument)
        assertTrue(index("ADB", mobile, setOf(adb)).projectNodes.single { it.name == "Child" }.rawJson.contains("changed"))
        assertEquals(desktop.projectNodes.map { it.rawJson }, index("Desktop", mobile).projectNodes.map { it.rawJson })
    }

    fun testDynamicTemplateSearchRegionsKeepTheirOwnTemplateImagesInThePreview() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"recognition":"And","all_of":["Color","ButtonA","ButtonB"]},
             "Color":{"recognition":"ColorMatch","method":40,"lower":[60,255,255],"upper":[60,255,255]},
             "ButtonA":{"recognition":"TemplateMatch","roi":"Color","template":"ButtonA.png","action":"Click"},
             "ButtonB":{"recognition":"TemplateMatch","roi":"Color","template":"ButtonB.png"}}
        """.trimIndent())
        val colors = listOf(java.awt.Color.MAGENTA, java.awt.Color.CYAN)
        colors.forEachIndexed { index, color ->
            val image = java.awt.image.BufferedImage(if (index == 0) 80 else 240, 80, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try { graphics.color = color; graphics.fillRect(12, 12, 40, 40) } finally { graphics.dispose() }
            val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
            val target = myFixture.addFileToProject("assets/resource/image/Button${if (index == 0) "A" else "B"}.png", "").virtualFile
            WriteCommandAction.runWriteCommandAction(project, Runnable { target.setBinaryContent(bytes) })
        }
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val nodes = model.projectNodes.associateBy { it.name }
        for (name in listOf("Start", "ButtonA", "ButtonB")) {
            val operations = PipelineVisualAssets.leaves(PipelineVisualModel.operation(nodes.getValue(name), resolve = nodes::get))
            val templates = PipelineVisualAssets.previewTemplates(project, file, operations, nodes::get)
            val expected = if (name == "Start") listOf("ButtonA.png", "ButtonB.png") else listOf("$name.png")
            assertEquals(expected, templates.values.flatten().map { it.name })
            assertTrue(templates.values.flatten().all { it.image != null })
            if (name == "Start") {
                val sizes = templates.values.flatten().mapIndexed { index, template ->
                    val image = template.image!!
                    val matching = (0 until image.height).flatMap { y -> (0 until image.width).mapNotNull { x ->
                        (x to y).takeIf { image.getRGB(x, y) == colors[index].rgb }
                    } }
                    (matching.maxOf { it.first } - matching.minOf { it.first } + 1) to
                        (matching.maxOf { it.second } - matching.minOf { it.second } + 1)
                }
                assertEquals("不同裁剪留白中的同尺寸按钮应按相同像素比例显示", sizes[0], sizes[1])
            }
            val overlay = PipelineVisualAssets.overlay(java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB),
                operations, nodes::get, templatePreviews = templates)
            val pixels = overlay.getRGB(0, 0, overlay.width, overlay.height, null, 0, overlay.width)
            expected.forEach { template ->
                val color = colors[if (template == "ButtonA.png") 0 else 1]
                assertTrue("$name 的 $template 必须绘制在预览中", pixels.count { it == color.rgb } >= 24 * 12)
            }
            if (name == "Start") {
                fun row(color: java.awt.Color) = pixels.indexOfFirst { it == color.rgb } / overlay.width
                assertTrue("同框条件应依次显示颜色样本、模板 A、模板 B", row(java.awt.Color.GREEN) < row(colors[0]) && row(colors[0]) < row(colors[1]))
            }
        }
        val action = PipelineVisualModel.operation(nodes.getValue("ButtonA"), true, nodes::get)
        val actionTemplates = PipelineVisualAssets.previewTemplates(project, file, listOf(action), nodes::get)
        assertEquals("ButtonA.png", actionTemplates.values.flatten().single().name)
        assertNotNull(actionTemplates.values.flatten().single().image)
    }

    fun testControllerPreviewLayersAndRoiEditsUseTheActiveAnnotationSource() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/interface.json", """
            {"resource":[{"path":["./resource"]}],"controller":[{"name":"Desktop","type":"Win32"},
             {"name":"Mobile","type":"Adb","attach_resource_path":["./resource_adb"]},
             {"name":"CloudADB","type":"Adb","attach_resource_path":["./resource_adb","./resource_cloud_adb"]}]}
        """.trimIndent())
        val definition = """{
            // @preview desktop.png
            // @preview adb mobile.png
            // @preview CloudADB cloud.png
            // @roi [1,2,3,4]
            // @roi adb [10,20,30,40]
            "recognition":"OCR","roi":[0,0,0,0],"roi_offset":[1,2,3,4]
        }""".trimIndent()
        replaceFixtureFile("assets/resource/pipeline/Main.json", """{"Start":$definition,"Fallback":$definition}""")
        val patch = myFixture.addFileToProject("assets/resource_adb/pipeline/Patch.json", """{"Start":{
            // @preview patch.png
            // @roi adb [40,50,20,10]
            // @roi [2,3,4,5]
        }}""").virtualFile
        listOf("assets/resource/pipeline/desktop.png", "assets/resource/pipeline/mobile.png",
            "assets/resource/pipeline/cloud.png", "assets/resource_adb/pipeline/patch.png").forEachIndexed { index, path ->
            val image = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.setRGB(99, 99, index + 1)
            val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
            val target = myFixture.addFileToProject(path, "").virtualFile
            WriteCommandAction.runWriteCommandAction(project, Runnable { target.setBinaryContent(bytes) })
        }
        fun catalog(environment: String) = ReadAction.compute<PipelineDocumentModel, RuntimeException> {
            PipelineDocumentIndexer.index(project, file, environmentId = environment)
        }.projectNodes.associateBy { it.name }
        val desktop = catalog("Desktop").getValue("Start")
        assertEquals("desktop.png", PipelinePreviewHints.preview(desktop)?.path)
        val mobile = catalog("Mobile")
        val start = mobile.getValue("Start")
        val hint = PipelinePreviewHints.preview(start)!!
        assertEquals("patch.png", hint.path)
        assertEquals(patch, hint.sourceFile)
        assertEquals("mobile.png", PipelinePreviewHints.preview(mobile.getValue("Fallback"))?.path)
        assertEquals("cloud.png", PipelinePreviewHints.preview(catalog("CloudADB").getValue("Fallback"))?.path)

        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.data.lastContext = "preview-test"
        val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file) as com.intellij.json.psi.JsonFile
        fun comment(name: String) = com.intellij.psi.util.PsiTreeUtil.findChildOfType(
            (psi.topLevelValue as com.intellij.json.psi.JsonObject).findProperty(name), com.intellij.psi.PsiComment::class.java)!!
        for ((environment, name, marker) in listOf(Triple("Desktop", "Start", 1), Triple("Mobile", "Start", 4),
            Triple("Mobile", "Fallback", 2), Triple("CloudADB", "Fallback", 3))) {
            workspace.reading(workspace.data.lastContext).environmentId = environment
            val loaded = PipelineHoverImages.load(comment(name), "preview")!!
            assertEquals(marker, loaded.image.getRGB(99, 99) and 0xFFFFFF)
            assertNotNull(PipelineJsonDocumentationProvider().generateDoc(comment(name), comment(name)))
        }

        val operation = PipelineVisualModel.operation(start)
        val session = PipelineRoiEditSession(project, listOf(operation))
        val annotation = PipelinePreviewModel.annotations(listOf(operation)).single()
        assertEquals(listOf(41,52,23,14), annotation.region.coordinates)
        assertNull(session.unavailable(annotation))
        val baseDocument = FileDocumentManager.getInstance().getDocument(file)!!
        val originalBase = baseDocument.text
        val patchDocument = FileDocumentManager.getInstance().getDocument(patch)!!
        val originalPatch = patchDocument.text
        assertTrue(session.apply(annotation, listOf(50,60,30,20)))
        assertEquals(originalBase, baseDocument.text)
        assertEquals(originalPatch.replace("@roi adb [40,50,20,10]", "@roi adb [50, 60, 30, 20]"), patchDocument.text)
        assertEquals(listOf(51,62,33,24), PipelineVisualModel.region(session.currentOperation(operation)).coordinates)

        val fallback = PipelineVisualModel.operation(mobile.getValue("Fallback"))
        val fallbackSession = PipelineRoiEditSession(project, listOf(fallback))
        assertTrue(fallbackSession.apply(PipelinePreviewModel.annotations(listOf(fallback)).single(), listOf(15,25,35,45)))
        val fallbackHint = "@roi adb [10,20,30,40]"
        val fallbackStart = originalBase.lastIndexOf(fallbackHint)
        assertEquals(originalBase.replaceRange(fallbackStart, fallbackStart + fallbackHint.length, "@roi adb [15, 25, 35, 45]"), baseDocument.text)
    }

    fun testWorkspaceUrlCanBeRestoredWithoutAnExistingFileInstance() {
        val file = PipelineWorkspaceState.getInstance(project).file
        assertTrue(file.url.startsWith("maa-pipeline:///"))
        assertSame(file, com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(file.url))
        val restored = PipelineWorkspaceFileSystem().findFileByPath(file.path)
        assertNotNull(restored)
        assertNotSame(file, restored)
        assertEquals(file.url, restored!!.url)
        assertTrue(PipelineFileEditorProvider().accept(project, restored))
        assertNull(PipelineWorkspaceFileSystem().findFileByPath("/missing"))
    }

    fun testWorkspaceControllerSearchSourceTabsAndSharedNodeLocations() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Root":{"next":["Left","Right"]},"Left":{"desc":"移动端终点","next":["Shared"]},
             "Right":{"next":["Shared"]},"Shared":{"desc":"共享终点"}}
        """.trimIndent())
        replaceFixtureFile("assets/tasks/Tasks.json", """{"task":[{"name":"Transfer","entry":"Root"}]}""")
        replaceFixtureFile("assets/interface.json", """
            {"resource":[{"path":["./resource"]}],"import":["tasks/Tasks.json"],
             "controller":[{"name":"Desktop","type":"Win32"},
              {"name":"ADB","type":"Adb","attach_resource_path":["./resource_adb"]}]}
        """.trimIndent())
        val overlay = myFixture.addFileToProject("assets/resource_adb/pipeline/Mobile.json", """
            {"Start":{"action":"Click","target":[30,40,50,60]},"Shared":{"action":"Click","target":[30,40,50,60]}}
        """.trimIndent()).virtualFile
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = model.pipelineEntries.single { it.taskId == "Transfer" }.stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            fun field(name: String) = PipelineFlowPanel::class.java.getDeclaredField(name).apply { isAccessible = true }
            fun current() = field("currentModel").get(panel) as PipelineDocumentModel
            fun ready() = !field("contextLoading").getBoolean(panel)
            val environment = descendants(panel).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-environment-selector" }
            PlatformTestUtil.waitWithEventsDispatching("环境列表未就绪", {
                current().version >= 0 && ready() && (0 until environment.itemCount).any { (environment.getItemAt(it) as PipelineEnvironment).id == "ADB" }
            }, 30)
            assertEquals("首启应使用 manifest 的第一个真实控制器", "Desktop", current().environmentId)
            environment.selectedItem = (0 until environment.itemCount).map { environment.getItemAt(it) }
                .single { (it as PipelineEnvironment).id == "ADB" }
            PlatformTestUtil.waitWithEventsDispatching("控制器切换未应用到工作区", { current().environmentId == "ADB" && ready() }, 30)
            val selector = descendants(panel).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-node-selector" }
            val search = field("nodeSearch").get(panel) as com.intellij.ui.SearchTextField
            search.text = "移动端终点"
            assertEquals(1, selector.itemCount)
            assertEquals("Left", (selector.getItemAt(0) as PipelineNodeLocation).name)
            search.text = "Shared"
            assertEquals(1, selector.itemCount)
            assertEquals("Shared", (selector.getItemAt(0) as PipelineNodeLocation).name)
            search.text = ""
            val shared = current().projectNodes.single { it.name == "Shared" }
            selector.selectedItem = shared
            val logic = field("logicPanel").get(panel) as PipelineRowsPanel
            val components = logic.components.toList()
            assertTrue(logic.findRows("共享终点").isNotEmpty())
            assertEquals("查找不能重建流程", components, logic.components.toList())
            logic.findRows("")
            val locate = PipelineFlowPanel::class.java.getDeclaredMethod("locateDetailInFlow").apply { isAccessible = true }
            locate.invoke(panel)
            val first = field("locatedOccurrence").get(panel)
            locate.invoke(panel)
            assertNotSame("共享节点应能巡回定位：${field("logicOccurrences").get(panel)}", first, field("locatedOccurrence").get(panel))
            val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single { it.name == "pipeline-detail-tabs" }
            tabs.selectedIndex = 1
            val source = descendants(panel).filterIsInstance<PipelineWorkspaceSource>().single()
            assertEquals(file, source.currentFile)
            val resources = descendants(source).filterIsInstance<JTabbedPane>().single()
            resources.selectedIndex = (0 until resources.tabCount).single { resources.getTitleAt(it) == "resource_adb" }
            assertEquals(overlay, source.currentFile)
            fun selectedResource() = resources.getTitleAt(resources.selectedIndex)
            assertEquals("resource_adb", selectedResource())
            tabs.selectedIndex = 0
            tabs.selectedIndex = 1
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals(overlay, source.currentFile)
            assertEquals("resource_adb", selectedResource())
            source.clearNodeSources()
            source.showNode(shared, current().resourceOverrides.filter { it.targetName == shared.name }, "ADB")
            assertEquals("resource_adb", selectedResource())
            source.showFile(file, shared.nameRange, false)
            assertEquals("基础资源 · resource", selectedResource())
            source.showFile(overlay, TextRange(0, 0), false)
            assertEquals("resource_adb", selectedResource())
            val nativeTabs = manager.openFiles.toSet()
            val patch = current().resourceOverrides.single { it.targetName == "Shared" }
            PipelineFlowPanel::class.java.getDeclaredMethod("navigateToFileRange", com.intellij.openapi.vfs.VirtualFile::class.java, TextRange::class.java)
                .apply { isAccessible = true }.invoke(panel, patch.file, patch.range)
            assertTrue(manager.isFileOpen(overlay))
            assertTrue(manager.openFiles.toSet().containsAll(nativeTabs))
        } finally { manager.closeFile(workspace.file) }
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            val current = PipelineFlowPanel::class.java.getDeclaredField("currentModel").apply { isAccessible = true }
            PlatformTestUtil.waitWithEventsDispatching("重开工作区未恢复 ADB 资源", {
                val restored = current.get(panel) as PipelineDocumentModel
                restored.version >= 0 && restored.environmentId == "ADB" && restored.resourceOverrides.any { it.file == overlay }
            }, 30)
            val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single { it.name == "pipeline-detail-tabs" }
            tabs.selectedIndex = 1
            val resources = descendants(panel).filterIsInstance<JTabbedPane>().single { it.name == "pipeline-resource-sources" }
            assertTrue((0 until resources.tabCount).any { resources.getTitleAt(it) == "resource_adb" })
        } finally { manager.closeFile(workspace.file) }
    }

    fun testSimulationAdvancesOneCandidateAndRunsBranchesInPlace() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"next":["Disabled","Branch"]},"Disabled":{"enabled":false},
             "Branch":{"next":["Left","Right"],"on_error":["Error"]},
             "Left":{},"Right":{},"Error":{}}
        """.trimIndent())
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = model.pipelineEntries.single { it.taskId == "Transfer" }.stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            fun field(name: String) = PipelineFlowPanel::class.java.getDeclaredField(name).apply { isAccessible = true }
            val logic = field("logicPanel").get(panel) as PipelineRowsPanel
            val details = field("detailsPanel").get(panel) as javax.swing.JPanel
            PlatformTestUtil.waitWithEventsDispatching("模拟流程未就绪", {
                (field("currentModel").get(panel) as PipelineDocumentModel).version >= 0 &&
                    !field("contextLoading").getBoolean(panel) && textComponents(logic).any { it.text.contains("→ Branch") }
            }, 30)
            fun invoke(name: String) = PipelineFlowPanel::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(panel)
            fun step() = PipelineFlowPanel::class.java.getDeclaredMethod("chooseSimulationStep", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(panel, false)
            fun cursor() = field("simulationCursor").get(panel) as PipelinePlaybackCursor
            fun runs(container: Component) = descendants(container).filterIsInstance<javax.swing.JButton>()
                .filter { it.name == "pipeline-simulation-run" && it.isVisible }
            val fold = descendants(logic).filterIsInstance<javax.swing.JComponent>().single { it.toolTipText == "收起Branch" }
            val click = java.awt.event.MouseEvent(fold, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 3, 3, 1, false, java.awt.event.MouseEvent.BUTTON1)
            fold.mouseListeners.forEach { it.mouseClicked(click) }
            assertFalse(textComponents(logic).any { it.text.contains("→ Left") })
            invoke("startSimulation")
            assertEquals(1, runs(logic).size)
            val toolbar = field("playbackToolbar").get(panel) as PipelinePlaybackToolbar
            PipelinePlaybackToolbar::class.java.getDeclaredField("follow").apply { isAccessible = true }.setBoolean(toolbar, false)
            step()
            assertEquals("Branch", cursor().frame?.nodeName)
            assertEquals(2, cursor().size)
            assertTrue("进入已折叠节点应自动展开，与跟随滚动开关无关", textComponents(logic).any { it.text.contains("→ Left") })
            step()
            assertEquals("多候选等待就地选择，不能擅自前进", 2, cursor().size)
            assertEquals(3, runs(logic).size)
            runs(logic).single { it.toolTipText.contains("next → Left") }.doClick()
            assertEquals("Left", cursor().frame?.nodeName)
            toolbar.seek(1)
            val selector = field("nodeSelector").get(panel) as JComboBox<*>
            selector.selectedItem = (0 until selector.itemCount).map(selector::getItemAt)
                .single { (it as PipelineNodeLocation).name == "Right" }
            runs(details).single().doClick()
            assertEquals("Right", cursor().frame?.nodeName)
            assertEquals("从历史分岔后应丢弃旧路径", 3, cursor().size)
            toolbar.seek(1)
            runs(logic).single { it.toolTipText.contains("on_error → Error") }.doClick()
            assertEquals("Error", cursor().frame?.nodeName)
            invoke("stopSimulation")
            assertEmpty(runs(logic))
        } finally { manager.closeFile(workspace.file) }
    }

    fun testDetailsReturnToTopOnNodeAndTabChangesButKeepPositionOnRefresh() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"desc":"${"说明\\n".repeat(50)}","next":["Child"]},
             "Child":{"desc":"${"子节点\\n".repeat(50)}"}}
        """.trimIndent())
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = model.pipelineEntries.single { it.taskId == "Transfer" }.stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val panel = descendants(manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single().component)
                .filterIsInstance<PipelineFlowPanel>().single()
            fun field(name: String) = PipelineFlowPanel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(panel)
            val details = field("detailsPanel") as javax.swing.JPanel
            val scroll = field("detailsScroll") as javax.swing.JScrollPane
            PlatformTestUtil.waitWithEventsDispatching("详情未就绪", { textComponents(details).any { it.text == "Start" } }, 30)
            scroll.setSize(420, 300)
            scroll.doLayout()
            scroll.viewport.doLayout()
            details.doLayout()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            textComponents(details).filterIsInstance<JTextComponent>().forEach { label ->
                label.caretPosition = 0
                label.caretPosition = label.document.length
            }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("未聚焦的只读文本不能因延迟光标更新将详情滚到底部", 0, scroll.viewport.viewPosition.y)
            fun moveDown() {
                scroll.viewport.viewPosition = java.awt.Point(0, 180)
                assertEquals(180, scroll.viewport.viewPosition.y)
            }
            moveDown()
            PipelineFlowPanel::class.java.getDeclaredMethod("renderSelectedNode", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(panel, false)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("同节点刷新保留阅读位置", 180, scroll.viewport.viewPosition.y)
            val selector = field("nodeSelector") as JComboBox<*>
            selector.selectedItem = (0 until selector.itemCount).map(selector::getItemAt)
                .single { (it as PipelineNodeLocation).name == "Child" }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("切节点应回到开头", 0, scroll.viewport.viewPosition.y)
            moveDown()
            val tabs = field("rightTabs") as JTabbedPane
            tabs.selectedIndex = 1
            tabs.selectedIndex = 0
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("切回详情应回到开头", 0, scroll.viewport.viewPosition.y)
        } finally { manager.closeFile(workspace.file) }
    }

    fun testMissingControllerOverlayCanBeDeclinedOrCreatedAsNodeOnlyJson() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/interface.json", """
            {"resource":[{"path":["./resource"]}],
             "controller":[{"name":"CloudADB","type":"Adb",
               "attach_resource_path":["./resource_adb","./resource_cloud_adb"]}]}
        """.trimIndent())
        val adb = myFixture.addFileToProject("assets/resource_adb/pipeline/Main.json", """{"Start":{"desc":"ADB 覆盖"}}""").virtualFile
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file, environmentId = "CloudADB") }
        val node = model.projectNodes.single { it.name == "Start" }
        val source = PipelineWorkspaceSource(project)
        try {
            source.showNode(node, model.resourceOverrides, "CloudADB")
            val tabs = descendants(source).filterIsInstance<JTabbedPane>().single()
            assertEquals(listOf("基础资源 · resource", "resource_adb", "resource_cloud_adb · 创建"), (0 until tabs.tabCount).map(tabs::getTitleAt))
            tabs.selectedIndex = 1
            assertEquals(adb, source.currentFile)
            var previousDialog = com.intellij.openapi.ui.TestDialogManager.setTestDialog { com.intellij.openapi.ui.Messages.NO }
            try { tabs.selectedIndex = 2 } finally { com.intellij.openapi.ui.TestDialogManager.setTestDialog(previousDialog) }
            assertNull(myFixture.tempDirFixture.getFile("assets/resource_cloud_adb/pipeline/Main.json"))
            assertEquals(1, tabs.selectedIndex)
            previousDialog = com.intellij.openapi.ui.TestDialogManager.setTestDialog { com.intellij.openapi.ui.Messages.YES }
            try { tabs.selectedIndex = 2 } finally { com.intellij.openapi.ui.TestDialogManager.setTestDialog(previousDialog) }
            val created = myFixture.tempDirFixture.getFile("assets/resource_cloud_adb/pipeline/Main.json")
            assertNotNull(created)
            assertEquals(created, source.currentFile)
            val parsed = PipelineJsonc.parse(PipelineProjectFiles.text(created!!)).asJsonObject
            assertEquals(setOf("Start"), parsed.keySet())
            assertTrue(parsed.getAsJsonObject("Start").entrySet().isEmpty())
        } finally { source.dispose() }
    }

    private fun textComponents(root: Component): List<Component> = descendants(root)
        .filter { it is JLabel || it is JTextComponent }

    private val Component.text: String
        get() = when (this) {
            is JLabel -> text.orEmpty()
            is JTextComponent -> text.orEmpty()
            else -> ""
        }

    fun testManyOptionalOverridesStayCompactAndPreserveTheirSources() {
        val file = fixture(maaEnd = false)
        val longRecognitionValue = "recognition-parameter-".repeat(14) + "complete-value-marker"
        val cases = (0..39).joinToString(",") { index ->
            """{"name":"Choice$index","label":"选项$index","pipeline_override":{"Start":{"post_delay":${index % 4 * 100}}}}"""
        }
        replaceFixtureFile("assets/tasks/Tasks.json", """
            {"task":[{"name":"Transfer","entry":"Start","option":["Variant"]}],
             "option":{"Variant":{"label":"测试分类","default_case":"Choice0","cases":[$cases]}}}
        """.trimIndent())
        val log = myFixture.addFileToProject("debug/override.log", """
            [t][Px1][Tx1][MaaNS::Tasker::post_task] [entry=Start] [pipeline_override={"Start":{"template":["logged-template.png"],"recognition":{"param":{"custom_recognition_param":"$longRecognitionValue"}}}}]
            [t][Px1][Tx1][MaaNS::TaskNS::Context::create] [id=9]
            [t][Px1][Tx2] [msg=Tasker.Task.Starting] [details={"entry":"Start","task_id":9}]
        """.trimIndent()).virtualFile
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = model.pipelineEntries.single().stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            fun field(name: String) = PipelineFlowPanel::class.java.getDeclaredField(name).apply { isAccessible = true }
            PlatformTestUtil.waitWithEventsDispatching("任务覆盖尚未加载", {
                descendants(host.component).filterIsInstance<PipelineFlowPanel>().any { panel ->
                    (field("currentModel").get(panel) as PipelineDocumentModel).version >= 0 && !field("contextLoading").getBoolean(panel)
                }
            }, 30)
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            val logic = field("logicPanel").get(panel) as PipelineRowsPanel
            @Suppress("UNCHECKED_CAST")
            val rows = PipelineRowsPanel::class.java.getDeclaredField("rows").apply { isAccessible = true }
                .get(logic) as List<PipelineRow>
            val notes = rows.filter { it.key.endsWith(":override") }
                .flatMap { textComponents(it.createComponent()) }
            assertEquals("主流程只应保留一条覆盖摘要", 1, notes.size)
            assertTrue("摘要应展示实际覆盖值", notes.single().text.contains("post_delay = 0"))
            assertTrue(notes.single().text.contains("39"))
            assertFalse(notes.single().text.contains("选项39"))
            val details = field("detailsPanel").get(panel) as javax.swing.JPanel
            val labels = textComponents(details)
            val sources = labels.filter { it.text.startsWith("测试分类 = 选项") }
            assertEquals("所有未应用条件的源码入口仍应保留", 39, sources.size)
            val optionalSection = generateSequence(sources.first() as Component?) { it.parent }.first { it.parent === details }
            assertEquals("相同覆盖值应合并展示", 4, textComponents(optionalSection)
                .count { it.text.startsWith("post_delay：") })
            sources.forEach { label ->
                assertTrue("未应用配置应默认收起", generateSequence(label as Component?) { it.parent }
                    .takeWhile { it !== details }.any { !it.isVisible })
                assertTrue(label.mouseListeners.isNotEmpty())
            }
            field("currentTrace").set(panel, PipelineTraceParser.parse(log))
            PipelineFlowPanel::class.java.getDeclaredMethod("rebuildEffectiveContext").apply { isAccessible = true }.invoke(panel)
            PlatformTestUtil.waitWithEventsDispatching("日志覆盖没有完成刷新", {
                !field("contextLoading").getBoolean(panel) && textComponents(details)
                    .any { it.text.contains("template：") && it.text.contains("logged-template.png") }
            }, 30)
            @Suppress("UNCHECKED_CAST")
            val loggedRows = PipelineRowsPanel::class.java.getDeclaredField("rows").apply { isAccessible = true }
                .get(logic) as List<PipelineRow>
            val loggedNote = loggedRows.single { it.key.endsWith(":override") }.createComponent().let { row ->
                textComponents(row).single()
            }
            assertTrue("日志摘要不能只剩字段名：${loggedNote.text}",
                loggedNote.text.contains("template =") && loggedNote.text.contains("logged-template.png"))
            assertTrue(loggedNote.text.contains("40"))
            val loggedLabels = textComponents(details)
            assertEquals("加载日志不能丢失任务的可选配置", 40, loggedLabels.count { it.text.startsWith("测试分类 = 选项") })
            assertTrue("日志来源应仍可定位", loggedLabels.any { it.text.startsWith("来源：") && it.text.contains("override.log") })
            assertTrue(loggedLabels.any { it.text.contains("task_id=9") })
            val fullRecognition = loggedLabels.filterIsInstance<PipelineWrappingLabel>()
                .single { it.text.startsWith("recognition：") }
            assertTrue("详情必须显示未经摘要截断的覆盖值", fullRecognition.text.contains("complete-value-marker"))
            assertFalse(fullRecognition.isEditable)
            fullRecognition.select(0, "recognition".length)
            assertEquals("recognition", fullRecognition.selectedText)
            val sourceLabel = loggedLabels.single { it.text.startsWith("来源：") && it.text.contains("override.log") }
            sourceLabel.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(sourceLabel,
                java.awt.event.MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1)) }
            assertTrue("覆盖来源应打开普通 IDE 文件标签", manager.isFileOpen(log))
            assertTrue(sourceLabel is PipelineWrappingLabel)
            val tabs = field("rightTabs").get(panel) as JTabbedPane
            assertEquals("打开来源文件不应切换内嵌 JSON 页", 0, tabs.selectedIndex)
            tabs.selectedIndex = 1
            val click = java.awt.event.MouseEvent(loggedNote, java.awt.event.MouseEvent.MOUSE_CLICKED,
                0L, 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1)
            loggedNote.mouseListeners.forEach { it.mouseClicked(click) }
            assertEquals("摘要点击应切回详情，即使该节点已被选中", 0, tabs.selectedIndex)
            assertNotNull(field("overrideDetailsHeader").get(panel))
        } finally { manager.closeFile(workspace.file) }
    }

    fun testPlaybackRevealsDynamicAnchorPathBeyondBudgetWithoutExpandingUnrelatedBranches() {
        val file = fixture(maaEnd = false)
        val fillers = (0..204).map { "Filler$it" }
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"next":[${fillers.joinToString(",") { "\"$it\"" }},"Router"]},
             ${fillers.joinToString(",") { "\"$it\":{}" }},
             "Router":{"action":"Custom","custom_action":"Opaque","next":["[Anchor]Resume"]},
             "Setup":{"anchor":{"Resume":"Target"}},"OtherSetup":{"anchor":{"Resume":"Alternative"}},
             "Target":{"next":["Tail"]},"Tail":{"next":["Start"]},"Alternative":{"next":["Unrelated"]},"Unrelated":{}}
        """.trimIndent())
        replaceFixtureFile("assets/tasks/Tasks.json", """{"task":[{"name":"Transfer","entry":"Start"}]}""")
        val log = myFixture.addFileToProject("debug/replay.log", """
            [t0] [msg=Tasker.Task.Starting] [details={"entry":"Start","task_id":1}]
            [t1] [msg=Node.Action.Starting] [details={"name":"Start","task_id":1}]
            [t2] [msg=Node.Action.Starting] [details={"name":"Target","task_id":1,"anchor":"Resume"}]
            [t3] [msg=Node.Action.Starting] [details={"name":"Tail","task_id":1}]
        """.trimIndent()).virtualFile
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = model.pipelineEntries.single().stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            fun field(name: String) = PipelineFlowPanel::class.java.getDeclaredField(name).apply { isAccessible = true }
            PlatformTestUtil.waitWithEventsDispatching("流程尚未加载", {
                (field("currentModel").get(panel) as PipelineDocumentModel).projectNodes.size == model.projectNodes.size &&
                    !(field("contextLoading").getBoolean(panel))
            }, 30)
            field("currentTrace").set(panel, PipelineTraceParser.parse(log))
            PipelineFlowPanel::class.java.getDeclaredMethod("rebuildEffectiveContext").apply { isAccessible = true }.invoke(panel)
            PlatformTestUtil.waitWithEventsDispatching("日志上下文尚未加载", { !field("contextLoading").getBoolean(panel) }, 30)
            val controls = field("playbackToolbar").get(panel) as PipelinePlaybackToolbar
            val logic = field("logicPanel").get(panel) as PipelineRowsPanel
            val rowsField = PipelineRowsPanel::class.java.getDeclaredField("rows").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val rowByNode = field("logicRowByNode").get(panel) as Map<String, String>
            val targetKey = "${file.url}#Target"
            val tailKey = "${file.url}#Tail"
            assertFalse(rowByNode.containsKey(targetKey))
            val initialRows = rowsField.get(logic)
            val follow = PipelinePlaybackToolbar::class.java.getDeclaredField("follow").apply { isAccessible = true }
            follow.setBoolean(controls, false)
            controls.seek(2)
            assertEquals("Target", (field("inspectedLogicNode").get(panel) as PipelineNodeLocation).name)
            assertSame(initialRows, rowsField.get(logic))
            assertFalse(rowByNode.containsKey(targetKey))
            follow.setBoolean(controls, true)
            controls.seek(1)
            controls.seek(2)
            assertNotNull("跟随应展开预算边界及动态 Anchor 候选", rowByNode[targetKey])
            assertEquals(rowByNode[targetKey], PipelineRowsPanel::class.java.getDeclaredField("playbackRowKey")
                .apply { isAccessible = true }.get(logic))
            controls.seek(3)
            assertNotNull("Anchor 目标之后的流程也应能定位", rowByNode[tailKey])
            assertEquals(200, field("nodeBudget").getInt(panel))
            assertFalse(rowByNode.containsKey("${file.url}#Alternative"))
            assertFalse(rowByNode.containsKey("${file.url}#Filler204"))
            val revealedRows = rowsField.get(logic)
            controls.seek(2)
            controls.seek(3)
            assertSame("已展开路径上的步进不应重建流程", revealedRows, rowsField.get(logic))
        } finally { manager.closeFile(workspace.file) }
    }

    fun testDetailsRemainMeasurableWhenDynamicChildInvalidatesItsParentDuringMeasurement() {
        val file = fixture()
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = model.pipelineEntries.single { it.taskId == "Transfer" }.stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            val flow = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            val details = PipelineFlowPanel::class.java.getDeclaredField("detailsPanel").apply { isAccessible = true }
                .get(flow) as javax.swing.JPanel
            PlatformTestUtil.waitWithEventsDispatching("节点详情未加载", {
                textComponents(details).any { it.text == "Start" }
            }, 30)
            val content = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                add(PipelineWrappingLabel("动态参考图与折叠内容的尺寸变化".repeat(8)))
            }
            val dynamic = object : javax.swing.JPanel(java.awt.BorderLayout()) {
                override fun getPreferredSize(): java.awt.Dimension {
                    parent?.invalidate()
                    return super.getPreferredSize()
                }
            }.apply { add(content) }
            details.add(dynamic)
            val scroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, details) as javax.swing.JScrollPane
            repeat(12) { step ->
                scroll.setSize(300 + step % 3 * 100, 900)
                content.isVisible = step % 2 == 0
                details.preferredSize
                scroll.doLayout()
                details.doLayout()
                assertTrue(details.preferredSize.height > 0)
                assertTrue(dynamic.height < 900)
            }
        } finally { manager.closeFile(workspace.file) }
    }

    fun testPlaybackUpdatesHistoryAndDetailsWithoutRebuildingFlowAndStopsOnClear() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/resource/pipeline/Main.json", """{"Start":{"next":["Child"]},"Child":{}}""")
        replaceFixtureFile("assets/tasks/Tasks.json", """{"task":[{"name":"Transfer","entry":"Start"}]}""")
        val log = myFixture.addFileToProject("debug/replay.log", """
            [t0] [msg=Tasker.Task.Starting] [details={"entry":"Start","task_id":1}]
            [t1] [msg=Node.PipelineNode.Starting] [details={"name":"Start","task_id":1}]
            [t2] [msg=Node.NextList.Starting] [details={"name":"Start","task_id":1,"list":["Child"]}]
            [t3] [msg=Node.Recognition.Succeeded] [details={"name":"Child","task_id":1}]
            [t4] [msg=Node.PipelineNode.Starting] [details={"name":"Child","task_id":1}]
            [t5] [msg=Tasker.Task.Succeeded] [details={"entry":"Start","task_id":1}]
        """.trimIndent()).virtualFile
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = model.pipelineEntries.single().stableId
        workspace.data.lastSourceUrl = file.url
        val before = ReadAction.compute<String, RuntimeException> { PipelineProjectFiles.text(file) }
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            fun field(name: String) = PipelineFlowPanel::class.java.getDeclaredField(name).apply { isAccessible = true }
            PlatformTestUtil.waitWithEventsDispatching("任务尚未加载", {
                (field("currentModel").get(panel) as PipelineDocumentModel).projectNodes.size == 2
            }, 30)
            field("currentTrace").set(panel, PipelineTraceParser.parse(log))
            PipelineFlowPanel::class.java.getDeclaredMethod("rebuildEffectiveContext").apply { isAccessible = true }.invoke(panel)
            val controls = field("playbackToolbar").get(panel) as PipelinePlaybackToolbar
            assertTrue(controls.isVisible)
            val cursor = controls.session!!
            val source = cursor.source as PipelineLogPlaybackSource
            val logic = field("logicPanel").get(panel) as PipelineRowsPanel
            val rowsField = PipelineRowsPanel::class.java.getDeclaredField("rows").apply { isAccessible = true }
            val rows = rowsField.get(logic)
            controls.seek(2)
            assertEquals("Child", (field("inspectedLogicNode").get(panel) as PipelineNodeLocation).name)
            assertNotNull(source.nodeState("Child", cursor.eventIndex))
            controls.seek(1)
            assertEquals("Start", (field("inspectedLogicNode").get(panel) as PipelineNodeLocation).name)
            assertNull(source.nodeState("Child", cursor.eventIndex))
            assertSame(rows, rowsField.get(logic))
            PipelinePlaybackToolbar::class.java.getDeclaredField("follow").apply { isAccessible = true }.setBoolean(controls, false)
            controls.seek(2)
            assertEquals("Child", (field("inspectedLogicNode").get(panel) as PipelineNodeLocation).name)
            assertSame(rows, rowsField.get(logic))
            PipelinePlaybackToolbar::class.java.getDeclaredField("follow").apply { isAccessible = true }.setBoolean(controls, true)
            controls.seek(1)
            @Suppress("UNCHECKED_CAST")
            val collapsed = field("collapsedLogicNodes").get(panel) as MutableSet<String>
            collapsed.add("${file.url}#Start")
            val renderFlow = PipelineFlowPanel::class.java.getDeclaredMethod("renderLogicView", PipelineNodeLocation::class.java)
                .apply { isAccessible = true }
            renderFlow.invoke(panel, model.projectNodes.single { it.name == "Start" })
            controls.seek(2)
            assertFalse(collapsed.contains("${file.url}#Start"))
            assertEquals("Child", (field("inspectedLogicNode").get(panel) as PipelineNodeLocation).name)
            val timer = PipelinePlaybackToolbar::class.java.getDeclaredField("timer").apply { isAccessible = true }
                .get(controls) as javax.swing.Timer
            val slider = PipelinePlaybackToolbar::class.java.getDeclaredField("slider").apply { isAccessible = true }
                .get(controls) as javax.swing.JSlider
            val positionLabel = PipelinePlaybackToolbar::class.java.getDeclaredField("positionLabel").apply { isAccessible = true }
                .get(controls) as javax.swing.JLabel
            timer.start()
            slider.valueIsAdjusting = true
            slider.value = 1
            assertTrue("拖动预览应同步显示目标步骤", positionLabel.text.contains("2 / ${cursor.size}"))
            controls.refresh()
            assertFalse("拖动进度时应暂停播放", timer.isRunning)
            assertEquals("刷新不能把拖动位置改回旧游标", 1, slider.value)
            assertTrue("刷新不能把步骤数字改回旧游标", positionLabel.text.contains("2 / ${cursor.size}"))
            assertEquals("拖动期间不应反复重建当前位置", 2, cursor.position)
            slider.valueIsAdjusting = false
            assertEquals("松开滑块应跳转到拖动位置", 1, cursor.position)
            assertEquals("Start", (field("inspectedLogicNode").get(panel) as PipelineNodeLocation).name)
            slider.value = 2
            assertEquals("直接调整进度也应生效", 2, cursor.position)
            slider.valueIsAdjusting = true
            slider.value = 1
            slider.valueIsAdjusting = false
            assertEquals("进度条应支持向后定位", 1, cursor.position)
            timer.start()
            (field("clearTraceButton").get(panel) as javax.swing.JButton).doClick()
            assertFalse(timer.isRunning)
            assertFalse(controls.isVisible)
            assertNull(controls.session)
            assertEquals(before, ReadAction.compute<String, RuntimeException> { PipelineProjectFiles.text(file) })
        } finally { manager.closeFile(workspace.file) }
    }

    fun testGenericProjectsKeepStandardOverridesButDoNotInterpretCustomSubTaskNames() {
        val file = fixture(maaEnd = false)
        val generic = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        assertFalse(generic.supportsMaaEndSubTasks)
        assertEmpty(generic.projectNodes.single { it.name == "Caller" }.embeddedReferences)
        val entry = generic.pipelineEntries.single { it.taskId == "Transfer" }
        val patches = PipelineTaskContext.activeOverrides(entry, generic.pipelineOverrides, mapOf("Return" to listOf("Yes")))
        val effective = ReadAction.compute<Map<String, PipelineNodeLocation>, RuntimeException> {
            PipelineDocumentIndexer.effectiveNodes(project, generic, patches, emptyMap(), entry.taskOptions)
        }
        assertEquals(listOf("Done"), effective.getValue("${file.url}#Start").next.map { it.targetName })
        replaceFixtureFile("assets/interface.json", """
            {"name":"MaaEnd","github":"https://github.com/MaaEnd/MaaEnd","resource":[{"path":["./resource"]}],"import":["tasks/Tasks.json"]}
        """.trimIndent())
        val specialized = ReadAction.compute<PipelineDocumentModel, RuntimeException> {
            PipelineDocumentIndexer.index(project, file, generic)
        }
        assertTrue(specialized.supportsMaaEndSubTasks)
        assertEquals("Child", specialized.projectNodes.single { it.name == "Caller" }
            .embeddedReferences.single { it.kind == PipelineReferenceKind.SUBTASK }.targetName)
    }

    fun testLogOverridesReplaceTaskDefaultsReadOnlyAndClearingRestoresDefaults() {
        val file = fixture(maaEnd = false)
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"next":["Gate"]},"Gate":{"enabled":false,"next":["Done"]},"Done":{}}
        """.trimIndent())
        val remote = myFixture.addFileToProject("assets/resource/pipeline/Other.json", """{"Remote":{}}""").virtualFile
        replaceFixtureFile("assets/tasks/Tasks.json", """
            {"task":[{"name":"Transfer","entry":"Start","option":["Enable"]}],
             "option":{"Enable":{"default_case":"Yes","cases":[{"name":"No"},
               {"name":"Yes","pipeline_override":{"Gate":{"enabled":true}}}]}}}
        """.trimIndent())
        val log = myFixture.addFileToProject("debug/maafw.log", """
            [t][Px1][Tx1][MaaNS::Tasker::post_task] [entry=Start] [pipeline_override=[{"Gate":{"enabled":true}},{"Gate":{"enabled":false,"next":["Remote"]}}]]
            [t][Px1][Tx1][MaaNS::TaskNS::Context::create] [id=9]
            [t][Px1][Tx2] [msg=Tasker.Task.Starting] [details={"entry":"Start","task_id":9}]
        """.trimIndent()).virtualFile
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val entry = model.pipelineEntries.single()
        val before = ReadAction.compute<String, RuntimeException> { PipelineProjectFiles.text(file) }
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = entry.stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            val effectiveField = PipelineFlowPanel::class.java.getDeclaredField("effectiveNodes").apply { isAccessible = true }
            fun gate(): PipelineNodeLocation? {
                @Suppress("UNCHECKED_CAST")
                return (effectiveField.get(panel) as Map<String, PipelineNodeLocation>)["${file.url}#Gate"]
            }
            PlatformTestUtil.waitWithEventsDispatching("未应用任务默认选项", { gate()?.enabled == true }, 30)
            val traceField = PipelineFlowPanel::class.java.getDeclaredField("currentTrace").apply { isAccessible = true }
            val rebuild = PipelineFlowPanel::class.java.getDeclaredMethod("rebuildEffectiveContext").apply { isAccessible = true }
            val trace = PipelineTraceParser.parse(log)
            traceField.set(panel, trace)
            rebuild.invoke(panel)
            PlatformTestUtil.waitWithEventsDispatching("未使用日志覆盖和跨文件目标", {
                gate()?.let { !it.enabled && it.next.singleOrNull()?.target?.file == remote } == true
            }, 30)
            assertTrue(gate()!!.nextField!!.isDerived)
            assertEquals(log, gate()!!.nextField!!.sourceFile)
            val clear = PipelineFlowPanel::class.java.getDeclaredField("clearTraceButton").apply { isAccessible = true }
                .get(panel) as javax.swing.JButton
            clear.doClick()
            PlatformTestUtil.waitWithEventsDispatching("清除日志后未恢复任务默认值", { gate()?.enabled == true }, 30)
            assertEquals(before, ReadAction.compute<String, RuntimeException> { PipelineProjectFiles.text(file) })
        } finally { manager.closeFile(workspace.file) }
    }

    fun testSubTasksHaveIndependentBodiesAndDisabledNodesFoldWithoutLosingConditionalOverrides() {
        val file = fixture()
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{"next":["Disabled","Shared","Caller","OtherCall"]},
             "Disabled":{"enabled":false,"desc":"DISABLED_BODY","next":["Done"]},
             "Shared":{"next":["Done"]},"Done":{},
             "Caller":{"action":{"type":"Custom","custom_action":"SubTask","custom_action_param":{"sub":["Child"]}}},
             "OtherCall":{"action":{"type":"Custom","param":{"custom_action":"SubTask","custom_action_param":{"sub":["Child"]}}}},
             "Child":{"desc":"CHILD_BODY","action":"Click","target":[1,2,3,4],"rate_limit":123,"timeout":345,"next":["Shared"]}}
        """.trimIndent())
        replaceFixtureFile("assets/tasks/Tasks.json", """
            {"task":[{"name":"Transfer","entry":"Start","option":["Enable"]}],
             "option":{"Enable":{"label":"启用检查","default_case":"No","cases":[{"name":"No"},
               {"name":"Yes","pipeline_override":{"Disabled":{"enabled":true}}}]}}}
        """.trimIndent())
        myFixture.addFileToProject("assets/resource/default_pipeline.json", """{"Default":{"rate_limit":0}}""")
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        assertEquals(2, model.projectNodes.filter { it.name in setOf("Caller", "OtherCall") }
            .flatMap { it.embeddedReferences }.count { it.kind == PipelineReferenceKind.SUBTASK && it.target?.name == "Child" })
        val entry = model.pipelineEntries.single()
        assertEmpty(PipelineTaskContext.activeOverrides(entry, model.pipelineOverrides, emptyMap()))
        assertEquals(1, PipelineTaskContext.possibleOverrides(entry, model.pipelineOverrides).size)
        val disabled = model.projectNodes.single { it.name == "Disabled" }
        assertEquals("0", PipelinePropertyCatalog.policyValue(disabled, PipelinePropertyCatalog.resourceDefaults(disabled, model), "rate_limit"))
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = entry.stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            val logic = descendants(host.component).filterIsInstance<PipelineRowsPanel>().single()
            val rowsField = PipelineRowsPanel::class.java.getDeclaredField("rows").apply { isAccessible = true }
            fun rows(): List<PipelineRow> {
                @Suppress("UNCHECKED_CAST")
                return rowsField.get(logic) as List<PipelineRow>
            }
            fun labels(): List<Component> = rows().flatMap { textComponents(it.createComponent()) }
            PlatformTestUtil.waitWithEventsDispatching("子任务流程未展开", { labels().count { it.text.contains("CHILD_BODY") } == 2 }, 30)
            assertFalse(labels().any { it.text.contains("DISABLED_BODY") })
            assertTrue(labels().any { it.text.contains("可由任务选项覆盖") })
            assertFalse(labels().any { it.text.contains("SubTask ·") || it.text.contains("call →") || it.text.contains("rate_limit") })
            val subtaskRow = rows().first { row -> textComponents(row.createComponent()).any { it.text.contains("subtask → Child") } }
            val subtaskIndex = rows().indexOf(subtaskRow)
            assertTrue(textComponents(rows()[subtaskIndex + 1].createComponent()).any { it.text == "CHILD_BODY" })
            val action = labels().filterIsInstance<PipelineStyledText>().first { it.text.startsWith("执行动作 Click") }
            assertFalse("只强调关键字，不能整行加粗", action.font.isBold)
            assertTrue(action.toolTipText.contains("target"))
            assertTrue(action.toolTipText.contains("识别结果"))
            val policy = labels().filterIsInstance<PipelineWrappingLabel>().first { it.text == "timeout=345" }
            assertTrue(policy.toolTipText.contains("on_error"))
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            PipelineFlowPanel::class.java.getDeclaredMethod("inspectNode", PipelineNodeLocation::class.java,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(panel, model.projectNodes.single { it.name == "Child" }, false)
            val details = PipelineFlowPanel::class.java.getDeclaredField("detailsPanel").apply { isAccessible = true }
                .get(panel) as javax.swing.JPanel
            val detailLabels = textComponents(details)
            assertEquals("Child", detailLabels[0].text)
            assertEquals("CHILD_BODY", detailLabels[1].text)
            assertTrue(detailLabels.any { it.text.contains("rate_limit：123") })
            val header = subtaskRow.createComponent()
            val marker = descendants(header).filterIsInstance<javax.swing.JComponent>().single { it.toolTipText == "收起Child" }
            fun toggle() {
                val event = java.awt.event.MouseEvent(marker, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0,
                    2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1)
                marker.mouseListeners.forEach { it.mouseClicked(event) }
            }
            toggle()
            assertEquals(1, labels().count { it.text == "CHILD_BODY" })
            toggle()
            assertEquals(2, labels().count { it.text == "CHILD_BODY" })
        } finally { manager.closeFile(workspace.file) }
    }

    fun testCrossFileDetailsStayEditableAndJsonDeclarationsUseNormalFileTabs() {
        val seed = fixture()
        val remote = myFixture.addFileToProject("assets/resource/pipeline/Remote.json", """
            {"Remote":{"recognition":"And","all_of":[{"recognition":"OCR","expected":"确认"}],
            "action":"Click","anchor":{"Resume":"Done"},"next":["Done","Caller"]}}
        """.trimIndent()).virtualFile
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastSourceUrl = seed.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            PlatformTestUtil.waitWithEventsDispatching("工作区尚未加载", {
                descendants(host.component).filterIsInstance<JComboBox<*>>().any { it.itemCount > 0 }
            }, 30)
            val panel = descendants(host.component).filterIsInstance<PipelineFlowPanel>().single()
            val catalog = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, seed) }
            val target = catalog.projectNodes.single { it.name == "Remote" }
            val inspect = PipelineFlowPanel::class.java.getDeclaredMethod("inspectNode", PipelineNodeLocation::class.java,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            inspect.invoke(panel, target, false)
            val components = descendants(panel)
            val captions = components.filterIsInstance<javax.swing.JLabel>().filter { it.text in setOf("条件：", "动作：") }
            assertEquals(2, captions.size)
            captions.forEach { caption ->
                assertEquals(javax.swing.SwingConstants.CENTER, caption.verticalAlignment)
                val row = caption.parent as javax.swing.JPanel
                row.setSize(500, row.preferredSize.height)
                row.doLayout()
                val value = (row.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.CENTER) as Component
                assertEquals(caption.y + caption.getBaseline(caption.width, caption.height), value.y + value.getBaseline(value.width, value.height))
                assertTrue(row.components.filterIsInstance<javax.swing.JButton>().single().isEnabled)
            }
            assertFalse(components.filterIsInstance<javax.swing.JComponent>().any { it.toolTipText?.contains("来自其它文件") == true })
            val source = components.filterIsInstance<PipelineWorkspaceSource>().single()
            val seedDocument = source.editorFor(seed).editor.document
            val seedText = seedDocument.text
            val remoteEditor = source.editorFor(remote)
            val remoteDocument = remoteEditor.editor.document
            val remoteText = remoteDocument.text
            val marker = components.filterIsInstance<javax.swing.JToggleButton>().first { it.text == "回" }
            assertTrue(marker.isEnabled)
            val previous = com.intellij.openapi.ui.TestDialogManager.setTestDialog { com.intellij.openapi.ui.Messages.YES }
            try { marker.doClick() } finally { com.intellij.openapi.ui.TestDialogManager.setTestDialog(previous) }
            assertTrue(remoteDocument.text.contains("[JumpBack]Done"))
            assertEquals(seedText, seedDocument.text)
            assertTrue(UndoManager.getInstance(project).isUndoAvailable(remoteEditor))
            UndoManager.getInstance(project).undo(remoteEditor)
            assertEquals(remoteText, remoteDocument.text)
            val embeddedBefore = source.currentFile
            val psi = ReadAction.compute<com.intellij.psi.PsiFile?, RuntimeException> {
                com.intellij.psi.PsiManager.getInstance(project).findFile(remote)
            }!!
            PipelineSourceTarget(psi, target.nameRange.startOffset, "Remote").navigate(true)
            assertEquals(embeddedBefore, source.currentFile)
            assertTrue(manager.isFileOpen(remote))
            assertEquals(target.nameRange.startOffset, (manager.selectedEditor as TextEditor).editor.caretModel.offset)
            val existing = manager.selectedEditor
            manager.openFile(workspace.file, true)
            PipelineSourceTarget(psi, target.nameRange.startOffset, "Remote").navigate(true)
            assertSame(existing, manager.selectedEditor)
            assertEquals(1, manager.openFiles.count { it == remote })
        } finally {
            manager.closeFile(remote)
            manager.closeFile(workspace.file)
        }
    }

    fun testReferenceImageKeepsOriginalCommentAndUsesOverriddenConditions() {
        val file = fixture()
        replaceFixtureFile("assets/resource/pipeline/Main.json", """
            {"Start":{
              // @preview reference.png
              // @roi target [10,20,30,40]
              "recognition":"OCR","expected":"原始条件","roi":[1,2,3,4],"action":"Click","next":["Done"]
            },"Done":{}}
        """.trimIndent())
        replaceFixtureFile("assets/tasks/Tasks.json", """
            {"task":[{"name":"Transfer","entry":"Start","pipeline_override":{
              "Start":{"recognition":{"type":"OCR","param":{"expected":"覆盖条件","roi":[5,6,7,8]}}}
            }}]}
        """.trimIndent())
        val model = ReadAction.compute<PipelineDocumentModel, RuntimeException> { PipelineDocumentIndexer.index(project, file) }
        val effective = ReadAction.compute<Map<String, PipelineNodeLocation>, RuntimeException> {
            PipelineDocumentIndexer.effectiveNodes(project, model, model.pipelineOverrides, emptyMap())
        }.values.single()
        val target = PipelineVisualModel.region(PipelineVisualModel.operation(effective, true))
        assertTrue(target.fromComment)
        assertEquals(listOf(10,20,30,40), target.coordinates)
        val workspace = PipelineWorkspaceState.getInstance(project)
        workspace.loadState(PipelineWorkspaceStorage())
        workspace.data.lastContext = model.pipelineEntries.single().stableId
        workspace.data.lastSourceUrl = file.url
        val manager = FileEditorManager.getInstance(project)
        workspace.open()
        try {
            val host = manager.getEditors(workspace.file).filterIsInstance<PipelineFileEditor>().single()
            try {
                PlatformTestUtil.waitWithEventsDispatching("任务覆盖后的详情没有加载", {
                    textComponents(host.component).any { it.text.contains("覆盖条件") }
                }, 30)
            } catch (failure: AssertionError) {
                throw AssertionError(textComponents(host.component).map { it.text }.joinToString("\n"), failure)
            }
            val labels = textComponents(host.component)
            assertTrue(labels.any { it.text.contains("参考图 · reference.png") })
            assertTrue(labels.any { it.text.contains("[5, 6, 7, 8]") })
        } finally { manager.closeFile(workspace.file) }
    }

    private fun fixture(maaEnd: Boolean = true) = myFixture.addFileToProject("assets/resource/pipeline/Main.json", """
        {"Start":{"recognition":"DirectHit","next":["Caller","Done"]},
         "Caller":{"action":"Custom","custom_action":"SubTask","custom_action_param":{"sub":["Child"]}},
         "Child":{"next":["Done"]},"Done":{"recognition":"DirectHit"}}
    """.trimIndent()).virtualFile.also {
        myFixture.addFileToProject("assets/interface.json", """
            {${if (maaEnd) "\"name\":\"MaaEnd\",\"github\":\"https://github.com/MaaEnd/MaaEnd\"," else ""}
             "resource":[{"path":["./resource"]}],"import":["tasks/Tasks.json"],
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
        val selector = descendants(host.component).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-node-selector" }
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
        descendants(host.component).filterIsInstance<JTabbedPane>().single { it.name == "pipeline-detail-tabs" }.selectedIndex = 1
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
        val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single { it.name == "pipeline-detail-tabs" }
        assertEquals(listOf("节点详情", "原始 JSON"), (0 until tabs.tabCount).map(tabs::getTitleAt))
        assertEquals(1, tabs.selectedIndex)
        val source = descendants(panel).filterIsInstance<PipelineWorkspaceSource>().single()
        val embeddedEditor = source.editorFor(file).editor
        assertEquals(3, embeddedEditor.caretModel.offset)
        val showBulb = embeddedEditor.settings.isShowIntentionBulb
        tabs.selectedIndex = 0
        assertFalse(embeddedEditor.settings.isShowIntentionBulb)
        tabs.selectedIndex = 1
        assertEquals(showBulb, embeddedEditor.settings.isShowIntentionBulb)
        host.deselectNotify()
        assertFalse(embeddedEditor.settings.isShowIntentionBulb)
        host.selectNotify()
        assertEquals(showBulb, embeddedEditor.settings.isShowIntentionBulb)
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
            descendants(host.component).any { it is PipelineRowsPanel } && workspace.reading(entry.stableId).rootNode.endsWith("#Start")
        }, 30)
        val logic = descendants(host.component).filterIsInstance<PipelineRowsPanel>().single()
        val viewport = logic.parent as javax.swing.JViewport
        viewport.extentSize = java.awt.Dimension(1000, 4000)
        val attach = !logic.isDisplayable
        if (attach) logic.addNotify()
        try {
            fun labels() = textComponents(logic)
            PlatformTestUtil.waitWithEventsDispatching("可能目标按钮未加载", {
                descendants(logic).filterIsInstance<javax.swing.JButton>().any { it.text.contains("个可能目标") }
            }, 30)
            val tabs = descendants(host.component).filterIsInstance<JTabbedPane>().single { it.name == "pipeline-detail-tabs" }
            val source = descendants(host.component).filterIsInstance<PipelineWorkspaceSource>().single()
            tabs.selectedIndex = 1
            val openBefore = manager.openFiles.toSet()
            descendants(logic).filterIsInstance<javax.swing.JButton>().single { it.text.contains("个可能目标") }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("候选未展开", {
                labels().any { it.text.contains("→ Target") }
            }, 30)
            val label = labels().single { it.text.contains("→ Target") }
            assertTrue(label.text.contains("可能目标（运行时绑定）"))
            val click = java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 3, 3, 1, false, java.awt.event.MouseEvent.BUTTON1)
            label.mouseListeners.forEach { it.mouseClicked(click) }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals(other, source.currentFile)
            assertEquals(target.nameRange.startOffset, source.editorFor(other).editor.caretModel.offset)
            assertEquals(2, source.editorFor(other).editor.caretModel.logicalPosition.line)
            assertEquals(openBefore, manager.openFiles.toSet())
            assertTrue(labels().none { it.text.contains("本轮没有成功后继") || it.text.startsWith("否则：") })
            assertEquals(labels().sumOf { it.text.count { character -> character == '{' } },
                labels().sumOf { it.text.count { character -> character == '}' } })
            assertTrue(logic.componentCount < 60)
            val cycle = labels().single { it.text.contains("· ↩ 循环") }
            val header = cycle.parent as javax.swing.JPanel
            val controls = (header.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.SOUTH)
            assertNull("左侧循环候选只负责阅读，不应保留编辑操作", controls)
            assertTrue(descendants(logic).filterIsInstance<javax.swing.JToggleButton>().none { it.text in setOf("回", "锚") })
            descendants(logic).filterIsInstance<javax.swing.JButton>().single { it.text == "↩ 回到此层" }.doClick()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("Start", (descendants(host.component).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-node-selector" }.selectedItem as PipelineNodeLocation).name)
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
        val selector = descendants(panel).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-node-selector" }
        PlatformTestUtil.waitWithEventsDispatching("流程未加载", {
            workspace.reading(entry.stableId).rootNode.endsWith("#Start") && selector.selectedItem != null
        }, 30)
        val logic = descendants(panel).filterIsInstance<PipelineRowsPanel>().single()
        val viewport = logic.parent as javax.swing.JViewport
        viewport.extentSize = java.awt.Dimension(900, 1000)
        val attach = !logic.isDisplayable
        if (attach) logic.addNotify()
        try {
            PlatformTestUtil.waitWithEventsDispatching("流程可见行未创建", { logic.componentCount > 5 }, 30)
            val originalRows = logic.components.toList()
            val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single { it.name == "pipeline-detail-tabs" }
            val originalDetails = textComponents(tabs.getComponentAt(0))
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
            assertEquals(originalDetails, textComponents(tabs.getComponentAt(0)))
            tabs.selectedIndex = 0
            assertTrue(textComponents(tabs.getComponentAt(0)).any {
                it.text.removePrefix("<html>").removeSuffix("</html>") == "Child"
            })
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
                textComponents(logic).any { it.text.contains("Start") }
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
                textComponents(host.component).any { it.text.startsWith("项目资源：") }
            }, 30)
            return host
        }
        assertNull(descendants(readyHost().component).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-node-selector" }.selectedItem)
        assertEquals("", workspace.data.lastContext)
        manager.closeFile(workspace.file)
        workspace.data.lastContext = "${file.url}#task:Removed"
        workspace.open()
        val restored = readyHost()
        assertNull(descendants(restored.component).filterIsInstance<JComboBox<*>>().single { it.name == "pipeline-node-selector" }.selectedItem)
        assertTrue(textComponents(restored.component).any { it.text.contains("上次任务或流程已不存在") })
        manager.closeFile(workspace.file)
    }

}
