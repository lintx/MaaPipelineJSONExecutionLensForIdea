package org.lintx.maapipeline.executionlens.pipeline

internal data class PipelineTraceTaskCall(
    val processId: String,
    val parentTaskId: Long,
    val childTaskId: Long,
    val entryName: String,
    val caller: String,
    val startLine: Int,
    var endLine: Int? = null,
)

internal data class PipelineTraceTaskRun(val processId: String, val taskId: Long, val entryName: String, val startLine: Int)

internal class PipelineTraceTaskCallCollector {
    private data class Frame(val taskId: Long, var nodeName: String?, val call: PipelineTraceTaskCall?)
    private data class ThreadState(val stack: MutableList<Frame> = mutableListOf(), var contextEntry: String? = null)
    private val threads = mutableMapOf<Pair<String, String>, ThreadState>()
    private val processPattern = Regex("\\[P[xX]([^]]+)]")
    private val threadPattern = Regex("\\[T[xX]([^]]+)]")
    val calls = mutableListOf<PipelineTraceTaskCall>()
    val runs = mutableListOf<PipelineTraceTaskRun>()

    fun accept(line: String, lineNumber: Int) {
        if (!line.contains("::PipelineTask::run]") && !line.contains("::Context::run_task]")) return
        val process = processPattern.find(line)?.groupValues?.get(1) ?: return
        val thread = threadPattern.find(line)?.groupValues?.get(1) ?: return
        val state = threads.getOrPut(process to thread) { ThreadState() }
        if (line.contains("::Context::run_task]")) {
            state.contextEntry = field(line, "entry")
            return
        }
        if (line.contains("| enter")) {
            val taskId = field(line, "task_id_")?.toLongOrNull() ?: return
            val entry = field(line, "entry_") ?: return
            val parent = state.stack.lastOrNull()
            if (parent == null) runs += PipelineTraceTaskRun(process, taskId, entry, lineNumber)
            // 只有同线程嵌套执行且 Context 入口吻合时才承认父子关系，不能按时间或节点同名猜测。
            val call = if (parent != null && parent.taskId != taskId && state.contextEntry == entry && parent.nodeName != null) {
                PipelineTraceTaskCall(process, parent.taskId, taskId, entry, parent.nodeName!!, lineNumber).also(calls::add)
            } else null
            state.contextEntry = null
            state.stack += Frame(taskId, null, call)
        } else if (line.contains("| leave")) {
            state.contextEntry = null
            state.stack.removeLastOrNull()?.call?.endLine = lineNumber
        }
    }

    fun observe(event: PipelineTraceEvent, line: String) {
        if (event.kind !in setOf(PipelineTraceEventKind.ACTION, PipelineTraceEventKind.PIPELINE_NODE) ||
            event.status != PipelineTraceStatus.STARTING) return
        val thread = threadPattern.find(line)?.groupValues?.get(1) ?: return
        val process = event.processId ?: return
        val frame = threads[process to thread]?.stack?.lastOrNull() ?: return
        if (frame.taskId == event.taskId) frame.nodeName = event.nodeName
    }

    private fun field(line: String, name: String): String? = line.substringAfter("[$name=", "")
        .substringBefore(']').takeIf { it.isNotEmpty() }
}
