package com.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.documentation.DocumentationProviderEx
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.awt.Image
import java.awt.image.BufferedImage
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

class PipelineJsonDocumentationProvider : DocumentationProviderEx() {
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
        previewElement(contextElement ?: file.findElementAt(targetOffset))

    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?): PsiElement? =
        previewElement(contextElement)

    internal fun previewElement(element: PsiElement?): PsiElement? {
        element ?: return null
        val context = PipelineJsonContext.at(element) ?: return null
        val comment = generateSequence(element) { it.parent }.filterIsInstance<PsiComment>().firstOrNull()
        if (comment != null && comment.text.contains("@preview") && PipelineVisualModel.preview(context.location.rawJson) != null) return comment
        return element.takeIf { context.field?.name in setOf("template", "lower", "upper", "method", "recognition", "action", "anchor", "target", "roi", "custom_action", "custom_recognition") }
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = generateDoc(element, originalElement)

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val origin = originalElement?.takeIf { PipelineJsonContext.at(it) != null } ?: element
        val context = PipelineJsonContext.at(origin) ?: return null
        val project = origin.project
        val node = context.location
        fun resolve(name: String): PipelineNodeLocation? {
            val candidate = PipelineNavigationIndex.values(project, "node:$name").singleOrNull()?.first ?: return null
            val psi = PsiManager.getInstance(project).findFile(candidate) as? com.intellij.json.psi.JsonFile ?: return null
            val property = (psi.topLevelValue as? JsonObject)?.findProperty(name) ?: return null
            return PipelineNodeLocation(name, property.nameElement.textRange, property.textRange,
                sourceFile = candidate, rawJson = property.value?.text ?: "{}")
        }
        val comment = generateSequence(origin) { it.parent }.filterIsInstance<PsiComment>().firstOrNull()
        val key = context.field?.name
        if (comment != null && comment.text.contains("@preview")) {
            val path = PipelineVisualModel.preview(node.rawJson) ?: return null
            val file = PipelineVisualAssets.resolve(project, context.file.virtualFile, path, true)
            val image = file?.let(PipelineVisualAssets::read)
            val recognition = PipelineVisualModel.operation(node, resolve = ::resolve)
            val action = PipelineVisualModel.operation(node, true, ::resolve)
            val operations = PipelineVisualAssets.leaves(recognition) + listOf(action).filter { it.type != "DoNothing" }
            return html {
                line("参考图 · $path", true)
                if (image == null) line("图片未找到或无法解码") else {
                    val templates = PipelineVisualAssets.previewTemplates(project, context.file.virtualFile, operations, ::resolve)
                    picture(PipelineVisualAssets.overlay(image, operations, ::resolve, templatePreviews = templates), 560, 340)
                    line("实线：识别搜索区或固定动作目标；虚线：动态结果的来源搜索区，实际结果框在运行时确定。")
                    line("${image.width} × ${image.height} · 标注使用参考图像素坐标，参考图应与识别截图尺寸一致。")
                }
                val annotations = PipelinePreviewModel.annotations(operations, ::resolve)
                annotations.take(16).forEach { annotation ->
                    line(annotation.title, true)
                    annotation.details().forEach { line(it) }
                    operationImages(annotation.operation, context, this)
                }
                if (annotations.size > 16) line("其余条件与动作请在节点详情中查看。")
            }
        }
        if (key !in setOf("template", "lower", "upper", "method", "recognition", "action", "anchor", "target", "roi", "custom_action", "custom_recognition")) return null
        val action = key in setOf("action", "custom_action") || generateSequence(origin) { it.parent }.filterIsInstance<JsonProperty>().any { it.name == "action" }
        val owner = generateSequence(origin) { it.parent }.filterIsInstance<JsonObject>()
            .firstOrNull { it.findProperty(if (action) "action" else "recognition") != null || it.findProperty("type") != null }
        val operation = PipelineVisualModel.operation(node.copy(rawJson = owner?.text ?: node.rawJson), action, ::resolve)
        return html {
            line(operation.title, true)
            line(PipelineVisualModel.fieldHelp(key!!))
            PipelineVisualModel.mode(operation)?.let { (mode, explanation) -> line(mode, true); line(explanation) }
            if (key in setOf("roi", "target")) {
                val region = PipelineVisualModel.region(operation, key, ::resolve)
                line(region.summary, true)
                line(region.explanation)
            }
            operationImages(operation, context, this)
            if (operation.children.isNotEmpty()) PipelineVisualAssets.leaves(operation).take(12).forEach {
                line(it.title, true)
                operationImages(it, context, this)
            }
        }
    }

    private fun operationImages(operation: PipelineOperation, context: PipelineJsonContext, html: Html) {
        operation.parameters["template"]?.let { value ->
            val paths = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
            paths.filter { it.isJsonPrimitive }.take(8).forEach { path ->
                html.line("模板：${path.asString}")
                val files = PipelineVisualAssets.templates(context.file.project, operation.source?.sourceFile ?: context.file.virtualFile, path.asString)
                if (files.isEmpty()) html.line("模板图片未找到")
                files.take(8).forEach { file -> PipelineVisualAssets.read(file)?.let { html.picture(it, 300, 150) } }
                if (files.size > 8) html.line("其余模板可打开资源目录查看。")
            }
        }
        PipelineVisualModel.colors(operation)?.let { colors ->
            html.line("${colors.space} 颜色范围样本", true)
            html.line(colors.explanation)
            colors.ranges.take(8).forEach { range ->
                html.line("${range.lower} → ${range.upper}")
                if (colors.samples(range).isEmpty()) html.line(if (colors.supported) "没有有效颜色，请检查通道数及上下限。" else "此颜色空间暂不生成 RGB 图示。")
                else PipelineVisualAssets.colorImage(colors.copy(ranges = listOf(range)))?.let { html.picture(it, 216, 42) }
            }
            if (colors.ranges.size > 8) html.line("其余颜色范围请在详情中查看。")
        }
    }

    override fun getLocalImageForElement(element: PsiElement, imageSpec: String): Image? = pictures[imageSpec]

    private fun html(body: Html.() -> Unit): String = Html().apply(body).build()

    private class Html {
        private val content = StringBuilder("<html><body>")
        private val metrics = java.awt.Canvas().getFontMetrics(com.intellij.util.ui.UIUtil.getLabelFont())
        private var pictureCount = 0
        fun line(value: String, bold: Boolean = false) {
            val bounded = value.take(800)
            val lines = mutableListOf<String>()
            bounded.split('\n').forEach { paragraph ->
                val current = StringBuilder()
                var width = 0
                paragraph.codePoints().forEach { point ->
                    val character = String(Character.toChars(point))
                    val span = metrics.stringWidth(character) + 1
                    if (width + span > com.intellij.util.ui.JBUI.scale(480) && current.isNotEmpty()) {
                        lines += current.toString()
                        current.clear()
                        width = 0
                    }
                    current.append(character)
                    width += span
                }
                lines += current.toString()
            }
            content.append("<div>")
            if (bold) content.append("<b>")
            content.append(lines.joinToString("<br>") { PipelineWrappingLabel.escape(it) })
            if (bold) content.append("</b>")
            content.append("</div>")
        }
        fun picture(image: BufferedImage, width: Int, height: Int) {
            if (pictureCount++ >= 16) {
                if (pictureCount == 17) line("其余图片请在详情中查看。")
                return
            }
            val scaled = PipelineVisualAssets.scaled(image, width, height)
            val key = "maa-preview:${sequence.incrementAndGet()}"
            pictures[key] = scaled
            content.append("<div><img src=\"$key\" width=\"${scaled.width}\" height=\"${scaled.height}\"></div>")
        }
        fun build(): String = content.append("</body></html>").toString()
    }

    companion object {
        private val sequence = AtomicLong()
        private val pictures = Collections.synchronizedMap(object : LinkedHashMap<String, Image>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?) = size > 96
        })
        internal fun imagesFor(html: String): Map<String, Image> = synchronized(pictures) {
            Regex("src=\"([^\"]+)\"").findAll(html).mapNotNull { match ->
                val key = match.groupValues[1]
                pictures[key]?.let { key to it }
            }.toMap()
        }
    }
}
