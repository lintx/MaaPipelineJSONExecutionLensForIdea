package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.ui.components.JBScrollPane
import org.intellij.images.editor.ImageZoomModel
import org.intellij.images.options.ZoomOptions
import org.intellij.images.ui.ImageComponent
import org.intellij.images.ui.ImageComponentDecorator
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.math.roundToInt

internal class PipelineImageViewer(image: BufferedImage, extraAction: AnAction? = null) : JPanel(BorderLayout()), ImageComponentDecorator, UiDataProvider, Disposable {
    val picture = ImageComponent()
    private val surface = object : JPanel(null) {
        override fun getPreferredSize(): Dimension = picture.preferredSize
        override fun doLayout() {
            val size = picture.preferredSize
            picture.setBounds(((width - size.width) / 2).coerceAtLeast(0), ((height - size.height) / 2).coerceAtLeast(0), size.width, size.height)
        }
    }
    val scroll = JBScrollPane(surface)
    val toolbarPanel = JPanel(BorderLayout())
    private val imageSize = Dimension(image.width, image.height)
    private var zoom = 1.0
    private var zoomChanged = false
    private var fitting = true
    private var customOptions: ZoomOptions? = null
    private val model = object : ImageZoomModel {
        override fun getZoomFactor() = zoom
        override fun setZoomFactor(value: Double) {
            val factor = value.coerceIn(0.01, 64.0)
            val previous = zoom
            val viewport = scroll.viewport
            val center = Point(viewport.viewPosition.x + viewport.width / 2 - picture.x - 2,
                viewport.viewPosition.y + viewport.height / 2 - picture.y - 2)
            fitting = false
            zoom = factor
            picture.zoomFactor = factor
            picture.setCanvasSize((imageSize.width * factor).roundToInt().coerceAtLeast(1),
                (imageSize.height * factor).roundToInt().coerceAtLeast(1))
            picture.preferredSize = Dimension(picture.canvasSize.width + 4, picture.canvasSize.height + 4)
            updateSurface()
            viewport.viewPosition = Point(
                (center.x * factor / previous + picture.x + 2 - viewport.width / 2).roundToInt().coerceIn(0, (surface.width - viewport.width).coerceAtLeast(0)),
                (center.y * factor / previous + picture.y + 2 - viewport.height / 2).roundToInt().coerceIn(0, (surface.height - viewport.height).coerceAtLeast(0)))
            picture.repaint()
        }
        override fun fitZoomToWindow() {
            val available = scroll.viewport.extentSize
            if (available.width <= 4 || available.height <= 4) return
            zoomFactor = minOf((available.width - 4).toDouble() / imageSize.width, (available.height - 4).toDouble() / imageSize.height, 1.0)
            fitting = true
            scroll.viewport.viewPosition = Point()
        }
        override fun zoomIn() { zoomFactor *= 1.25; zoomChanged = true }
        override fun zoomOut() { zoomFactor /= 1.25; zoomChanged = true }
        override fun canZoomIn() = zoom < 64.0
        override fun canZoomOut() = zoom > 0.01
        override fun isZoomLevelChanged() = zoomChanged
        override fun setZoomLevelChanged(value: Boolean) { zoomChanged = value }
        override fun getCustomZoomOptions() = customOptions
        override fun setCustomZoomOptions(value: ZoomOptions?) { customOptions = value }
    }

    init {
        // 只组合公开的图片组件和原生图片操作，不依赖 ImageEditorUI 的内部实现。
        picture.name = "pipeline-preview-image"
        picture.document.setValue(image)
        picture.setCanvasSize(imageSize)
        picture.preferredSize = Dimension(image.width + 4, image.height + 4)
        // 滚动区可以填满窗口，图片本身始终只占等比缩放后的像素尺寸。
        surface.add(picture)
        val manager = ActionManager.getInstance()
        val group = manager.getAction("Images.EditorToolbar") as ActionGroup
        val actions = DefaultActionGroup().apply {
            add(group)
            if (extraAction != null) { addSeparator(); add(extraAction) }
        }
        val toolbar = manager.createActionToolbar(org.intellij.images.editor.actionSystem.ImageEditorActions.ACTION_PLACE, actions, true)
        toolbar.targetComponent = this
        toolbarPanel.add(toolbar.component, BorderLayout.WEST)
        add(toolbarPanel, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (fitting) model.fitZoomToWindow() else updateSurface()
            }
        })
    }

    private fun updateSurface() {
        val extent = scroll.viewport.extentSize
        scroll.viewport.viewSize = Dimension(maxOf(extent.width, picture.preferredSize.width), maxOf(extent.height, picture.preferredSize.height))
        surface.doLayout()
        surface.revalidate()
    }

    override fun uiDataSnapshot(sink: DataSink) { sink[ImageComponentDecorator.DATA_KEY] = this }
    override fun getZoomModel(): ImageZoomModel = model
    override fun isEnabledForActionPlace(place: String) = true
    override fun setTransparencyChessboardVisible(value: Boolean) { picture.isTransparencyChessboardVisible = value; picture.repaint() }
    override fun isTransparencyChessboardVisible() = picture.isTransparencyChessboardVisible
    override fun setGridVisible(value: Boolean) { picture.isGridVisible = value; picture.repaint() }
    override fun isGridVisible() = picture.isGridVisible
    override fun setFileSizeVisible(value: Boolean) { picture.isFileSizeVisible = value; picture.repaint() }
    override fun isFileSizeVisible() = picture.isFileSizeVisible
    override fun setFileNameVisible(value: Boolean) { picture.isFileNameVisible = value; picture.repaint() }
    override fun isFileNameVisible() = picture.isFileNameVisible
    override fun setBorderVisible(value: Boolean) { picture.isBorderVisible = value; picture.repaint() }
    override fun setEditorBackground(color: Color?) { picture.background = color; surface.background = color; scroll.viewport.background = color }
    override fun dispose() = Unit
}
