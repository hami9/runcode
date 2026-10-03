package com.runcode.app.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Calls into runcode_debugger.py. Swapped for a fake in tests. */
interface DebugBridge {
    fun command(sessionId: String, name: String): String
    fun evaluate(sessionId: String, expression: String): String
    fun setBreakpoints(sessionId: String, file: String, linesJson: String): String
}

enum class DebugStatus { IDLE, STARTING, RUNNING, PAUSED, FINISHED }

data class DebugFrame(val file: String, val line: Int, val function: String)

data class DebugVariable(val name: String, val type: String, val value: String)

data class DebugState(
    val status: DebugStatus = DebugStatus.IDLE,
    val projectId: String? = null,
    /** Path relative to source/, e.g. "main.py". */
    val file: String? = null,
    val line: Int = 0,
    val function: String? = null,
    /** "breakpoint" or "step". */
    val reason: String? = null,
    val stack: List<DebugFrame> = emptyList(),
    val locals: List<DebugVariable> = emptyList(),
    val globals: List<DebugVariable> = emptyList(),
    /** For FINISHED: completed, stopped, failed:… or fatal:… as the runner reports it. */
    val outcome: String? = null
) {
    companion object {
        fun parse(projectId: String, json: String): DebugState {
            val o = JSONObject(json)
            val status = when (o.optString("status")) {
                "starting" -> DebugStatus.STARTING
                "running" -> DebugStatus.RUNNING
                "paused" -> DebugStatus.PAUSED
                "finished" -> DebugStatus.FINISHED
                else -> DebugStatus.IDLE
            }
            fun variables(key: String) = o.optJSONArray(key).objects().map {
                DebugVariable(it.optString("name"), it.optString("type"), it.optString("value"))
            }
            return DebugState(
                status = status,
                projectId = projectId,
                file = o.optString("file").ifEmpty { null },
                line = o.optInt("line", 0),
                function = o.optString("function").ifEmpty { null },
                reason = o.optString("reason").ifEmpty { null },
                stack = o.optJSONArray("stack").objects().map {
                    DebugFrame(it.optString("file"), it.optInt("line"), it.optString("function"))
                },
                locals = variables("locals"),
                globals = variables("globals"),
                outcome = o.optString("outcome").ifEmpty { null }
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    }
}

/**
 * The debugger as the app sees it: breakpoints per project file, the state of the one debug
 * run that can exist at a time, and the commands that drive it.
 *
 * A debug run is an ordinary run of the project through the supervisor; [arm] marks the next
 * start of a project as a debug run, and the Python engine [takeLaunch]es it.
 */
class PythonDebugger(private val bridge: DebugBridge) {

    private val _state = MutableStateFlow(DebugState())
    val state: StateFlow<DebugState> = _state.asStateFlow()

    /** projectId -> path under source/ -> 1-based lines. */
    private val _breakpoints = MutableStateFlow<Map<String, Map<String, Set<Int>>>>(emptyMap())
    val breakpoints: StateFlow<Map<String, Map<String, Set<Int>>>> = _breakpoints.asStateFlow()

    private val armed = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var session: Session? = null

    private class Session(val projectId: String, val sessionId: String, val sourceDir: File)

    fun breakpointsFor(projectId: String, file: String): Set<Int> =
        _breakpoints.value[projectId]?.get(file).orEmpty()

    /** Adds or removes a breakpoint; a running debug session picks the change up at once. */
    fun toggleBreakpoint(projectId: String, file: String, line: Int) {
        val forProject = _breakpoints.value[projectId].orEmpty()
        val lines = forProject[file].orEmpty().let { if (line in it) it - line else it + line }
        val updated = if (lines.isEmpty()) forProject - file else forProject + (file to lines)
        _breakpoints.value = if (updated.isEmpty()) _breakpoints.value - projectId else _breakpoints.value + (projectId to updated)
        session?.takeIf { it.projectId == projectId }?.let { s ->
            runCatching { bridge.setBreakpoints(s.sessionId, File(s.sourceDir, file).absolutePath, JSONArray(lines.sorted()).toString()) }
        }
    }

    fun setBreakpoints(projectId: String, file: String, lines: Set<Int>) {
        val forProject = _breakpoints.value[projectId].orEmpty()
        val updated = if (lines.isEmpty()) forProject - file else forProject + (file to lines)
        _breakpoints.value = _breakpoints.value + (projectId to updated)
    }

    // ---------------------------------------------------------------- launching

    /** The next start of [projectId] is a debug run. Call [disarm] after the start attempt. */
    fun arm(projectId: String) {
        armed += projectId
        _state.value = DebugState(DebugStatus.STARTING, projectId)
    }

    fun disarm(projectId: String) {
        if (armed.remove(projectId) && _state.value.projectId == projectId && _state.value.status == DebugStatus.STARTING) {
            _state.value = DebugState()
        }
    }

    /** Called by the engine when it starts [projectId]: breakpoints as JSON, or null for a normal run. */
    fun takeLaunch(projectId: String, sessionId: String, sourceDir: File): String? {
        if (!armed.remove(projectId)) return null
        session = Session(projectId, sessionId, sourceDir)
        val byFile = JSONObject()
        _breakpoints.value[projectId].orEmpty().forEach { (file, lines) ->
            byFile.put(File(sourceDir, file).absolutePath, JSONArray(lines.sorted()))
        }
        return byFile.toString()
    }

    /** Receives state from runcode_debugger.py. */
    inner class Listener(private val projectId: String) {
        @Suppress("unused") // invoked from Python
        fun onState(json: String) {
            val parsed = DebugState.parse(projectId, json)
            _state.value = parsed
            if (parsed.status == DebugStatus.FINISHED) session = null
        }
    }

    fun listener(projectId: String) = Listener(projectId)

    // ---------------------------------------------------------------- driving

    /** continue, step, next, return or stop. Returns an error message, or null. */
    fun command(name: String): String? {
        val s = session ?: return "No debug session is running."
        val reply = JSONObject(bridge.command(s.sessionId, name))
        return if (reply.optBoolean("ok")) null else reply.optString("error")
    }

    /** "type: value" on success, or the error text. */
    fun evaluate(expression: String): Result<String> {
        val s = session ?: return Result.failure(IllegalStateException("No debug session is running."))
        val reply = JSONObject(bridge.evaluate(s.sessionId, expression))
        return if (reply.optBoolean("ok")) Result.success("${reply.optString("type")}: ${reply.optString("value")}")
        else Result.failure(IllegalStateException(reply.optString("error")))
    }

    val isActive: Boolean get() = session != null

    /** Clears a finished run from the screen. */
    fun dismiss() {
        if (_state.value.status == DebugStatus.FINISHED) _state.value = DebugState()
    }

    /**
     * Waits until the program pauses or ends after [since] was the current state, so a caller
     * that just sent a command sees its result. Returns the state at that point, or the latest
     * one after [timeoutMs].
     */
    suspend fun awaitSettled(since: DebugState, timeoutMs: Long): DebugState =
        withTimeoutOrNull(timeoutMs) {
            state.first { it !== since && it.status in SETTLED }
        } ?: state.value

    fun toJson(s: DebugState = state.value): JSONObject {
        fun vars(list: List<DebugVariable>) = JSONArray(list.map {
            JSONObject().put("name", it.name).put("type", it.type).put("value", it.value)
        })
        return JSONObject()
            .put("status", s.status.name.lowercase())
            .put("project_id", s.projectId ?: JSONObject.NULL)
            .apply {
                if (s.status == DebugStatus.PAUSED) {
                    put("file", s.file).put("line", s.line).put("function", s.function).put("reason", s.reason)
                    put("stack", JSONArray(s.stack.map {
                        JSONObject().put("file", it.file).put("line", it.line).put("function", it.function)
                    }))
                    put("locals", vars(s.locals)).put("globals", vars(s.globals))
                }
                s.outcome?.let { put("outcome", it) }
            }
    }

    private companion object {
        val SETTLED = setOf(DebugStatus.PAUSED, DebugStatus.FINISHED, DebugStatus.IDLE)
    }
}
