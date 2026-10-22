package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.documentation.DocumentationProviderEx
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class PipelineJsonDocumentationProvider : DocumentationProviderEx() {
    override fun getDocumentationElementForLink(psiManager: PsiManager, link: String, context: PsiElement?): PsiElement? =
        context?.takeIf { PipelineHoverImages.open(it, link) }
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
        previewElement(contextElement ?: file.findElementAt(targetOffset))

    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?): PsiElement? =
        previewElement(contextElement)

    internal fun previewElement(element: PsiElement?): PsiElement? {
        element ?: return null
        val context = PipelineJsonContext.at(element) ?: return null
        val comment = generateSequence(element) { it.parent }.filterIsInstance<PsiComment>().firstOrNull()
        if (comment != null && (comment.text.contains("@preview") || comment.text.contains("@roi"))) return comment
        return element.takeIf { context.field?.name in setOf("template", "lower", "upper", "method", "recognition", "action", "anchor", "target", "begin", "end", "roi", "custom_action", "custom_recognition") }
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = generateDoc(element, originalElement)

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val origin = originalElement?.takeIf { PipelineJsonContext.at(it) != null } ?: element
        val context = PipelineJsonContext.at(origin) ?: return null
        val project = origin.project
        val previewContext = PipelinePreviewContext(context)
        val node = previewContext.location
        val environmentId = previewContext.environment.id
        fun resolve(name: String) = previewContext.resolve(name)
        val comment = generateSequence(origin) { it.parent }.filterIsInstance<PsiComment>().firstOrNull()
        val key = context.field?.name
        if ((comment != null && (comment.text.contains("@preview") || comment.text.contains("@roi"))) ||
            key in setOf("roi", "target", "begin", "end")) {
            val hint = PipelinePreviewHints.preview(node)
            val path = hint?.path
            val image = if (hint != null) PipelineVisualAssets.resolve(project, hint.sourceFile ?: context.file.virtualFile,
                hint.path, true, environmentId)?.let(PipelineVisualAssets::read)
                else PipelinePreviewSettings.getInstance().background(context.file.virtualFile)?.second
            val recognition = PipelineVisualModel.operation(node, resolve = ::resolve)
            val action = PipelineVisualModel.operation(node, true, ::resolve)
            val operations = PipelineVisualAssets.leaves(recognition) + listOf(action).filter { it.type != "DoNothing" }
            val annotation = PipelineHoverImages.annotationAt(origin, operations)
            return html {
                line(path?.let { "参考图 · $it" } ?: "默认预览", true)
                if (image == null) line("图片未找到或无法解码") else {
                    val templates = PipelineVisualAssets.previewTemplates(project, context.file.virtualFile, operations, ::resolve, environmentId)
                    picture(PipelineVisualAssets.overlay(image, operations, ::resolve, selectedAnnotation = annotation?.number,
                        templatePreviews = templates, compact = true), 420, 240,
                        "preview")
                    link("preview", "打开大图")
                }
            }
        }
        if (key !in setOf("template", "lower", "upper", "method", "recognition", "action", "anchor", "target", "begin", "end", "roi", "custom_action", "custom_recognition")) return null
        val action = key in setOf("action", "custom_action", "target", "begin", "end") || generateSequence(origin) { it.parent }.filterIsInstance<JsonProperty>().any { it.name == "action" }
        val owner = generateSequence(origin) { it.parent }.filterIsInstance<JsonObject>()
            .firstOrNull { it.findProperty(if (action) "action" else "recognition") != null || it.findProperty("type") != null }
        val operation = PipelineVisualModel.operation(node.copy(rawJson = owner?.text ?: node.rawJson), action, ::resolve)
        return html {
            line(operation.title, true)
            PipelineVisualModel.mode(operation)?.let { (mode, _) -> line(mode) }
            if (key in setOf("roi", "target", "begin", "end")) {
                val region = PipelineVisualModel.region(operation, key!!, ::resolve)
                line(region.summary, true)
            }
            operationImages(operation, context, this, environmentId)
            if (operation.children.isNotEmpty()) PipelineVisualAssets.leaves(operation).take(12).forEach {
                line(it.title, true)
                operationImages(it, context, this, environmentId)
            }
        }
    }

    private fun operationImages(operation: PipelineOperation, context: PipelineJsonContext, html: Html, environmentId: String) {
        operation.parameters["template"]?.let { value ->
            val paths = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
            paths.filter { it.isJsonPrimitive }.take(8).forEach { path ->
                html.line("模板：${path.asString}")
                val files = PipelineVisualAssets.templates(context.file.project, operation.source?.sourceFile ?: context.file.virtualFile, path.asString, environmentId)
                if (files.isEmpty()) html.line("模板图片未找到")
                files.take(8).forEach { file -> PipelineVisualAssets.read(file)?.let {
                    val key = "template:" + java.net.URLEncoder.encode(file.url, java.nio.charset.StandardCharsets.UTF_8)
                    html.picture(it, 280, 140, key)
                    html.link(key, "打开大图")
                } }
                if (files.size > 8) html.line("其余模板可打开资源目录查看。")
            }
        }
        PipelineVisualModel.colors(operation)?.let { colors ->
            html.line("${colors.space} 颜色范围样本", true)
            colors.ranges.take(8).forEach { range ->
                if (colors.samples(range).isEmpty()) html.line(if (colors.supported) "没有有效颜色，请检查通道数及上下限。" else "此颜色空间暂不生成 RGB 图示。")
                else PipelineVisualAssets.colorImage(colors.copy(ranges = listOf(range)))?.let { html.picture(it, 216, 42, "colors"); html.link("colors", "打开大图") }
            }
            if (colors.ranges.size > 8) html.line("其余颜色范围请在详情中查看。")
        }
    }

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
        fun link(key: String, label: String) {
            content.append("<div><a href=\"psi_element://maa-image:${PipelineWrappingLabel.escape(key)}\">${PipelineWrappingLabel.escape(label)}</a></div>")
        }
        fun picture(image: BufferedImage, width: Int, height: Int, link: String? = null) {
            if (pictureCount++ >= 16) {
                if (pictureCount == 17) line("其余图片请在详情中查看。")
                return
            }
            val scaled = PipelineVisualAssets.scaled(image, width, height)
            val output = ByteArrayOutputStream()
            ImageIO.write(scaled, "png", output)
            val key = "data:image/png;base64,${Base64.getEncoder().encodeToString(output.toByteArray())}"
            // 固定单元格宽高，切断自适应图片高度与悬浮窗口可用宽度的相互反馈。
            content.append("<table width=\"${scaled.width}\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td width=\"${scaled.width}\" height=\"${scaled.height}\" style=\"width:${scaled.width}px;height:${scaled.height}px\">")
            if (link != null) content.append("<a href=\"psi_element://maa-image:${PipelineWrappingLabel.escape(link)}\">")
            content.append("<img src=\"$key\" width=\"${scaled.width}\" height=\"${scaled.height}\">")
            if (link != null) content.append("</a>")
            content.append("</td></tr></table>")
        }
        fun build(): String = content.append("</body></html>").toString()
    }

}
