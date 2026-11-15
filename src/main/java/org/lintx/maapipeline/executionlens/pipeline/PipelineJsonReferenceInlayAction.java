package org.lintx.maapipeline.executionlens.pipeline;

import com.intellij.codeInsight.hints.declarative.InlayActionHandler;
import com.intellij.codeInsight.hints.declarative.InlayActionPayload;
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;

// Java 直接继承平台的默认方法，避免 Kotlin 为已弃用的旧点击接口生成转发实现。
public final class PipelineJsonReferenceInlayAction implements InlayActionHandler {
    public static final String ID = "maapipeline.references";

    @Override
    public void handleClick(@NotNull EditorMouseEvent event, @NotNull InlayActionPayload payload) {
        var editor = event.getEditor();
        var project = editor.getProject();
        var source = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (project != null && source != null && payload instanceof StringInlayActionPayload reference) {
            PipelineJsonReferences.INSTANCE.showUsages(project, source, reference.getText(), editor);
        }
    }
}
