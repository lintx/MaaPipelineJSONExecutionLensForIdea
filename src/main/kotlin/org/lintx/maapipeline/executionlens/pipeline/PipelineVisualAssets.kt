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
    private data class OverlayContent(val text: String? = null, val image: BufferedImage? = null, val spacing: Int = 0)
    data class OverlayMark(val annotation: PipelinePreviewAnnotation, val original: Boolean = false) {
        val description: String get() = annotation.title + when {
            original -> " · 原始（虚线）"
            annotation.region.originalCoordinates != null -> " · 最终（实线）"
            annotation.dynamic -> " · 实际结果框运行时确定"
            else -> ""
        }
    }
    data class OverlayRegion(val rectangle: Rectangle, val marks: List<OverlayMark>) {
        val annotations: List<PipelinePreviewAnnotation> get() = marks.map { it.annotation }.distinctBy { it.number }
        val descriptions: List<String> get() = marks.map { it.description }.distinct()
        val dynamicOnly: Boolean get() = marks.all { it.annotation.dynamic }
        val originalOnly: Boolean get() = marks.all { it.original }
        val dashed: Boolean get() = marks.all { it.original ||
            (it.annotation.region.originalCoordinates == null && (it.annotation.dynamic || it.annotation.region.fromComment)) }
    }
    private val images = Collections.synchronizedMap(object : LinkedHashMap<String, BufferedImage?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage?>): Boolean = size > 48
    })

    fun resolve(project: Project, source: VirtualFile?, path: String, preview: Boolean = false, environmentId: String = ""): VirtualFile? {
        val clean = path.replace('\\', '/').removePrefix("./")
        if (clean.isBlank()) return null
        val contentRoots = (listOfNotNull(PipelineProjectFiles.base(project)) + ProjectRootManager.getInstance(project).contentRoots).distinct()
        val imageRoots = PipelineProjectFiles.pipelineRoots(project, source, environmentId).asReversed().mapNotNull { it.parent.findChild("image") }
        val roots = if (preview) listOfNotNull(source?.parent) + contentRoots + imageRoots else imageRoots
        // 只读取当前工程内的显式资源；不沿相对路径越出工程去猜测本机文件。
        return roots.firstNotNullOfOrNull { it.findFileByRelativePath(clean) }
            ?.takeIf { file -> contentRoots.any { file.path.startsWith(it.path.trimEnd('/') + "/") } }
    }

    fun templates(project: Project, source: VirtualFile?, path: String, environmentId: String = ""): List<VirtualFile> {
        val resource = resolve(project, source, path, environmentId = environmentId) ?: return emptyList()
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
        return scaled(image, ratio)
    }

    private fun scaled(image: BufferedImage, ratio: Double): BufferedImage {
        val result = BufferedImage((image.width * ratio).toInt().coerceAtLeast(1),
            (image.height * ratio).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.scale(ratio, ratio)
            graphics.drawImage(image, 0, 0, null)
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
        resolve: (String) -> PipelineNodeLocation? = { null }, environmentId: String = ""): Map<Int, List<TemplatePreview>> {
        val previews = PipelinePreviewModel.annotations(operations, resolve).mapNotNull { annotation ->
            // 动态 roi 只借用来源的搜索区，匹配模板仍属于当前条件；动作 target 才展示来源条件的模板。
            val operation = if (annotation.operation.isAction && annotation.dynamic)
                annotation.region.resultOperation ?: annotation.operation else annotation.operation
            val template = operation.parameters["template"] ?: return@mapNotNull null
            val paths = (if (template.isJsonArray) template.asJsonArray.toList() else listOf(template))
                .filter { it.isJsonPrimitive }.map { it.asString }
            val files = ReadAction.compute<List<Pair<String, VirtualFile?>>, RuntimeException> {
                if (project.isDisposed) emptyList() else paths.flatMap { path ->
                    templates(project, operation.source?.sourceFile ?: source, path, environmentId).map { it.name to it }.ifEmpty { listOf(path to null) }
                }
            }
            annotation.number to files.mapIndexed { index, (name, file) ->
                TemplatePreview(name, if (index < 4) file?.let(::read) else null)
            }
        }.toMap()
        // 同一预览共用像素比例，避免模板裁剪留白不同导致按钮内容被缩放成不同大小。
        val ratio = previews.values.asSequence().flatten().mapNotNull { it.image }.map {
            minOf(1.0, 128.0 / it.width, 64.0 / it.height)
        }.minOrNull() ?: 1.0
        return previews.mapValues { (_, templates) ->
            templates.map { template -> template.copy(image = template.image?.let { scaled(it, ratio) }) }
        }
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
        resolve: (String) -> PipelineNodeLocation? = { null }, originalDraft: Pair<Int, List<Int>>? = null): List<OverlayRegion> {
        val regions = mutableListOf<OverlayRegion>()
        PipelinePreviewModel.annotations(operations, resolve).forEach { item ->
            val annotation = if (originalDraft == null || originalDraft.first != item.number) item else {
                val base = originalDraft.second
                val offset = PipelineVisualModel.integers(item.operation.parameters["${item.key}_offset"])?.takeIf { it.size == 4 }
                item.copy(region = item.region.copy(coordinates = offset?.let { base.zip(it) { value, delta -> value + delta } } ?: base,
                    originalCoordinates = base.takeIf { offset != null }))
            }
            val region = annotation.region
            region.originalCoordinates?.let { coordinates ->
                PipelineRegionInfo.rectangle(coordinates, width, height)?.let { rectangle ->
                    regions += OverlayRegion(rectangle, listOf(OverlayMark(annotation, original = true)))
                }
            }
            (region.coordinates ?: region.searchCoordinates)?.let { coordinates ->
                val rectangle = if (region.coordinates != null) region.rectangle(width, height)
                    else PipelineRegionInfo.rectangle(coordinates, width, height)
                rectangle?.let {
                    regions += OverlayRegion(rectangle, listOf(OverlayMark(annotation)))
                }
            }
        }
        // 同坐标不重复描边，仍保留固定识别范围和动态动作来源之间的区别。
        return regions.groupBy { it.rectangle }.map { (rectangle, identical) ->
            OverlayRegion(rectangle, identical.flatMap { it.marks })
        }
    }

    fun overlay(image: BufferedImage, operations: List<PipelineOperation>,
        resolve: (String) -> PipelineNodeLocation? = { null }, selectedAnnotation: Int? = null,
        templatePreviews: Map<Int, List<TemplatePreview>> = emptyMap(), compact: Boolean = false,
        excludedAnnotation: Int? = null, originalDraft: List<Int>? = null, finalDraft: List<Int>? = null): BufferedImage {
        val draft = if (excludedAnnotation != null && originalDraft != null) excludedAnnotation to originalDraft else null
        val sourceRegions = overlayRegions(image.width, image.height, operations, resolve, draft).toMutableList()
        if (finalDraft != null) {
            PipelinePreviewModel.annotations(operations, resolve).firstOrNull { it.number == excludedAnnotation }
                ?.takeIf { it.region.originalCoordinates == null }?.let { annotation ->
                    annotation.region.coordinates?.let { PipelineRegionInfo.rectangle(it, image.width, image.height) }?.let { rectangle ->
                        sourceRegions += OverlayRegion(rectangle, listOf(OverlayMark(annotation, original = true)))
                    }
                }
        }
        val regions = sourceRegions.mapNotNull { region ->
            // 原始框由交互编辑框接管，最终框仍由基础值与 offset 计算，且不影响其它区域。
            val marks = region.marks.filter { it.annotation.number != excludedAnnotation ||
                if (finalDraft != null) it.original else (!it.original && it.annotation.region.originalCoordinates != null) }
            if (marks.isEmpty()) null else region.copy(marks = marks)
        }
        val visible = regions.filter { selectedAnnotation == null || it.annotations.any { item -> item.number == selectedAnnotation } }
            .map { region -> region.copy(marks = region.marks.filter { selectedAnnotation == null || it.annotation.number == selectedAnnotation }) }
            .map { IndexedValue(it.annotations.first().number - 1, it) }
        val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        val lineWidth = PipelinePreviewSettings.lineWidth()
        try {
            graphics.drawImage(image, 0, 0, null)
            graphics.font = Font(Font.DIALOG, Font.PLAIN, if (compact) maxOf(14, image.width * 12 / 420) else 14)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            visible.forEach { (index, region) ->
                drawRoiFrame(graphics, region.rectangle, index, region.originalOnly, region.dashed, lineWidth)
            }
            drawSharedEdges(graphics, visible, lineWidth)
            val placed = mutableListOf<Rectangle>()
            visible.forEach { (index, region) ->
                val rectangle = region.rectangle
                val labels = region.annotations
                val metrics = graphics.fontMetrics
                val small = compact || image.width < 320
                val contentWidth = (image.width - 12).coerceIn(1, 420)
                val content = labels.flatMap { annotation ->
                    val original = region.marks.any { it.annotation.number == annotation.number && it.original }
                    val final = region.marks.any { it.annotation.number == annotation.number && !it.original }
                    val paragraphs = if (small) listOf("${annotation.number}" + when {
                        original && final -> " 原始/最终"
                        original -> " 原始"
                        annotation.region.originalCoordinates != null -> " 最终"
                        else -> ""
                    }) else buildList {
                        if (original) {
                            add("${annotation.number} 原始（虚线） ${annotation.key}=${annotation.region.originalCoordinates}")
                            if (final) add("原始与最终同框")
                        }
                        if (final) addAll(annotation.imageLines(rectangle))
                    }
                    val templates = if (small || !final) emptyList() else templatePreviews[annotation.number].orEmpty().distinctBy { it.name }
                    val media = templates.take(4) + if (small || !final) emptyList() else
                        PipelineVisualModel.colors(annotation.operation)?.let { colors ->
                            colors.ranges.take(8).mapIndexed { rangeIndex, range ->
                                TemplatePreview("${colors.space} ${rangeIndex + 1}", colorImage(colors.copy(ranges = listOf(range))))
                            }
                        }.orEmpty()
                    val extra = if (templates.size > 4) listOf("模板预览：前 4 张，共 ${templates.size} 张") else emptyList()
                    buildList {
                        wrapOverlayText(paragraphs + extra, metrics, contentWidth).forEach { add(OverlayContent(text = it)) }
                        media.forEach { preview ->
                            wrapOverlayText(listOf(preview.name + if (preview.image == null) "（图片不可用）" else ""), metrics, contentWidth)
                                .forEach { add(OverlayContent(text = it)) }
                            preview.image?.let { add(OverlayContent(image = it)) }
                            add(OverlayContent(spacing = 4))
                        }
                    }
                }
                val width = (content.maxOfOrNull { it.text?.let(metrics::stringWidth) ?: it.image?.width ?: 0 } ?: 0) + 8
                val height = content.sumOf { if (it.text != null) metrics.height else (it.image?.height ?: 0) + it.spacing } + 8
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
                graphics.color = overlayColor(index, region.originalOnly)
                graphics.stroke = BasicStroke(1f)
                graphics.drawLine(bounds.x.coerceIn(rectangle.x, rectangle.x + rectangle.width - 1),
                    bounds.y.coerceIn(rectangle.y, rectangle.y + rectangle.height - 1), bounds.x, bounds.y)
                graphics.color = Color(0, 0, 0, 220)
                graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
                graphics.color = overlayColor(index, region.originalOnly)
                var contentTop = bounds.y + 4
                content.forEach { item ->
                    item.text?.let { text ->
                        graphics.drawString(text, bounds.x + 4, contentTop + metrics.ascent)
                        contentTop += metrics.height
                    }
                    item.image?.let { graphics.drawImage(it, bounds.x + 4, contentTop, null); contentTop += it.height }
                    contentTop += item.spacing
                }
            }
        } finally { graphics.dispose() }
        return result
    }

    private fun overlayColor(index: Int, original: Boolean = false): Color = Color.getHSBColor(index * 0.17f % 1f,
        if (original) 0.35f else 0.7f, 1f)

    fun drawRoiFrame(graphics: Graphics2D, rectangle: Rectangle, index: Int, original: Boolean, dashed: Boolean, lineWidth: Float) {
        val shape = java.awt.geom.Rectangle2D.Double(rectangle.x.toDouble(), rectangle.y.toDouble(),
            (rectangle.width - 1).coerceAtLeast(0).toDouble(), (rectangle.height - 1).coerceAtLeast(0).toDouble())
        drawContrastingLine(graphics, shape, index, original, lineWidth, if (dashed) floatArrayOf(7f, 5f) else null)
    }

    private fun drawContrastingLine(graphics: Graphics2D, shape: java.awt.Shape, index: Int, original: Boolean,
        lineWidth: Float, dash: FloatArray? = null, phase: Float = 0f) {
        graphics.color = overlayColor(index, original)
        graphics.stroke = if (dash == null) BasicStroke(lineWidth) else
            BasicStroke(lineWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, phase)
        graphics.draw(shape)
        // 深色段只覆盖同一路径的已有亮色段，保留 ROI 色相、线宽及原始框的透明间隔。
        val darkDash = if (dash == null) floatArrayOf(4f, 4f) else floatArrayOf(3f, dash.sum() - 3f)
        graphics.color = Color.getHSBColor(index * 0.17f % 1f, 0.85f, 0.25f)
        graphics.stroke = BasicStroke(lineWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, darkDash, phase)
        graphics.draw(shape)
    }

    private fun drawSharedEdges(graphics: Graphics2D, regions: List<IndexedValue<OverlayRegion>>, lineWidth: Float) {
        data class Edge(val horizontal: Boolean, val fixed: Int, val start: Int, val end: Int, val index: Int,
            val identity: Int, val original: Boolean)
        val edges = regions.flatMapIndexed { identity, (index, region) ->
            val bounds = region.rectangle
            val right = bounds.x + bounds.width - 1
            val bottom = bounds.y + bounds.height - 1
            listOf(Edge(true, bounds.y, bounds.x, right, index, identity, region.originalOnly), Edge(true, bottom, bounds.x, right, index, identity, region.originalOnly),
                Edge(false, bounds.x, bounds.y, bottom, index, identity, region.originalOnly), Edge(false, right, bounds.y, bottom, index, identity, region.originalOnly))
        }
        // 共边按原坐标分段交替着色，不能让最后绘制的框掩盖其它框的完整边界。
        edges.groupBy { it.horizontal to it.fixed }.forEach { (_, collinear) ->
            val breaks = collinear.flatMap { listOf(it.start, it.end) }.distinct().sorted()
            breaks.zipWithNext().forEach segment@{ (start, end) ->
                val shared = collinear.filter { it.start <= start && it.end >= end }.distinctBy { it.identity }
                if (shared.size < 2) return@segment
                shared.forEachIndexed { position, edge ->
                    val shape = if (edge.horizontal) java.awt.geom.Line2D.Double(start.toDouble(), edge.fixed.toDouble(), end.toDouble(), edge.fixed.toDouble())
                        else java.awt.geom.Line2D.Double(edge.fixed.toDouble(), start.toDouble(), edge.fixed.toDouble(), end.toDouble())
                    drawContrastingLine(graphics, shape, edge.index, edge.original, lineWidth,
                        floatArrayOf(7f, (shared.size - 1) * 7f), position * 7f)
                }
            }
        }
    }

    fun leaves(operation: PipelineOperation): List<PipelineOperation> = if (operation.children.isEmpty()) listOf(operation)
        else operation.children.flatMap(::leaves)
}
