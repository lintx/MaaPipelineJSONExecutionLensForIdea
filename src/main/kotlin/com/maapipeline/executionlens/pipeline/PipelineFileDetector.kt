package com.maapipeline.executionlens.pipeline

import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

internal object PipelineFileDetector {
    private val pipelineExtensions = setOf("json", "jsonc")
    private val pipelineDirectoryMarker = "/assets/resource/pipeline/"
    private val pipelineFieldPattern = Regex("""(?:"recognition"|"action"|"next"|"on_error"|"anchor")\s*:""")

    fun isPipelineFile(file: VirtualFile): Boolean {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        if (file.isDirectory || extension !in pipelineExtensions) return false

        val normalizedPath = file.path.replace('\\', '/')
        if (normalizedPath.contains(pipelineDirectoryMarker, ignoreCase = true)) return true

        return runCatching {
            val text = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
            text.trimStart().startsWith("{") && pipelineFieldPattern.containsMatchIn(text)
        }.getOrDefault(false)
    }
}
