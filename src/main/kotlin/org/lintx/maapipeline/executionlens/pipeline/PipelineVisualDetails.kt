package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.HideableDecorator
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret
import javax.swing.text.View

internal fun pipelineActionForeground(): java.awt.Color = JBColor.lazy {
    EditorColorsManager.getInstance().globalScheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD)?.foregroundColor
        ?: UIUtil.getLabelForeground()
}

internal fun pipelineDefaultProperties(content: JPanel, count: Int): JComponent =
    pipelineCollapsedSection("默认参数（$count）", content)

internal fun pipelineCollapsedSection(title: String, content: JPanel): JComponent = object : JPanel(BorderLayout()) {
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
}.apply {
    isOpaque = false
    alignmentX = JComponent.LEFT_ALIGNMENT
    HideableDecorator(this, title, false).apply {
        setContentComponent(content)
        setOn(false)
    }
}

internal class PipelineWrappingLabel(value: String) : JTextArea(value) {
    init {
        lineWrap = true
        wrapStyleWord = false
        isEditable = false
        isOpaque = false
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getLabelForeground()
        caretColor = foreground
        selectionColor = JBColor.namedColor("TextField.selectionBackground", UIUtil.getListSelectionBackground(false))
        selectedTextColor = JBColor.namedColor("TextField.selectionForeground", UIUtil.getLabelForeground())
        alignmentX = LEFT_ALIGNMENT
        margin = JBUI.insets(2, 4)
        // 数据刷新不能让只读文本的光标抢走流程滚动；用户的选择、复制仍由 Swing 处理。
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
    }

    private fun availableWidth(): Int {
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
        if (viewport == null) {
            return parent?.let { it.width - it.insets.left - it.insets.right }?.takeIf { it > 0 } ?: JBUI.scale(480)
        }
        var available = viewport.extentSize.width
        var child: Component = this
        var container: Container? = parent
        while (container != null && container !== viewport) {
            available -= container.insets.left + container.insets.right
            val layout = container.layout
            if (layout is BorderLayout && layout.getLayoutComponent(BorderLayout.CENTER) === child) {
                listOf(BorderLayout.WEST, BorderLayout.EAST).forEach { side ->
                    layout.getLayoutComponent(side)?.takeIf { it.isVisible }?.let {
                        available -= it.preferredSize.width + layout.hgap
                    }
                }
            }
            child = container
            container = container.parent
        }
        return available.coerceAtLeast(1)
    }

    override fun getPreferredSize(): Dimension {
        val naturalWidth = text.orEmpty().lineSequence().maxOfOrNull { getFontMetrics(font).stringWidth(it) } ?: 0
        return preferredSizeForWidth(minOf(naturalWidth + insets.left + insets.right, availableWidth()))
    }

    fun preferredSizeForWidth(available: Int): Dimension {
        val contentWidth = (available - insets.left - insets.right).coerceAtLeast(1)
        val view = getUI().getRootView(this)
        view.setSize(contentWidth.toFloat(), Float.MAX_VALUE)
        val contentHeight = kotlin.math.ceil(view.getPreferredSpan(View.Y_AXIS).toDouble()).toInt()
        return Dimension(available.coerceAtLeast(0), contentHeight + insets.top + insets.bottom)
    }

    fun setContent(value: String) { text = value }

    override fun setToolTipText(text: String?) {
        super.setToolTipText(if (text == null || text.startsWith("<html>")) text else PipelineFlowTooltip.render(
            listOf(PipelineConditionDetail(text)), getFontMetrics(font ?: UIUtil.getLabelFont())))
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    companion object {
        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
    }
}

internal class PipelineVisualDetails(
    private val project: Project,
    private val source: VirtualFile,
    private val resolve: (String) -> PipelineNodeLocation?,
    private val open: (VirtualFile) -> Unit,
    private val blocked: (VirtualFile) -> Boolean = { false },
    private val defaults: (PipelineNodeLocation?) -> com.google.gson.JsonObject = { com.google.gson.JsonObject() },
) {
    fun operation(operation: PipelineOperation): JComponent = column().apply { appendOperation(this, operation, 0) }

    private fun appendOperation(container: JPanel, original: PipelineOperation, depth: Int) {
        val properties = PipelinePropertyCatalog.operationProperties(original, defaults(original.source))
        val defaultContent = column()
        val byName = properties.associateBy { it.name }
        val operation = original.copy(parameters = properties.mapNotNull { property ->
            property.value?.let { property.name to it }
        }.toMap())
        val title = operation.title + operation.reference?.takeIf { operation.type != "Reference" }?.let { " · $it" }.orEmpty()
        if (depth > 0) container.add(PipelineWrappingLabel(title).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            if (operation.isAction) foreground = pipelineActionForeground()
            toolTipText = if (operation.isAction) PipelineVisualModel.actionHelp(operation)
                else PipelineVisualModel.fieldHelp("recognition")
        })
        val mode = PipelineVisualModel.mode(operation)
        val regionKeys = if (operation.isAction) when (operation.type) {
            "Swipe" -> listOf("begin", "end")
            "Click", "LongPress", "TouchDown", "TouchMove", "Scroll", "Custom" -> listOf("target")
            else -> emptyList()
        } else if (operation.type !in setOf("And", "Or", "Reference")) listOf("roi") else emptyList()
        regionKeys.forEach { key ->
            val regionSource = if (key in original.roiHints && key !in original.parameters) original else operation
            val region = PipelineVisualModel.region(regionSource, key, resolve)
            val target = if (byName[key]?.isDefault == true && key !in original.roiHints) defaultContent else container
            target.add(PipelineWrappingLabel("$key：${region.summary}${byName[key]?.suffix.orEmpty()}").apply { toolTipText = region.explanation })
        }
        val excluded = regionKeys.toSet() + setOf("all_of", "any_of", "template", "lower", "upper")
        properties.filter { it.name !in excluded }.forEach { property ->
            val key = property.name
            val value = property.value
            val rendered = if (value != null && (value.isJsonObject || value.isJsonArray && value.asJsonArray.any { it.isJsonObject }))
                GsonBuilder().setPrettyPrinting().create().toJson(value) else value?.let(PipelineVisualModel::display) ?: "未设置"
            val target = if (property.isDefault) defaultContent else container
            target.add(PipelineWrappingLabel("$key：$rendered${property.suffix}").apply {
                toolTipText = if (key in setOf("method", "detector", "only_rec") && mode != null) mode.second else property.explanation
            })
        }
        listOf("all_of", "any_of").forEach { key ->
            byName[key]?.let { property -> container.add(PipelineWrappingLabel("$key：${operation.children.size} 个子条件${property.suffix}")) }
        }
        operation.parameters["template"]?.let { template ->
            val paths = if (template.isJsonArray) template.asJsonArray.toList() else listOf(template)
            paths.filter { it.isJsonPrimitive }.forEach { path ->
                addImage(container, path.asString, operation.source?.sourceFile ?: source, false)
            }
        }
        if ("template" in byName && !operation.parameters.containsKey("template")) {
            container.add(PipelineWrappingLabel("template：未设置"))
        }
        PipelineVisualModel.colors(operation)?.let { colors ->
            container.add(PipelineWrappingLabel("${colors.space} 颜色范围样本（各通道闭区间，并非匹配概率）").apply { toolTipText = colors.explanation })
            colors.ranges.forEachIndexed { index, range ->
                container.add(PipelineWrappingLabel("${index + 1}. ${range.lower} → ${range.upper}"))
                val single = colors.copy(ranges = listOf(range))
                val image = PipelineVisualAssets.colorImage(single)
                if (image == null || single.samples(range).isEmpty()) {
                    container.add(PipelineWrappingLabel(if (colors.supported) "无有效颜色：请检查通道数及上下限" else "此模式保留原始通道值，暂不转换预览颜色"))
                } else container.add(JBLabel(ImageIcon(image)).apply { alignmentX = JComponent.LEFT_ALIGNMENT; toolTipText = colors.explanation })
            }
            if (colors.ranges.isEmpty()) container.add(PipelineWrappingLabel("lower：${operation.text("lower") ?: "未设置"}\nupper：${operation.text("upper") ?: "未设置"}"))
        }
        if (defaultContent.componentCount > 0) container.add(pipelineDefaultProperties(defaultContent, defaultContent.componentCount))
        operation.children.forEach { child ->
            val body = column().apply { border = JBUI.Borders.empty(4, 12, 4, 0) }
            if (depth < 16) appendOperation(body, child, depth + 1)
            container.add(body)
        }
    }

    fun referenceImage(node: PipelineNodeLocation, recognition: PipelineOperation, action: PipelineOperation): JComponent? {
        val path = PipelineVisualModel.preview(node.rawJson) ?: return null
        return column().apply {
            add(PipelineWrappingLabel("参考图 · $path").apply {
                toolTipText = "// @preview 路径：参考图应与运行时截图使用相同尺寸。实线表示固定 ROI；虚线表示动态目标的来源搜索区，或 @roi 注释提供的示意范围。"
            })
            val operations = PipelineVisualAssets.leaves(recognition) + listOf(action).filter { it.type != "DoNothing" }
            addImage(this, path, node.sourceFile ?: source, true, operations)
        }
    }

    private fun addImage(container: JPanel, path: String, sourceFile: VirtualFile, preview: Boolean,
        operations: List<PipelineOperation> = emptyList()) {
        val row = column()
        val label = PipelineWrappingLabel(if (preview) "加载参考图…" else "模板：$path")
        row.add(label)
        container.add(row)
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val files = ReadAction.compute<List<VirtualFile>, RuntimeException> {
                if (project.isDisposed) emptyList() else if (preview) listOfNotNull(PipelineVisualAssets.resolve(project, sourceFile, path, true))
                else PipelineVisualAssets.templates(project, sourceFile, path)
            }
            val templatePreviews = if (preview) PipelineVisualAssets.previewTemplates(project, sourceFile, operations, resolve) else emptyMap()
            val decoded = files.map { file ->
                val original = PipelineVisualAssets.read(file)
                val image = original?.let {
                    if (preview) PipelineVisualAssets.overlay(it, operations, resolve, templatePreviews = templatePreviews) else it
                }
                LoadedImage(file, original?.takeIf { preview }, image?.takeIf { preview },
                    image?.let { PipelineVisualAssets.scaled(it, JBUI.scale(360), JBUI.scale(190)) },
                    original?.width ?: 0, original?.height ?: 0)
            }
            application.invokeLater({
                if (project.isDisposed || row.parent == null) return@invokeLater
                if (decoded.isEmpty()) {
                    label.setContent("图片未找到：$path")
                }
                decoded.forEach { (file, original, popup, thumbnail, width, height) ->
                    if (thumbnail == null) row.add(PipelineWrappingLabel("图片无法解码：${file.name}"))
                    else row.add(JBLabel(ImageIcon(thumbnail)).apply {
                        alignmentX = JComponent.LEFT_ALIGNMENT
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        toolTipText = if (preview) "点击查看带 ROI 标注的大图；$width × $height，标注使用参考图像素坐标" else "${file.path} · 点击打开原图"
                        addMouseListener(object : MouseAdapter() {
                            override fun mouseClicked(event: MouseEvent) {
                                if (event.button != MouseEvent.BUTTON1) return
                                if (popup != null && original != null) PipelineImagePreview.show(project, file, original, operations, resolve, templatePreviews, blocked)
                                else open(file)
                            }
                        })
                    })
                }
                if (decoded.size == 64) row.add(PipelineWrappingLabel("文件夹预览最多显示 64 张图片；打开目录可查看全部。"))
                if (decoded.any { it.thumbnail != null }) label.setContent(if (preview) "点击缩略图查看大图与单个区域" else "模板：$path")
                row.revalidate()
                container.parent?.revalidate()
                container.repaint()
            }, ModalityState.any())
        }
    }

    private data class LoadedImage(val file: VirtualFile, val original: BufferedImage?, val popup: BufferedImage?,
        val thumbnail: BufferedImage?, val width: Int, val height: Int)

    internal fun previewContent(original: BufferedImage, image: BufferedImage, operations: List<PipelineOperation>,
        templatePreviews: Map<Int, List<PipelineVisualAssets.TemplatePreview>> = emptyMap()): PipelineImagePreview =
        PipelineImagePreview(project, original, operations, resolve, templatePreviews)

    private fun column(): JPanel = object : JPanel() {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        layout = VerticalLayout(0)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }
}
