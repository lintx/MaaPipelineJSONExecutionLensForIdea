package org.lintx.maapipeline.executionlens.pipeline

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.testFramework.LightVirtualFile
import java.util.concurrent.ConcurrentHashMap

internal class PipelineWorkspaceFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    private val files = ConcurrentHashMap<String, PipelineWorkspaceFile>()

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): PipelineWorkspaceFile? {
        val parts = path.split('/')
        if (parts.size != 3 || parts[0].isNotEmpty()) return null
        if (parts[2] != "Pipeline 流程" && !parts[2].startsWith("node-")) return null
        if (parts[2].startsWith("node-") && runCatching { decodeNode(parts[2]) }.getOrNull() == null) return null
        if (path.substring(1).substringBefore('/').isEmpty()) return null
        return files.computeIfAbsent(path) { PipelineWorkspaceFile(this, it) }
    }

    override fun refresh(asynchronous: Boolean) = Unit

    override fun refreshAndFindFileByPath(path: String): PipelineWorkspaceFile? = findFileByPath(path)

    companion object {
        const val PROTOCOL = "maa-pipeline"
        fun nodePath(projectHash: String, sourceUrl: String, name: String): String =
            "/$projectHash/node-" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("$sourceUrl\n$name".toByteArray(Charsets.UTF_8))

        fun decodeNode(name: String): Pair<String, String>? {
            if (!name.startsWith("node-")) return null
            val parts = String(java.util.Base64.getUrlDecoder().decode(name.removePrefix("node-")), Charsets.UTF_8).split('\n', limit = 2)
            return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
        }
    }
}

internal class PipelineWorkspaceFile(
    private val workspaceFileSystem: PipelineWorkspaceFileSystem,
    private val workspacePath: String,
) : LightVirtualFile(PipelineWorkspaceFileSystem.decodeNode(workspacePath.substringAfterLast('/'))?.second?.let { "流程 · $it" } ?: "Pipeline 流程") {
    val nodeRequest: Pair<String, String>? = PipelineWorkspaceFileSystem.decodeNode(workspacePath.substringAfterLast('/'))
    val nodeContext: String? get() = nodeRequest?.let { (url, node) -> "node:$url#$node" }
    // IDE 恢复标签时可重新解析地址；工作区内容仍由项目阅读状态提供，不创建磁盘文件。
    override fun getFileSystem(): PipelineWorkspaceFileSystem = workspaceFileSystem
    override fun getPath(): String = workspacePath
    override fun getPresentableUrl(): String = name
}
