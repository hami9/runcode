package com.runcode.app.runtime

import android.content.Context
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile

data class RuntimeDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val supportedProfiles: List<ProjectProfile>,
    val supportsNetworking: Boolean,
    val supportsLongRunning: Boolean
)

data class CompatibilityReport(
    val isCompatible: Boolean,
    val status: String,
    val details: String
)

sealed class PrepareResult {
    data object Success : PrepareResult()
    data class Failure(val reason: String) : PrepareResult()
}

sealed class StopResult {
    data object Stopped : StopResult()
    data class Failed(val error: String) : StopResult()
}

data class RuntimeHealth(
    val isHealthy: Boolean,
    val cpuPercent: Int,
    val memoryMb: Double,
    val message: String
)

interface RuntimeEventSink {
    fun onEvent(level: LogLevel, message: String)
}

/**
 * Why a runtime is no longer alive. The supervisor uses this to tell a script that ran to
 * completion apart from one that died, so a successful one-shot run is not reported as a crash.
 */
enum class ExitReason {
    /** Still running. */
    RUNNING,
    /** The workload finished on its own without error. */
    COMPLETED,
    /** The workload threw, or the runtime died unexpectedly. */
    CRASHED,
    /**
     * The workload cannot start at all: a syntax error, a missing module. Restarting it
     * would fail identically every time, so the supervisor must not retry.
     */
    FATAL,
    /** Shut down on request. */
    STOPPED
}

interface RuntimeHandle {
    val serviceId: String
    val projectId: String
    val isAlive: Boolean
    val boundPort: Int
    val exitReason: ExitReason
    suspend fun stop()
    suspend fun forceKill()
    suspend fun checkHealth(): RuntimeHealth
}

interface RuntimeEngine {
    val descriptor: RuntimeDescriptor
    suspend fun checkCompatibility(context: Context): CompatibilityReport
    suspend fun prepare(project: Project): PrepareResult
    suspend fun start(project: Project, sink: RuntimeEventSink): RuntimeHandle
    suspend fun requestStop(handle: RuntimeHandle): StopResult
    suspend fun forceStop(handle: RuntimeHandle): StopResult
    suspend fun health(handle: RuntimeHandle): RuntimeHealth
}
