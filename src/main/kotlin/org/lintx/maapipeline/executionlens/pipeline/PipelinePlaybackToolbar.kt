package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.HierarchyEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.Timer

internal class PipelinePlaybackToolbar(
    private val onPositionChanged: (Boolean) -> Unit,
    private val onNavigateLog: (Int) -> Unit,
) : JPanel(BorderLayout()), Disposable {
    var session: PipelinePlaybackCursor? = null
        private set
    var follow = true
        private set
    private var speed = 1.0
    private var updating = false
    private val slider = JSlider(0, 0, 0)
    private val positionLabel = JBLabel("0 / 0")
    private val eventLabel = JBLabel()
    private val timer = Timer(1000) { advance() }
    private val actions = DefaultActionGroup()
    private val toolbar = ActionManager.getInstance().createActionToolbar("MaaPipeline.Playback", actions, true)

    init {
        isVisible = false
        border = JBUI.Borders.empty(4, 8)
        fun action(text: String, icon: Icon, enabled: () -> Boolean, operation: () -> Unit) {
            actions.add(object : DumbAwareAction(text, text, icon) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(event: AnActionEvent) { event.presentation.isEnabled = enabled() }
                override fun actionPerformed(event: AnActionEvent) { pause(); operation() }
            })
        }
        action("回到首步", AllIcons.Actions.Play_first, { session?.canPrevious == true }) { seek(0) }
        action("后退一步", AllIcons.Actions.Play_back, { session?.canPrevious == true }) { seek(session?.previousPosition ?: -1) }
        actions.add(object : DumbAwareAction("播放", "自动回放日志", AllIcons.Actions.Execute) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.text = if (timer.isRunning) "暂停" else "播放"
                event.presentation.icon = if (timer.isRunning) AllIcons.Actions.Pause else AllIcons.Actions.Execute
                event.presentation.isEnabled = session?.let { it.source.canAutoplay && it.canNext } == true
            }
            override fun actionPerformed(event: AnActionEvent) {
                if (timer.isRunning) pause() else if (session?.canNext == true) { timer.start(); refresh() }
            }
        })
        action("下一步", AllIcons.Actions.Play_forward, { session?.canNext == true }) { seek((session?.position ?: -1) + 1) }
        action("跳到末步", AllIcons.Actions.Play_last, { session?.canNext == true }) { seek((session?.size ?: 0) - 1) }
        actions.addSeparator()
        actions.add(object : ComboBoxAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.text = session?.granularity?.label ?: "节点"
                event.presentation.isEnabled = session?.size?.let { it > 0 } == true
            }
            override fun createPopupActionGroup(button: JComponent) = DefaultActionGroup().apply {
                PipelinePlaybackGranularity.entries.forEach { value ->
                    add(object : DumbAwareAction(value.label) {
                        override fun actionPerformed(event: AnActionEvent) {
                            pause(); session?.changeGranularity(value); refresh(); onPositionChanged(false)
                        }
                    })
                }
            }
        })
        actions.add(object : ComboBoxAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.text = "${speed}×"
                event.presentation.description = "回放速度（1× 每秒前进一步）"
                event.presentation.isEnabled = session?.source?.canAutoplay == true
            }
            override fun createPopupActionGroup(button: JComponent) = DefaultActionGroup().apply {
                listOf(0.5, 1.0, 2.0, 4.0).forEach { value ->
                    add(object : DumbAwareAction("${value}×") {
                        override fun actionPerformed(event: AnActionEvent) {
                            speed = value; timer.delay = (1000 / speed).toInt(); timer.initialDelay = timer.delay; refresh()
                        }
                    })
                }
            }
        })
        actions.add(object : ToggleAction("跟随当前位置", "自动滚动流程至当前节点", AllIcons.General.Locate) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(event: AnActionEvent) = follow
            override fun setSelected(event: AnActionEvent, state: Boolean) {
                follow = state
                onPositionChanged(state)
            }
        })
        action("查看当前位置原始日志", AllIcons.FileTypes.Text, { session?.frame?.lineNumber != null }) {
            session?.frame?.lineNumber?.let(onNavigateLog)
        }
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
        val progress = JPanel(BorderLayout(8, 0)).apply {
            add(slider, BorderLayout.CENTER)
            add(positionLabel, BorderLayout.EAST)
        }
        add(progress, BorderLayout.CENTER)
        eventLabel.preferredSize = Dimension(JBUI.scale(180), JBUI.scale(24))
        add(eventLabel, BorderLayout.SOUTH)
        slider.toolTipText = "拖动定位到回放步骤"
        slider.addChangeListener {
            if (!updating) {
                // pause() 会刷新旧游标；拖动期间只停止计时，保留预览，松手后提交目标位置。
                val target = slider.value
                timer.stop()
                if (slider.valueIsAdjusting) {
                    updatePositionPreview(target)
                    toolbar.updateActionsImmediately()
                } else seek(target)
            }
        }
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !isShowing) pause()
        }
    }

    fun bind(value: PipelinePlaybackCursor?, loaded: Boolean) {
        if (session !== value) pause()
        session = value
        isVisible = loaded
        refresh()
    }

    fun seek(position: Int) {
        if (session?.seek(position) == true) onPositionChanged(false)
        refresh()
    }

    private fun advance() {
        val value = session ?: return pause()
        if (!value.canNext) return pause()
        seek(value.position + 1)
        if (!value.canNext) pause()
    }

    fun pause() { timer.stop(); refresh() }

    fun refresh() {
        val value = session
        updating = true
        try {
            slider.maximum = ((value?.size ?: 0) - 1).coerceAtLeast(0)
            if (!slider.valueIsAdjusting) slider.value = value?.position ?: 0
            slider.isEnabled = value != null && value.size > 1 && value.source.canSeekBackward
            val displayedPosition = if (slider.valueIsAdjusting) slider.value else value?.position ?: 0
            positionLabel.text = positionText(displayedPosition, value?.size ?: 0)
            eventLabel.text = value?.frame?.let { frame ->
                listOfNotNull(frame.timestamp, frame.text).joinToString(" · ")
            } ?: "当前入口没有可回放的日志事件"
            eventLabel.toolTipText = eventLabel.text
        } finally { updating = false }
        toolbar.updateActionsImmediately()
    }

    private fun updatePositionPreview(position: Int) {
        positionLabel.text = positionText(position, slider.maximum + 1)
    }

    private fun positionText(position: Int, size: Int): String =
        if (size <= 0) "第 0 / 0 步" else "第 ${(position + 1).coerceIn(1, size)} / $size 步"

    override fun dispose() { timer.stop() }
}
