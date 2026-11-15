package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.ui.popup.util.PopupUtil
import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.swing.JEditorPane
import javax.swing.text.html.HTMLDocument

internal object PipelineDocumentationPopup {
    fun close(marker: String) {
        Window.getWindows().filter { it.isShowing }.forEach { window ->
            pane(window, marker)?.let { PopupUtil.getPopupContainerFor(it)?.cancel() }
        }
    }

    internal fun pane(component: Component, marker: String): JEditorPane? {
        if (component is JEditorPane && (component.document as? HTMLDocument)?.getElement(marker) != null) return component
        return (component as? Container)?.components?.firstNotNullOfOrNull { pane(it, marker) }
    }
}
