package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.Font
import java.awt.Graphics2D
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import java.util.Collections
import javax.imageio.ImageIO

internal object PipelineVisualAssets {
    data class TemplatePreview(val name: String, val image: BufferedImage?)
    data class OverlayRegion(val rectangle: Rectangle, val descriptions: List<String>, val dynamicOnly: Boolean,
        val annotations: List<PipelinePreviewAnnotation>)
    private val images = Collections.synchronizedMap(object : LinkedHashMap<String, BufferedImage?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage?>): Boolean = size > 48
    })

    fun resolve(project: Project, source: VirtualFile?, path: String, preview: Boolean = false): VirtualFile? {
        val clean = path.replace('\\', '/').removePrefix("./")
        if (clean.isBlank()) return null
        val contentRoots = (listOfNotNull(PipelineProjectFiles.base(project)) + ProjectRootManager.getInstance(project).contentRoots).distinct()
        val imageRoots = PipelineProjectFiles.pipelineRoots(project, source).asReversed().mapNotNull { it.parent.findChild("image") }
        val roots = if (preview) listOfNotNull(source?.parent) + contentRoots + imageRoots else imageRoots
        // 只读取当前工程内的显式资源；不沿相对路径越出工程去猜测本机文件。
        return roots.firstNotNullOfOrNull { it.findFileByRelativePath(clean) }
            ?.takeIf { file -> contentRoots.any { file.path.startsWith(it.path.trimEnd('/') + "/") } }
    }

    fun templates(project: Project, source: VirtualFile?, path: String): List<VirtualFile> {
        val resource = resolve(project, source, path) ?: return emptyList()
        if (!resource.isDirectory) return listOf(resource)
        val files = mutableListOf<VirtualFile>()
        fun visit(directory: VirtualFile) {
            if (files.size >= 64) return
            directory.children.sortedBy { it.name }.forEach { child ->
                if (child.isDirectory) visit(child)
                else if (child.extension?.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "webp") && files.size < 64) files += child
            }
        }
        visit(resource)
        return files
    }

    fun read(file: VirtualFile): BufferedImage? {
        val key = "${file.url}:${file.modificationStamp}:${file.length}"
        synchronized(images) { if (images.containsKey(key)) return images[key] }
        val decoded = try {
            file.inputStream.use { input -> ImageIO.createImageInputStream(input).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return@use null
                val reader = readers.next()
                try {
                    reader.input = stream
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width.toLong() * height > 40_000_000L) null else reader.read(0)
                } finally { reader.dispose() }
            } }
        } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null }
        synchronized(images) {
            if (decoded == null || decoded.width.toLong() * decoded.height <= 16_000_000L) images[key] = decoded
            while (images.values.sumOf { it?.let { image -> image.width.toLong() * image.height } ?: 0L } > 16_000_000L) {
                images.remove(images.keys.first())
            }
        }
        return decoded
    }

    fun scaled(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val ratio = minOf(1.0, width.toDouble() / image.width, height.toDouble() / image.height)
        val result = BufferedImage((image.width * ratio).toInt().coerceAtLeast(1),
            (image.height * ratio).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(image, 0, 0, result.width, result.height, null)
        } finally { graphics.dispose() }
        return result
    }

    fun colorImage(spec: PipelineColorSpec): BufferedImage? {
        if (!spec.supported || spec.ranges.isEmpty()) return null
        val result = BufferedImage(216, spec.ranges.size.coerceAtMost(16) * 36, BufferedImage.TYPE_INT_RGB)
        val graphics = result.createGraphics()
        try {
            graphics.color = Color(128, 128, 128)
            graphics.fillRect(0, 0, result.width, result.height)
            spec.ranges.take(16).forEachIndexed { rangeIndex, range ->
                val samples = spec.samples(range)
                samples.forEachIndexed { sampleIndex, color ->
                    val columns = minOf(36, samples.size)
                    graphics.color = color
                    val rows = ((samples.size + columns - 1) / columns).coerceAtLeast(1)
                    val rowSize = minOf(columns, samples.size - sampleIndex / columns * columns)
                    val left = sampleIndex % columns * 216 / rowSize
                    val right = (sampleIndex % columns + 1) * 216 / rowSize
                    val top = sampleIndex / columns * 36 / rows
                    val bottom = (sampleIndex / columns + 1) * 36 / rows
                    graphics.fillRect(left, rangeIndex * 36 + top, right - left, bottom - top)
                }
            }
        } finally { graphics.dispose() }
        return result
    }

    fun previewTemplates(project: Project, source: VirtualFile, operations: List<PipelineOperation>,
        resolve: (String) -> PipelineNodeLocation? = { null }): Map<Int, List<TemplatePreview>> {
        return PipelinePreviewModel.annotations(operations, resolve).mapNotNull { annotation ->
            val operation = if (annotation.dynamic) annotation.region.resultOperation ?: annotation.operation else annotation.operation
            val template = operation.parameters["template"] ?: return@mapNotNull null
            val paths = (if (template.isJsonArray) template.asJsonArray.toList() else listOf(template))
                .filter { it.isJsonPrimitive }.map { it.asString }
            val files = ReadAction.compute<List<Pair<String, VirtualFile?>>, RuntimeException> {
                if (project.isDisposed) emptyList() else paths.flatMap { path ->
                    templates(project, operation.source?.sourceFile ?: source, path).map { it.name to it }.ifEmpty { listOf(path to null) }
                }
            }
            annotation.number to files.mapIndexed { index, (name, file) ->
                TemplatePreview(name, if (index < 4) file?.let(::read)?.let { scaled(it, 128, 64) } else null)
            }
        }.toMap()
    }

    fun wrapOverlayText(paragraphs: List<String>, metrics: FontMetrics, width: Int): List<String> = buildList {
        paragraphs.flatMap { it.split('\n') }.forEach { paragraph ->
            val current = StringBuilder()
            paragraph.codePoints().forEach { point ->
                val character = String(Character.toChars(point))
                if (metrics.stringWidth(current.toString() + character) > width && current.isNotEmpty()) {
                    add(current.toString())
                    current.clear()
                }
                current.append(character)
            }
            add(current.toString())
        }
    }

    fun overlayRegions(width: Int, height: Int, operations: List<PipelineOperation>,
        resolve: (String) -> PipelineNodeLocation? = { null }): List<OverlayRegion> {
        val regions = mutableListOf<OverlayRegion>()
        PipelinePreviewModel.annotations(operations, resolve).forEach { annotation ->
            val region = annotation.region
            val coordinates = region.coordinates ?: region.searchCoordinates ?: return@forEach
            val rectangle = PipelineRegionInfo.rectangle(coordinates, width, height) ?: return@forEach
            val description = annotation.title + if (annotation.dynamic) " · 实际结果框运行时确定" else ""
            regions += OverlayRegion(rectangle, listOf(description), annotation.dynamic, listOf(annotation))
        }
        // 同坐标不重复描边，仍保留固定识别范围和动态动作来源之间的区别。
        return regions.groupBy { it.rectangle }.map { (rectangle, identical) ->
            OverlayRegion(rectangle, identical.flatMap { it.descriptions }.distinct(), identical.all { it.dynamicOnly },
                identical.flatMap { it.annotations })
        }
    }

    fun overlay(image: BufferedImage, operations: List<PipelineOperation>,
        resolve: (String) -> PipelineNodeLocation? = { null }, selectedAnnotation: Int? = null,
        templatePreviews: Map<Int, List<TemplatePreview>> = emptyMap(), compact: Boolean = false,
        excludedAnnotation: Int? = null): BufferedImage {
        val regions = overlayRegions(image.width, image.height, operations, resolve).mapNotNull { region ->
            val annotations = region.annotations.filter { it.number != excludedAnnotation }
            if (annotations.isEmpty()) null else region.copy(annotations = annotations)
        }
        val visible = regions.filter { selectedAnnotation == null || it.annotations.any { item -> item.number == selectedAnnotation } }
            .map { IndexedValue(it.annotations.first().number - 1, it) }
        val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
            graphics.font = Font(Font.DIALOG, Font.PLAIN, if (compact) maxOf(14, image.width * 12 / 420) else 14)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            visible.forEach { (index, region) ->
                val rectangle = region.rectangle
                graphics.color = Color(0, 0, 0, 210)
                graphics.stroke = BasicStroke(if (compact) maxOf(5f, image.width * 4f / 420) else 5f)
                graphics.drawRect(rectangle.x, rectangle.y, rectangle.width - 1, rectangle.height - 1)
                graphics.color = overlayColor(index)
                val dynamic = selectedAnnotation?.let { number -> region.annotations.first { it.number == number }.let { it.dynamic || it.region.fromComment } }
                    ?: region.annotations.all { it.dynamic || it.region.fromComment }
                graphics.stroke = if (dynamic) BasicStroke(2f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10f, floatArrayOf(7f, 5f), 0f) else BasicStroke(if (compact) maxOf(2f, image.width * 1.5f / 420) else 2f)
                graphics.drawRect(rectangle.x, rectangle.y, rectangle.width - 1, rectangle.height - 1)
            }
            drawSharedEdges(graphics, visible)
            val placed = mutableListOf<Rectangle>()
            visible.forEach { (index, region) ->
                val rectangle = region.rectangle
                val labels = region.annotations.filter { selectedAnnotation == null || it.number == selectedAnnotation }
                val metrics = graphics.fontMetrics
                val small = compact || image.width < 320
                val paragraphs = if (small) listOf(labels.joinToString(" / ") { it.number.toString() })
                    else labels.flatMap { it.imageLines() }
                val templates = if (small) emptyList() else labels.flatMap { templatePreviews[it.number].orEmpty() }.distinctBy { it.name }
                val media = templates.take(4) + if (small) emptyList() else labels.flatMap { annotation ->
                    PipelineVisualModel.colors(annotation.operation)?.let { colors ->
                        colors.ranges.take(8).mapIndexed { rangeIndex, range ->
                            TemplatePreview("${colors.space} ${rangeIndex + 1}", colorImage(colors.copy(ranges = listOf(range))))
                        }
                    }.orEmpty()
                }
                val extra = if (templates.size > 4) listOf("模板预览：前 4 张，共 ${templates.size} 张") else emptyList()
                val contentWidth = (image.width - 12).coerceIn(1, 420)
                val lines = wrapOverlayText(paragraphs + extra, metrics, contentWidth)
                val mediaWidth = media.maxOfOrNull { maxOf(it.image?.width ?: 0, metrics.stringWidth(it.name)) } ?: 0
                val width = maxOf(lines.maxOf { metrics.stringWidth(it) }, mediaWidth.coerceAtMost(contentWidth)) + 8
                val captions = media.map { wrapOverlayText(listOf(it.name + if (it.image == null) "（图片不可用）" else ""), metrics, width - 8) }
                val height = lines.size * metrics.height + 8 + media.mapIndexed { mediaIndex, preview ->
                    captions[mediaIndex].size * metrics.height + (preview.image?.height ?: 0) + 4
                }.sum()
                val candidates = listOf(
                    Rectangle(rectangle.x, rectangle.y - height - 4, width, height),
                    Rectangle(rectangle.x, rectangle.y + rectangle.height + 4, width, height),
                    Rectangle(rectangle.x + rectangle.width + 4, rectangle.y, width, height),
                    Rectangle(rectangle.x - width - 4, rectangle.y, width, height),
                ).map { candidate -> Rectangle(candidate.x.coerceIn(0, (image.width - width).coerceAtLeast(0)),
                    candidate.y.coerceIn(0, (image.height - height).coerceAtLeast(0)), width, height) }
                fun area(bounds: Rectangle): Long = if (bounds.width <= 0 || bounds.height <= 0) 0 else bounds.width.toLong() * bounds.height
                val bounds = candidates.withIndex().minBy { (position, candidate) ->
                    placed.sumOf { area(candidate.intersection(it)) } * 8 +
                        regions.sumOf { area(candidate.intersection(it.rectangle)) } + position
                }.value
                placed += bounds
                graphics.color = overlayColor(index)
                graphics.stroke = BasicStroke(1f)
                graphics.drawLine(bounds.x.coerceIn(rectangle.x, rectangle.x + rectangle.width - 1),
                    bounds.y.coerceIn(rectangle.y, rectangle.y + rectangle.height - 1), bounds.x, bounds.y)
                graphics.color = Color(0, 0, 0, 220)
                graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
                graphics.color = overlayColor(index)
                lines.forEachIndexed { line, text -> graphics.drawString(text, bounds.x + 4, bounds.y + 4 + metrics.ascent + line * metrics.height) }
                var mediaTop = bounds.y + 4 + lines.size * metrics.height
                media.forEachIndexed { mediaIndex, preview ->
                    graphics.color = overlayColor(index)
                    captions[mediaIndex].forEach { text ->
                        graphics.drawString(text, bounds.x + 4, mediaTop + metrics.ascent)
                        mediaTop += metrics.height
                    }
                    preview.image?.let { graphics.drawImage(it, bounds.x + 4, mediaTop, null); mediaTop += it.height }
                    mediaTop += 4
                }
            }
        } finally { graphics.dispose() }
        return result
    }

    private fun overlayColor(index: Int): Color = Color.getHSBColor(index * 0.17f % 1f, 0.7f, 1f)

    private fun drawSharedEdges(graphics: Graphics2D, regions: List<IndexedValue<OverlayRegion>>) {
        data class Edge(val horizontal: Boolean, val fixed: Int, val start: Int, val end: Int, val index: Int)
        val edges = regions.flatMap { (index, region) ->
            val bounds = region.rectangle
            val right = bounds.x + bounds.width - 1
            val bottom = bounds.y + bounds.height - 1
            listOf(Edge(true, bounds.y, bounds.x, right, index), Edge(true, bottom, bounds.x, right, index),
                Edge(false, bounds.x, bounds.y, bottom, index), Edge(false, right, bounds.y, bottom, index))
        }
        // 共边按原坐标分段交替着色，不能让最后绘制的框掩盖其它框的完整边界。
        edges.groupBy { it.horizontal to it.fixed }.forEach { (_, collinear) ->
            val breaks = collinear.flatMap { listOf(it.start, it.end) }.distinct().sorted()
            breaks.zipWithNext().forEach segment@{ (start, end) ->
                val shared = collinear.filter { it.start <= start && it.end >= end }.distinctBy { it.index }
                if (shared.size < 2) return@segment
                shared.forEachIndexed { position, edge ->
                    graphics.color = overlayColor(edge.index)
                    graphics.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, floatArrayOf(7f, (shared.size - 1) * 7f), position * 7f)
                    if (edge.horizontal) graphics.drawLine(start, edge.fixed, end, edge.fixed)
                    else graphics.drawLine(edge.fixed, start, edge.fixed, end)
                }
            }
        }
    }

    fun leaves(operation: PipelineOperation): List<PipelineOperation> = if (operation.children.isEmpty()) listOf(operation)
        else operation.children.flatMap(::leaves)
}
