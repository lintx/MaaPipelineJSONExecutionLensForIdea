package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.testFramework.LightVirtualFile
import java.util.concurrent.ConcurrentHashMap

internal class PipelineWorkspaceFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    private val files = ConcurrentHashMap<String, PipelineWorkspaceFile>()

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): PipelineWorkspaceFile? {
        if (!path.startsWith('/') || !path.endsWith("/Pipeline 流程") || path.count { it == '/' } != 2) return null
        if (path.substring(1).substringBefore('/').isEmpty()) return null
        return files.computeIfAbsent(path) { PipelineWorkspaceFile(this, it) }
    }

    override fun refresh(asynchronous: Boolean) = Unit

    override fun refreshAndFindFileByPath(path: String): PipelineWorkspaceFile? = findFileByPath(path)

    companion object {
        const val PROTOCOL = "maa-pipeline"
    }
}

internal class PipelineWorkspaceFile(
    private val workspaceFileSystem: PipelineWorkspaceFileSystem,
    private val workspacePath: String,
) : LightVirtualFile("Pipeline 流程") {
    // IDE 恢复标签时可重新解析地址；工作区内容仍由项目阅读状态提供，不创建磁盘文件。
    override fun getFileSystem(): PipelineWorkspaceFileSystem = workspaceFileSystem
    override fun getPath(): String = workspacePath
    override fun getPresentableUrl(): String = name
}
