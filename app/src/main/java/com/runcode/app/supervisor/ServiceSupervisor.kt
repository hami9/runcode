package com.runcode.app.supervisor

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.RestartPolicy
import com.runcode.app.domain.models.RuntimeEvent
import com.runcode.app.domain.models.RuntimeInstance
import com.runcode.app.domain.models.ServiceState
import com.runcode.app.logging.LogManager
import com.runcode.app.network.PortManager
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
    private val supervisorJobs = ConcurrentHashMap<String, Job>() // projectId -> supervisor job

    private val _instances = MutableStateFlow<Map<String, RuntimeInstance>>(emptyMap())
    val instances: StateFlow<Map<String, RuntimeInstance>> = _instances.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRefCount = 0

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

        // Check port availability if networking profile
        if (project.profile.name.contains("HTTP") || project.profile.name.contains("WEB")) {
            val port = project.network.port
            if (!portManager.isPortAvailable(port)) {
                val nextPort = try {
                    portManager.getNextAvailablePort(port, project.id)
                } catch (e: Exception) {
                    logManager.log(project.id, project.name, LogLevel.ERROR, "Port conflict: $port is occupied and no free ports found.")
                    return false
                }
                logManager.log(project.id, project.name, LogLevel.WARN, "Port $port occupied. Auto-assigned available port $nextPort")
            } else {
                portManager.reservePort(port, project.id)
            }
        }

        updateInstance(project.id) {
            RuntimeInstance(
                serviceId = "svc_${project.id}",
                projectId = project.id,
                projectName = project.name,
                profile = project.profile,
                state = ServiceState.PREPARING,
                port = project.network.port,
                boundAddress = project.network.bindAddress
            )
        }

        logManager.log(project.id, project.name, LogLevel.SYSTEM, "Preparing service '${project.name}'...")

        val engine = runtimeRegistry.getEngineForProfile(project.profile)
        val prepResult = engine.prepare(project)
        if (prepResult is PrepareResult.Failure) {
            logManager.log(project.id, project.name, LogLevel.ERROR, "Preparation failed: ${prepResult.reason}")
            updateInstance(project.id) {
                it.copy(state = ServiceState.FAILED, lastError = prepResult.reason)
            }
            portManager.releaseServicePorts(project.id)
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
            val handle = engine.start(project, eventSink)
            activeHandles[project.id] = handle

            acquireWakeLock()
            updateForegroundService()

            updateInstance(project.id) {
                it.copy(
                    state = ServiceState.RUNNING,
                    port = handle.boundPort,
                    lastHealthCheck = System.currentTimeMillis()
                )
            }

            // Launch supervisor watcher job for this service
            val watcherJob = scope.launch {
                superviseLifecycle(project, handle)
            }
            supervisorJobs[project.id] = watcherJob

            return true
        } catch (e: Exception) {
            logManager.log(project.id, project.name, LogLevel.ERROR, "Startup failed: ${e.message}")
            updateInstance(project.id) {
                it.copy(state = ServiceState.FAILED, lastError = e.message)
            }
            portManager.releaseServicePorts(project.id)
            return false
        }
    }

    suspend fun stopProject(projectId: String): Boolean = mutex.withLock {
        val handle = activeHandles[projectId] ?: run {
            updateInstance(projectId) { it.copy(state = ServiceState.STOPPED) }
            return true
        }

        val instance = _instances.value[projectId]
        val projectName = instance?.projectName ?: projectId
        logManager.log(projectId, projectName, LogLevel.SYSTEM, "Stopping service '$projectName'...")

        updateInstance(projectId) { it.copy(state = ServiceState.STOPPING) }

        supervisorJobs[projectId]?.cancel()
        supervisorJobs.remove(projectId)

        val engine = runtimeRegistry.getEngineForProfile(instance?.profile ?: return false)
        engine.requestStop(handle)

        activeHandles.remove(projectId)
        portManager.releaseServicePorts(projectId)

        updateInstance(projectId) {
            it.copy(state = ServiceState.STOPPED, lastError = null)
        }

        releaseWakeLock()
        updateForegroundService()

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
        val windowStart = System.currentTimeMillis()

        while (true) {
            delay(3000)
            if (!handle.isAlive) {
                // Detected termination
                val instance = _instances.value[project.id] ?: break
                if (instance.state == ServiceState.STOPPING || instance.state == ServiceState.STOPPED) {
                    break
                }

                // Unexpected exit
                logManager.log(project.id, project.name, LogLevel.WARN, "Service process exited unexpectedly.")

                val shouldRestart = when (project.restartPolicy) {
                    RestartPolicy.NEVER -> false
                    RestartPolicy.ON_FAILURE, RestartPolicy.ALWAYS -> true
                }

                consecutiveCrashes++
                if (shouldRestart && consecutiveCrashes <= 4) {
                    val backoffMs = (consecutiveCrashes * 1500L).coerceAtMost(8000L)
                    logManager.log(project.id, project.name, LogLevel.SYSTEM, "Restart policy '${project.restartPolicy}': restarting in ${backoffMs / 1000}s (attempt $consecutiveCrashes/4)...")

                    updateInstance(project.id) {
                        it.copy(
                            state = ServiceState.RESTARTING,
                            restartCount = it.restartCount + 1
                        )
                    }

                    delay(backoffMs)
                    startProject(project)
                } else {
                    if (consecutiveCrashes > 4) {
                        logManager.log(project.id, project.name, LogLevel.ERROR, "Circuit breaker tripped: service crashed $consecutiveCrashes times in a row. Halting automatic restart.")
                    }
                    updateInstance(project.id) {
                        it.copy(state = ServiceState.FAILED, lastError = "Unexpected termination")
                    }
                    activeHandles.remove(project.id)
                    portManager.releaseServicePorts(project.id)
                    releaseWakeLock()
                    updateForegroundService()
                }
                break
            } else {
                // Heartbeat health check
                try {
                    val health = handle.checkHealth()
                    updateInstance(project.id) {
                        it.copy(
                            lastHealthCheck = System.currentTimeMillis(),
                            cpuEstimatePercent = health.cpuPercent,
                            memoryEstimateMb = health.memoryMb
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun updateInstance(projectId: String, transform: (RuntimeInstance) -> RuntimeInstance) {
        val currentMap = _instances.value.toMutableMap()
        val existing = currentMap[projectId] ?: RuntimeInstance(
            serviceId = "svc_$projectId",
            projectId = projectId,
            projectName = projectId,
            profile = com.runcode.app.domain.models.ProjectProfile.PYTHON_SCRIPT
        )
        currentMap[projectId] = transform(existing)
        _instances.value = currentMap
    }

    @Synchronized
    private fun acquireWakeLock() {
        wakeLockRefCount++
        if (wakeLockRefCount == 1) {
            try {
                // 1 hour maximum safety timeout
                wakeLock?.acquire(60 * 60 * 1000L)
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLockRefCount = (wakeLockRefCount - 1).coerceAtLeast(0)
        if (wakeLockRefCount == 0) {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateForegroundService() {
        val runningCount = _instances.value.values.count { it.isRunning }
        val intent = Intent(context, RuntimeForegroundService::class.java).apply {
            action = if (runningCount > 0) {
                RuntimeForegroundService.ACTION_START_FOREGROUND
            } else {
                RuntimeForegroundService.ACTION_STOP_FOREGROUND
            }
            putExtra(RuntimeForegroundService.EXTRA_RUNNING_COUNT, runningCount)
        }
        try {
            if (runningCount > 0) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    fun isAnyRunning(): Boolean {
        return _instances.value.values.any { it.isRunning }
    }

    suspend fun stopAll() {
        activeHandles.keys().toList().forEach { projectId ->
            stopProject(projectId)
        }
    }
}
