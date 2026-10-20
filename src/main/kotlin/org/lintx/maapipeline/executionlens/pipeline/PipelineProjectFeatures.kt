package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

internal object PipelineProjectFeatures {
    fun supportsMaaEndSubTasks(project: Project, file: VirtualFile): Boolean {
        val manifest = PipelineProjectFiles.manifest(project, file) ?: return false
        val metadata = PipelineVisualModel.parse(PipelineProjectFiles.text(manifest)) ?: return false
        fun text(name: String) = metadata.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        // 同名 Custom 动作在其他项目中可以有不同实现，不能仅凭 SubTask 字符串套用 MaaEnd 语义。
        return text("name").equals("MaaEnd", ignoreCase = true) &&
            text("github").trimEnd('/').removeSuffix(".git").equals("https://github.com/MaaEnd/MaaEnd", ignoreCase = true)
    }
}
