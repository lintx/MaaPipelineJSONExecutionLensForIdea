package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorPanel
import java.awt.Color
import java.awt.GridLayout
import java.awt.image.BufferedImage
import javax.swing.*

class PipelinePreviewDefaults {
    var imageMode = false
    var imagePath = ""
    var width = 1280
    var height = 720
    var color = 0xFFFFFF
    var lineWidth = 1
}

@Service(Service.Level.APP)
@State(name = "MaaPipelinePreview", storages = [Storage("maaPipelinePreview.xml")])
class PipelinePreviewSettings : PersistentStateComponent<PipelinePreviewDefaults> {
    private var value = PipelinePreviewDefaults()
    override fun getState() = value
    override fun loadState(state: PipelinePreviewDefaults) {
        value = state
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.syncPublisher(CHANGED).run()
    }
    internal fun background(source: VirtualFile): Pair<VirtualFile, BufferedImage>? {
        if (value.imageMode) {
            val file = LocalFileSystem.getInstance().findFileByPath(value.imagePath.replace('\\', '/')) ?: return null
            return PipelineVisualAssets.read(file)?.let { file to it }
        }
        val image = BufferedImage(value.width.coerceIn(1, 8192), value.height.coerceIn(1, 8192), BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            try { graphics.color = Color(value.color); graphics.fillRect(0, 0, image.width, image.height) } finally { graphics.dispose() }
        }
        return source to image
    }
    companion object {
        val CHANGED = com.intellij.util.messages.Topic.create("MaaPipeline preview settings", Runnable::class.java)
        fun getInstance(): PipelinePreviewSettings = service()
        internal fun lineWidth(): Float = (com.intellij.openapi.application.ApplicationManager.getApplication()
            ?.getService(PipelinePreviewSettings::class.java)?.state?.lineWidth ?: 1).coerceIn(1, 10).toFloat()
    }
}

class PipelinePreviewSettingsConfigurable : Configurable {
    private val mode = JComboBox(arrayOf("纯色画布", "图片"))
    private val path = TextFieldWithBrowseButton()
    private val width = JSpinner(SpinnerNumberModel(1280, 1, 8192, 1))
    private val height = JSpinner(SpinnerNumberModel(720, 1, 8192, 1))
    private val color = ColorPanel()
    private val lineWidth = JSpinner(SpinnerNumberModel(1, 1, 10, 1))
    override fun getDisplayName() = "MaaPipeline"
    override fun createComponent(): JComponent = JPanel(java.awt.BorderLayout()).apply {
        val form = JPanel(GridLayout(0, 2, 12, 10))
        form.add(JLabel("默认预览来源")); form.add(mode)
        form.add(JLabel("默认图片")); form.add(path)
        form.add(JLabel("画布尺寸")); form.add(JPanel().apply { add(this@PipelinePreviewSettingsConfigurable.width); add(JLabel("×")); add(this@PipelinePreviewSettingsConfigurable.height) })
        form.add(JLabel("画布颜色")); form.add(color)
        form.add(JLabel("预览框线宽（px）")); form.add(lineWidth)
        path.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor())
        mode.addActionListener { updateEnabled() }
        add(form, java.awt.BorderLayout.NORTH)
        reset()
    }
    private fun updateEnabled() {
        path.isEnabled = mode.selectedIndex == 1
        width.isEnabled = mode.selectedIndex == 0; height.isEnabled = mode.selectedIndex == 0; color.isEnabled = mode.selectedIndex == 0
    }
    override fun reset() {
        val state = PipelinePreviewSettings.getInstance().state
        mode.selectedIndex = if (state.imageMode) 1 else 0
        path.text = state.imagePath; width.value = state.width.coerceIn(1, 8192); height.value = state.height.coerceIn(1, 8192)
        color.selectedColor = Color(state.color)
        lineWidth.value = state.lineWidth.coerceIn(1, 10)
        updateEnabled()
    }
    override fun isModified(): Boolean {
        val state = PipelinePreviewSettings.getInstance().state
        return state.imageMode != (mode.selectedIndex == 1) || state.imagePath != path.text || state.width != width.value ||
            state.height != height.value || state.color != (color.selectedColor?.rgb?.and(0xFFFFFF) ?: 0xFFFFFF) || state.lineWidth != lineWidth.value
    }
    override fun apply() {
        if (mode.selectedIndex == 1) {
            val valid = runCatching {
                val file = LocalFileSystem.getInstance().findFileByPath(path.text.replace('\\', '/'))
                file != null && PipelineVisualAssets.read(file) != null
            }.getOrDefault(false)
            if (!valid) throw com.intellij.openapi.options.ConfigurationException("请选择可读取的预览图片")
        }
        PipelinePreviewSettings.getInstance().loadState(PipelinePreviewDefaults().also {
            it.imageMode = mode.selectedIndex == 1; it.imagePath = path.text; it.width = width.value as Int; it.height = height.value as Int
            it.color = color.selectedColor?.rgb?.and(0xFFFFFF) ?: 0xFFFFFF
            it.lineWidth = lineWidth.value as Int
        })
    }
}
