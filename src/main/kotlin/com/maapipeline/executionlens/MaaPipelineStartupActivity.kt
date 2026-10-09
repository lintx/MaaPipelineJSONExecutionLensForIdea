package com.maapipeline.executionlens

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class MaaPipelineStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(MaaPipelineStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        // Phase 0 先保留可下断点的项目启动入口，后续在这里接入 Pipeline 索引服务。
        logger.info("MaaPipeline JSON Execution Lens loaded for project: ${project.name}")
    }
}
