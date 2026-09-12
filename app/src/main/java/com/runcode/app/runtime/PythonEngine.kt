package com.runcode.app.runtime

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs project scripts on the CPython interpreter that Chaquopy embeds in the APK.
 *
 * Each service gets its own JVM thread; Chaquopy attaches it to the interpreter, so several
 * scripts can be in flight at once under the GIL. Output is streamed line by line from the
 * Python side through [OutputSink] instead of being collected at the end, so a long-running
 * bot shows up in the console while it runs.
 */
class PythonEngine(
    private val context: Context,
    private val secretStore: SecretStore
) : RuntimeEngine {

    override val descriptor = RuntimeDescriptor(
        id = "embedded_python",
        displayName = "Embedded Python Runtime",
        version = "CPython 3.12 (Chaquopy)",
        supportedProfiles = listOf(
            ProjectProfile.PYTHON_SCRIPT,
            ProjectProfile.TELEGRAM_BOT,
            ProjectProfile.PYTHON_HTTP,
            ProjectProfile.SQLITE_APP
        ),
        supportsNetworking = true,
        supportsLongRunning = true
    )

    /** Called from runcode_runner.py to push a line of output into the app's log stream. */
    class OutputSink(private val sink: RuntimeEventSink) {
        @Suppress("unused") // invoked from Python
        fun onOutput(level: String, line: String) {
            sink.onEvent(if (level == "stderr") LogLevel.STDERR else LogLevel.STDOUT, line)
        }
    }

    override suspend fun checkCompatibility(context: Context): CompatibilityReport {
        return if (Python.isStarted()) {
            CompatibilityReport(
                isCompatible = true,
                status = "Available",
                details = "CPython ${pythonVersion()} (Chaquopy) with the standard library and sqlite3"
            )
        } else {
            CompatibilityReport(
                isCompatible = false,
                status = "Unavailable",
                details = "The embedded interpreter failed to start on this device/ABI"
            )
        }
    }

    override suspend fun prepare(project: Project): PrepareResult = withContext(Dispatchers.IO) {
        if (!Python.isStarted()) {
            return@withContext PrepareResult.Failure("Embedded Python interpreter is not available on this device")
        }
        val entry = File(project.projectRoot, "source/${project.entryPoint}")
        if (!entry.exists()) {
            return@withContext PrepareResult.Failure("Entrypoint script '${project.entryPoint}' does not exist")
        }
        PrepareResult.Success
    }

    override suspend fun start(project: Project, sink: RuntimeEventSink): RuntimeHandle = withContext(Dispatchers.IO) {
        val scriptFile = File(project.projectRoot, "source/${project.entryPoint}")
        val workingDir = project.workingDirectory.ifBlank { project.projectRoot }
        val handleServiceId = "svc_py_${project.id}"

        sink.onEvent(LogLevel.SYSTEM, "Initializing Python ${pythonVersion()} for '${project.name}'...")

        val envPairs = project.environment.map { "${it.key}=${secretStore.resolveValue(it.value)}" }
        if (envPairs.isNotEmpty()) {
            sink.onEvent(LogLevel.SYSTEM, "Loaded ${envPairs.size} environment variable(s)")
        }
        sink.onEvent(LogLevel.SYSTEM, "Entrypoint: ${project.entryPoint} (${scriptFile.length()} bytes)")

        val alive = AtomicBoolean(true)
        val reason = AtomicReference(ExitReason.RUNNING)

        val worker = Thread({
            sink.onEvent(LogLevel.SYSTEM, "--- Python Execution Started ---")
            val outcome = try {
                Python.getInstance().getModule(RUNNER_MODULE).callAttr(
                    "run_script",
                    handleServiceId,
                    scriptFile.absolutePath,
                    workingDir,
                    envPairs.toTypedArray(),
                    OutputSink(sink)
                ).toString()
            } catch (e: Throwable) {
                sink.onEvent(LogLevel.STDERR, "${e.javaClass.simpleName}: ${e.message}")
                "failed:${e.javaClass.simpleName}"
            }

            when (outcome) {
                "completed" -> {
                    sink.onEvent(LogLevel.SYSTEM, "--- Python Execution Finished (Exit Code: 0) ---")
                    reason.compareAndSet(ExitReason.RUNNING, ExitReason.COMPLETED)
                }
                "stopped" -> {
                    sink.onEvent(LogLevel.SYSTEM, "Python process stopped.")
                    reason.set(ExitReason.STOPPED)
                }
                else -> {
                    sink.onEvent(LogLevel.ERROR, "--- Python Execution Failed: ${outcome.removePrefix("failed:")} ---")
                    reason.compareAndSet(ExitReason.RUNNING, ExitReason.CRASHED)
                }
            }
            alive.set(false)
        }, "runcode-python-${project.id}")
        worker.isDaemon = true
        worker.start()

        object : RuntimeHandle {
            override val serviceId = handleServiceId
            override val projectId = project.id
            override val isAlive get() = alive.get()
            override val boundPort = project.network.port
            override val exitReason get() = reason.get()

            override suspend fun stop() = withContext(Dispatchers.IO) {
                if (!alive.get()) return@withContext
                sink.onEvent(LogLevel.SYSTEM, "Requesting interpreter shutdown...")
                reason.set(ExitReason.STOPPED)
                try {
                    Python.getInstance().getModule(RUNNER_MODULE).callAttr("request_stop", handleServiceId)
                } catch (e: Exception) {
                    sink.onEvent(LogLevel.WARN, "Could not signal the interpreter: ${e.message}")
                }
                // The trace hook raises at the script's next executed line, so give it a
                // moment before declaring the service gone.
                worker.join(STOP_GRACE_MS)
                alive.set(false)
            }

            override suspend fun forceKill() = stop()

            override suspend fun checkHealth(): RuntimeHealth {
                val running = alive.get()
                return RuntimeHealth(
                    isHealthy = running,
                    cpuPercent = if (running) 2 else 0,
                    memoryMb = usedMemoryMb(),
                    message = if (running) "Interpreter thread active" else "Exited"
                )
            }
        }
    }

    override suspend fun requestStop(handle: RuntimeHandle): StopResult {
        handle.stop()
        return StopResult.Stopped
    }

    override suspend fun forceStop(handle: RuntimeHandle): StopResult {
        handle.forceKill()
        return StopResult.Stopped
    }

    override suspend fun health(handle: RuntimeHandle): RuntimeHealth {
        return handle.checkHealth()
    }

    private fun usedMemoryMb(): Double {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
    }

    companion object {
        private const val RUNNER_MODULE = "runcode_runner"
        private const val STOP_GRACE_MS = 3000L

        /** Starts the interpreter once per process. Safe to call repeatedly. */
        fun ensureStarted(context: Context): Boolean {
            return try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun pythonVersion(): String {
            return try {
                val info: PyObject = Python.getInstance().getModule(RUNNER_MODULE).callAttr("interpreter_info")
                info.toString().substringBefore(' ')
            } catch (_: Throwable) {
                "3.12"
            }
        }
    }
}
