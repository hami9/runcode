package com.runcode.app.supervisor

import android.app.Application
import android.content.Context
import com.runcode.app.domain.models.*
import com.runcode.app.logging.LogManager
import com.runcode.app.network.PortManager
import com.runcode.app.runtime.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ServiceSupervisorTest {
    private val project = Project("test", "Test", "", ProjectProfile.PYTHON_SCRIPT, "/test", "main.py")

    private class Handle(
        var alive: Boolean = false,
        var reason: ExitReason = ExitReason.CRASHED,
        override val restartable: Boolean = true
    ) : RuntimeHandle {
        override val serviceId = "test"
        override val projectId = "test"
        override val isAlive get() = alive
        override val boundPort = 8080
        override val exitReason get() = reason
        override val threadId: Long? = null
        override suspend fun stop() { reason = ExitReason.STOPPED }
        override suspend fun forceKill() = stop()
        override suspend fun checkHealth() = RuntimeHealth(alive, 0, 0.0, "test")
    }

    private class Engine(val handle: Handle) : RuntimeEngine {
        var starts = 0
        override val descriptor = RuntimeDescriptor("test", "Test", "1", listOf(ProjectProfile.PYTHON_SCRIPT), false, true)
        override suspend fun checkCompatibility(context: Context) = CompatibilityReport(true, "ok", "")
        override suspend fun prepare(project: Project) = PrepareResult.Success
        override suspend fun start(project: Project, sink: RuntimeEventSink): RuntimeHandle { starts++; return handle }
        override suspend fun requestStop(handle: RuntimeHandle): StopResult { handle.stop(); return StopResult.Stopped }
        override suspend fun forceStop(handle: RuntimeHandle) = requestStop(handle)
        override suspend fun health(handle: RuntimeHandle) = handle.checkHealth()
    }

    private fun TestScope.supervisor(engine: Engine): ServiceSupervisor {
        val context = RuntimeEnvironment.getApplication()
        return ServiceSupervisor(context, RuntimeRegistry(engine, engine), LogManager(context), PortManager(), backgroundScope)
    }

    @Test fun `a crashed debug run is not restarted, even with ALWAYS`() = runTest {
        val engine = Engine(Handle(reason = ExitReason.CRASHED, restartable = false))
        val supervisor = supervisor(engine)
        supervisor.startProject(project.copy(restartPolicy = RestartPolicy.ALWAYS))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, engine.starts)
        assertEquals(ServiceState.FAILED, supervisor.instances.value[project.id]?.state)
    }

    @Test fun `a finished debug run stops cleanly`() = runTest {
        val engine = Engine(Handle(reason = ExitReason.COMPLETED, restartable = false))
        val supervisor = supervisor(engine)
        supervisor.startProject(project.copy(restartPolicy = RestartPolicy.ALWAYS))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, engine.starts)
        assertEquals(ServiceState.STOPPED, supervisor.instances.value[project.id]?.state)
    }

    @Test fun `crashing services stop after four automatic retries`() = runTest {
        val engine = Engine(Handle())
        val supervisor = supervisor(engine)
        supervisor.startProject(project)
        assertFalse(supervisor.startProject(project))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(5, engine.starts)
        assertEquals(ServiceState.FAILED, supervisor.instances.value[project.id]?.state)
        assertEquals(4, supervisor.instances.value[project.id]?.restartCount)
    }

    @Test fun `stop during backoff cancels the pending restart`() = runTest {
        val engine = Engine(Handle())
        val supervisor = supervisor(engine)
        supervisor.startProject(project)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(ServiceState.RESTARTING, supervisor.instances.value[project.id]?.state)
        assertTrue(supervisor.stopProject(project.id))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, engine.starts)
        assertEquals(ServiceState.STOPPED, supervisor.instances.value[project.id]?.state)
    }

    @Test fun `blocked workers stay owned until they actually exit`() = runTest {
        val handle = Handle(alive = true, reason = ExitReason.RUNNING)
        val engine = Engine(handle)
        val supervisor = supervisor(engine)
        supervisor.startProject(project)
        assertFalse(supervisor.stopProject(project.id))
        assertFalse(supervisor.startProject(project))
        assertTrue(supervisor.hasActiveWork(project.id))
        assertEquals(ServiceState.STOPPING, supervisor.instances.value[project.id]?.state)
        handle.alive = false
        advanceTimeBy(500)
        runCurrent()
        assertEquals(ServiceState.STOPPED, supervisor.instances.value[project.id]?.state)
        assertEquals(1, engine.starts)
    }

    @Test fun `stop all includes services waiting to restart`() = runTest {
        val engine = Engine(Handle())
        val supervisor = supervisor(engine)
        supervisor.startProject(project)
        advanceTimeBy(3_000)
        runCurrent()
        supervisor.stopAll()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, engine.starts)
        assertEquals(ServiceState.STOPPED, supervisor.instances.value[project.id]?.state)
    }

    @Test fun `always restarts successful scripts but on failure does not`() = runTest {
        val engine = Engine(Handle(reason = ExitReason.COMPLETED))
        val supervisor = supervisor(engine)
        supervisor.startProject(project)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, engine.starts)
        supervisor.startProject(project.copy(restartPolicy = RestartPolicy.ALWAYS))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, engine.starts)
    }
}
