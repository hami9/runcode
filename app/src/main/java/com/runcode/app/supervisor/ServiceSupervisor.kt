package com.runcode.app.supervisor

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.domain.models.RestartPolicy
import com.runcode.app.domain.models.RuntimeInstance
import com.runcode.app.domain.models.ServiceState
import com.runcode.app.logging.LogManager
import com.runcode.app.network.PortManager
import com.runcode.app.runtime.ExitReason
import com.runcode.app.runtime.PrepareResult
import com.runcode.app.runtime.RuntimeEventSink
import com.runcode.app.runtime.RuntimeHandle
import com.runcode.app.runtime.RuntimeRegistry
import com.runcode.app.service.RuntimeForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ServiceSupervisor(
    private val context: Context,
    private val runtimeRegistry: RuntimeRegistry,
    private val logManager: LogManager,
    private val portManager: PortManager
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val mutex = Mutex()

    private val activeHandles = ConcurrentHashMap<String, RuntimeHandle>() // projectId -> handle
    private val activeProfiles = ConcurrentHashMap<String, ProjectProfile>() // projectId -> profile
    private val supervisorJobs = ConcurrentHashMap<String, Job>() // projectId -> supervisor job

    private val _instances = MutableStateFlow<Map<String, RuntimeInstance>>(emptyMap())
    val instances: StateFlow<Map<String, RuntimeInstance>> = _instances.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private val externalHold = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "runcode:ServiceSupervisorWakeLock")
        wakeLock?.setReferenceCounted(false)
    }

    suspend fun startProject(project: Project): Boolean = mutex.withLock {
        val current = _instances.value[project.id]
        if (current?.isRunning == true) {
            logManager.log(project.id, project.name, LogLevel.WARN, "Service is already running.")
            return false
        }

        // Networking profiles may need a different port than the configured one; whatever we
        // settle on has to be the port the engine actually binds.
        var effectiveProject = project
        if (needsPort(project.profile)) {
            val configuredPort = project.network.port
            if (portManager.isPortAvailable(configuredPort)) {
                portManager.reservePort(configuredPort, project.id)
            } else {
                val nextPort = try {
                    portManager.getNextAvailablePort(configuredPort, project.id)
                } catch (_: Exception) {
                    logManager.log(project.id, project.name, LogLevel.ERROR, "Port conflict: $configuredPort is occupied and no free ports found.")
                    return false
                }
                logManager.log(project.id, project.name, LogLevel.WARN, "Port $configuredPort occupied. Auto-assigned available port $nextPort")
                effectiveProject = project.copy(network = project.network.copy(port = nextPort))
            }
        }

        updateInstance(project.id) {
            RuntimeInstance(
                serviceId = "svc_${project.id}",
                projectId = project.id,
                projectName = project.name,
                profile = project.profile,
                state = ServiceState.PREPARING,
                port = effectiveProject.network.port,
                boundAddress = effectiveProject.network.bindAddress
            )
        }

        logManager.log(project.id, project.name, LogLevel.SYSTEM, "Preparing service '${project.name}'...")

        val engine = runtimeRegistry.getEngineForProfile(project.profile)
        val prepResult = engine.prepare(effectiveProject)
        if (prepResult is PrepareResult.Failure) {
            logManager.log(project.id, project.name, LogLevel.ERROR, "Preparation failed: ${prepResult.reason}")
            updateInstance(project.id) { it.copy(state = ServiceState.FAILED, lastError = prepResult.reason) }
            portManager.releaseServicePorts(project.id)
            syncSystemState()
            return false
        }

        updateInstance(project.id) {
            it.copy(state = ServiceState.STARTING, startTime = System.currentTimeMillis())
        }

        val eventSink = object : RuntimeEventSink {
            override fun onEvent(level: LogLevel, message: String) {
                logManager.log(project.id, project.name, level, message)
            }
        }

        try {
            val handle = engine.start(effectiveProject, eventSink)
            activeHandles[project.id] = handle
            activeProfiles[project.id] = project.profile

            updateInstance(project.id) {
                it.copy(
                    state = ServiceState.RUNNING,
                    port = handle.boundPort,
                    lastHealthCheck = System.currentTimeMillis()
                )
            }
            syncSystemState()

            // Watch this service. The original project is passed on purpose so that a restart
            // re-runs port resolution from scratch.
            supervisorJobs[project.id] = scope.launch { superviseLifecycle(project, handle) }

            return true
        } catch (e: Throwable) {
            // Throwable, not Exception: a missing platform class surfaces as NoClassDefFoundError
            // and must not take the whole app down with it.
            logManager.log(project.id, project.name, LogLevel.ERROR, "Startup failed: ${e.javaClass.simpleName}: ${e.message}")
            updateInstance(project.id) { it.copy(state = ServiceState.FAILED, lastError = e.message ?: e.javaClass.simpleName) }
            activeHandles.remove(project.id)
            activeProfiles.remove(project.id)
            portManager.releaseServicePorts(project.id)
            syncSystemState()
            return false
        }
    }

    suspend fun stopProject(projectId: String): Boolean = mutex.withLock {
        val handle = activeHandles[projectId] ?: run {
            updateInstance(projectId) { it.copy(state = ServiceState.STOPPED) }
            syncSystemState()
            return true
        }

        val projectName = _instances.value[projectId]?.projectName ?: projectId
        logManager.log(projectId, projectName, LogLevel.SYSTEM, "Stopping service '$projectName'...")

        updateInstance(projectId) { it.copy(state = ServiceState.STOPPING) }

        supervisorJobs.remove(projectId)?.cancel()

        val profile = activeProfiles[projectId] ?: _instances.value[projectId]?.profile
        if (profile != null) {
            runtimeRegistry.getEngineForProfile(profile).requestStop(handle)
        } else {
            // We no longer know which engine owns this handle; shutting it down directly still
            // beats leaking the runtime.
            handle.stop()
        }

        activeHandles.remove(projectId)
        activeProfiles.remove(projectId)
        portManager.releaseServicePorts(projectId)

        updateInstance(projectId) { it.copy(state = ServiceState.STOPPED, lastError = null) }
        syncSystemState()

        logManager.log(projectId, projectName, LogLevel.SYSTEM, "Service '$projectName' stopped.")
        return true
    }

    suspend fun restartProject(project: Project): Boolean {
        stopProject(project.id)
        delay(300)
        return startProject(project)
    }

    private suspend fun superviseLifecycle(project: Project, handle: RuntimeHandle) {
        var consecutiveCrashes = 0

        while (true) {
            delay(3000)

            if (handle.isAlive) {
                try {
                    val health = handle.checkHealth()
                    updateInstance(project.id) {
                        it.copy(
                            lastHealthCheck = System.currentTimeMillis(),
                            cpuEstimatePercent = health.cpuPercent,
                            memoryEstimateMb = health.memoryMb
                        )
                    }
                } catch (_: Exception) {
                }
                continue
            }

            val instance = _instances.value[project.id] ?: break
            if (instance.state == ServiceState.STOPPING || instance.state == ServiceState.STOPPED) break

            when (handle.exitReason) {
                ExitReason.STOPPED -> break

                ExitReason.COMPLETED -> {
                    // A one-shot script that ran to the end is a success, not a crash.
                    logManager.log(project.id, project.name, LogLevel.SYSTEM, "Service finished successfully.")
                    releaseService(project.id)
                    updateInstance(project.id) { it.copy(state = ServiceState.STOPPED, lastError = null) }
                    syncSystemState()
                    break
                }

                ExitReason.CRASHED, ExitReason.RUNNING -> {
                    logManager.log(project.id, project.name, LogLevel.WARN, "Service process exited unexpectedly.")

                    val shouldRestart = project.restartPolicy != RestartPolicy.NEVER
                    consecutiveCrashes++

                    if (shouldRestart && consecutiveCrashes <= MAX_RESTARTS) {
                        val backoffMs = (consecutiveCrashes * 1500L).coerceAtMost(8000L)
                        logManager.log(
                            project.id,
                            project.name,
                            LogLevel.SYSTEM,
                            "Restart policy '${project.restartPolicy}': restarting in ${backoffMs / 1000}s (attempt $consecutiveCrashes/$MAX_RESTARTS)..."
                        )

                        updateInstance(project.id) {
                            it.copy(state = ServiceState.RESTARTING, restartCount = it.restartCount + 1)
                        }

                        // Hand the old runtime's resources back before the new one claims them.
                        releaseService(project.id)
                        syncSystemState()

                        delay(backoffMs)
                        startProject(project)
                    } else {
                        if (consecutiveCrashes > MAX_RESTARTS) {
                            logManager.log(
                                project.id,
                                project.name,
                                LogLevel.ERROR,
                                "Circuit breaker tripped: service crashed $consecutiveCrashes times in a row. Halting automatic restart."
                            )
                        }
                        releaseService(project.id)
                        updateInstance(project.id) {
                            it.copy(state = ServiceState.FAILED, lastError = "Unexpected termination")
                        }
                        syncSystemState()
                    }
                    break
                }
            }
        }
    }

    private fun releaseService(projectId: String) {
        activeHandles.remove(projectId)
        activeProfiles.remove(projectId)
        portManager.releaseServicePorts(projectId)
    }

    private fun needsPort(profile: ProjectProfile): Boolean {
        return profile == ProjectProfile.STATIC_WEB || profile == ProjectProfile.PYTHON_HTTP
    }

    private fun updateInstance(projectId: String, transform: (RuntimeInstance) -> RuntimeInstance) {
        _instances.update { current ->
            val existing = current[projectId] ?: RuntimeInstance(
                serviceId = "svc_$projectId",
                projectId = projectId,
                projectName = projectId,
                profile = ProjectProfile.PYTHON_SCRIPT
            )
            current + (projectId to transform(existing))
        }
    }

    /**
     * Keeps the process in the foreground for work that is not a supervised service — today
     * that is the MCP bridge, which is useless if Android reclaims the app the moment the
     * user switches away.
     */
    fun setExternalHold(active: Boolean) {
        externalHold.set(active)
        syncSystemState()
    }

    /** Brings the wake lock and the foreground notification back in line with live work. */
    private fun syncSystemState() {
        val runningCount = _instances.value.values.count { it.isRunning }
        val bridgeHeld = externalHold.get()
        syncWakeLock(runningCount > 0 || bridgeHeld)
        syncForegroundService(runningCount, bridgeHeld)
    }

    @Synchronized
    private fun syncWakeLock(shouldHold: Boolean) {
        try {
            val held = wakeLock?.isHeld == true
            if (shouldHold && !held) {
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            } else if (!shouldHold && held) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun syncForegroundService(runningCount: Int, bridgeHeld: Boolean) {
        val intent = Intent(context, RuntimeForegroundService::class.java).apply {
            action = if (runningCount > 0 || bridgeHeld) {
                RuntimeForegroundService.ACTION_START_FOREGROUND
            } else {
                RuntimeForegroundService.ACTION_STOP_FOREGROUND
            }
            putExtra(RuntimeForegroundService.EXTRA_RUNNING_COUNT, runningCount)
            putExtra(RuntimeForegroundService.EXTRA_BRIDGE_ACTIVE, bridgeHeld)
        }
        try {
            // Always the foreground variant: plain startService() is rejected from the
            // background on Android 12+, which used to leave the notification stuck.
            context.startForegroundService(intent)
        } catch (_: Exception) {
        }
    }

    fun isAnyRunning(): Boolean {
        return _instances.value.values.any { it.isRunning }
    }

    suspend fun stopAll() {
        activeHandles.keys().toList().forEach { projectId ->
            stopProject(projectId)
        }
    }

    private companion object {
        const val MAX_RESTARTS = 4
        const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L
    }
}
