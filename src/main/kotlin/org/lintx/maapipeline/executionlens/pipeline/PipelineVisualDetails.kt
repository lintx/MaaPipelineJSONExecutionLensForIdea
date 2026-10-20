package org.lintx.maapipeline.executionlens.pipeline

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class PipelineWrappingLabel(value: String) : JBLabel(value) {
    private var renderedWidth = -1
    private var renderedFont: java.awt.Font? = null
    private var renderedText: String? = null
    private var renderedLines: List<String> = emptyList()
    init {
        verticalAlignment = SwingConstants.TOP
        alignmentX = LEFT_ALIGNMENT
    }

    private fun lines(available: Int): List<String> {
        if (renderedWidth != available || renderedFont != font || renderedText != text) {
            renderedWidth = available
            renderedFont = font
            renderedText = text
            renderedLines = PipelineVisualAssets.wrapOverlayText(listOf(text.orEmpty()), getFontMetrics(font), available)
        }
        return renderedLines
    }

    override fun getPreferredSize(): Dimension {
        val parentWidth = ((parent?.width ?: 0) - (parent?.insets?.let { it.left + it.right } ?: 0))
            .takeIf { it > 0 } ?: JBUI.scale(480)
        val available = minOf(width.takeIf { it > 0 } ?: parentWidth, parentWidth)
        return preferredSizeForWidth(available)
    }

    fun preferredSizeForWidth(available: Int): Dimension {
        val contentWidth = (available - insets.left - insets.right).coerceAtLeast(1)
        // 尺寸查询不能改动标签文本，否则会在 BoxLayout 计算中途清空其布局缓存。
        return Dimension(available, lines(contentWidth).size * getFontMetrics(font).height + insets.top + insets.bottom)
    }

    override fun paintComponent(graphics: java.awt.Graphics) {
        val copy = graphics.create() as java.awt.Graphics2D
        try {
            if (isOpaque) { copy.color = background; copy.fillRect(0, 0, width, height) }
            copy.font = font
            copy.color = if (isEnabled) foreground else UIUtil.getLabelDisabledForeground()
            copy.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val metrics = copy.fontMetrics
            lines((width - insets.left - insets.right).coerceAtLeast(1)).forEachIndexed { index, line ->
                copy.drawString(line, insets.left, insets.top + metrics.ascent + index * metrics.height)
            }
        } finally { copy.dispose() }
    }

    override fun getBaseline(width: Int, height: Int): Int = insets.top + getFontMetrics(font).ascent

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
        val byName = properties.associateBy { it.name }
        val operation = original.copy(parameters = properties.mapNotNull { property ->
            property.value?.let { property.name to it }
        }.toMap())
        val title = operation.title + operation.reference?.takeIf { operation.type != "Reference" }?.let { " · $it" }.orEmpty()
        if (depth > 0) container.add(PipelineWrappingLabel(title).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            toolTipText = PipelineVisualModel.fieldHelp(if (operation.isAction) "action" else "recognition")
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
            container.add(PipelineWrappingLabel("$key：${region.summary}${byName[key]?.suffix.orEmpty()}").apply { toolTipText = region.explanation })
        }
        val excluded = regionKeys.toSet() + setOf("all_of", "any_of", "template", "lower", "upper")
        properties.filter { it.name !in excluded }.forEach { property ->
            val key = property.name
            val value = property.value
            val rendered = if (value != null && (value.isJsonObject || value.isJsonArray && value.asJsonArray.any { it.isJsonObject }))
                GsonBuilder().setPrettyPrinting().create().toJson(value) else value?.let(PipelineVisualModel::display) ?: "未设置"
            container.add(PipelineWrappingLabel("$key：$rendered${property.suffix}").apply {
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
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }
}
