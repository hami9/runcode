package com.runcode.app.runtime

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.concurrent.thread

private class FakeBridge : DebugBridge {
    val calls = mutableListOf<List<String>>()
    var reply = """{"ok": true}"""
    override fun command(sessionId: String, name: String): String { calls += listOf("command", sessionId, name); return reply }
    override fun evaluate(sessionId: String, expression: String): String {
        calls += listOf("evaluate", sessionId, expression)
        return """{"ok": true, "type": "int", "value": "42"}"""
    }
    override fun setBreakpoints(sessionId: String, file: String, linesJson: String): String {
        calls += listOf("breakpoints", sessionId, file, linesJson); return reply
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PythonDebuggerTest {

    private val bridge = FakeBridge()
    private val debugger = PythonDebugger(bridge)
    private val source = File("/data/projects/p1/source")

    private val paused = JSONObject()
        .put("status", "paused").put("reason", "breakpoint")
        .put("file", "helper.py").put("line", 3).put("function", "double")
        .put("stack", JSONArray().put(JSONObject().put("file", "helper.py").put("line", 3).put("function", "double"))
            .put(JSONObject().put("file", "main.py").put("line", 5).put("function", "<module>")))
        .put("locals", JSONArray().put(JSONObject().put("name", "x").put("type", "int").put("value", "2")))
        .put("globals", JSONArray())
        .toString()

    @Test fun `paused state is parsed with stack and variables`() {
        val state = DebugState.parse("p1", paused)
        assertEquals(DebugStatus.PAUSED, state.status)
        assertEquals(Triple("helper.py", 3, "double"), Triple(state.file, state.line, state.function))
        assertEquals(listOf("double", "<module>"), state.stack.map { it.function })
        assertEquals(DebugVariable("x", "int", "2"), state.locals.single())
        assertEquals("completed", DebugState.parse("p1", """{"status":"finished","outcome":"completed"}""").outcome)
    }

    @Test fun `only an armed start becomes a debug run, with absolute breakpoint paths`() {
        assertNull(debugger.takeLaunch("p1", "svc_py_p1", source))
        debugger.toggleBreakpoint("p1", "main.py", 6)
        debugger.toggleBreakpoint("p1", "lib/util.py", 2)
        debugger.arm("p1")
        assertEquals(DebugStatus.STARTING, debugger.state.value.status)
        val launch = JSONObject(debugger.takeLaunch("p1", "svc_py_p1", source)!!)
        assertEquals("[6]", launch.getJSONArray(File(source, "main.py").absolutePath).toString())
        assertEquals("[2]", launch.getJSONArray(File(source, "lib/util.py").absolutePath).toString())
        // Consumed: the next start is a normal run again.
        assertNull(debugger.takeLaunch("p1", "svc_py_p1", source))
    }

    @Test fun `disarming after a failed start clears the starting state`() {
        debugger.arm("p1")
        debugger.disarm("p1")
        assertEquals(DebugStatus.IDLE, debugger.state.value.status)
        assertNull(debugger.takeLaunch("p1", "svc_py_p1", source))
    }

    @Test fun `toggling twice removes the breakpoint and live sessions are updated`() {
        debugger.toggleBreakpoint("p1", "main.py", 6)
        assertEquals(setOf(6), debugger.breakpointsFor("p1", "main.py"))
        assertTrue("no session yet, so nothing is sent", bridge.calls.isEmpty())

        debugger.arm("p1")
        debugger.takeLaunch("p1", "svc_py_p1", source)
        debugger.toggleBreakpoint("p1", "main.py", 9)
        assertEquals(listOf("breakpoints", "svc_py_p1", File(source, "main.py").absolutePath, "[6,9]"), bridge.calls.last())

        debugger.toggleBreakpoint("p1", "main.py", 6)
        debugger.toggleBreakpoint("p1", "main.py", 9)
        assertTrue(debugger.breakpointsFor("p1", "main.py").isEmpty())
        assertTrue(debugger.breakpoints.value.isEmpty())
    }

    @Test fun `commands go to the running session and errors come back as text`() {
        assertEquals("No debug session is running.", debugger.command("continue"))
        debugger.arm("p1")
        debugger.takeLaunch("p1", "svc_py_p1", source)
        assertNull(debugger.command("next"))
        assertEquals(listOf("command", "svc_py_p1", "next"), bridge.calls.last())
        bridge.reply = """{"ok": false, "error": "The program is running, not paused."}"""
        assertEquals("The program is running, not paused.", debugger.command("step"))
        assertEquals("int: 42", debugger.evaluate("6 * 7").getOrThrow())
    }

    @Test fun `listener updates state and the end closes the session`() {
        debugger.arm("p1")
        debugger.takeLaunch("p1", "svc_py_p1", source)
        val listener = debugger.listener("p1")
        listener.onState(paused)
        assertEquals(3, debugger.state.value.line)
        listener.onState("""{"status":"finished","outcome":"stopped"}""")
        assertFalse(debugger.isActive)
        debugger.dismiss()
        assertEquals(DebugStatus.IDLE, debugger.state.value.status)
    }

    @Test fun `awaitSettled returns the next pause after a command`() = runBlocking {
        debugger.arm("p1")
        debugger.takeLaunch("p1", "svc_py_p1", source)
        val listener = debugger.listener("p1")
        listener.onState(paused)
        val before = debugger.state.value
        thread {
            Thread.sleep(100)
            listener.onState("""{"status":"running"}""")
            Thread.sleep(100)
            listener.onState(paused.replace("\"line\":3", "\"line\":4"))
        }
        val next = debugger.awaitSettled(before, 5_000)
        assertEquals(DebugStatus.PAUSED, next.status)
        assertNotSame(before, next)
        assertEquals("paused", debugger.toJson(next).getString("status"))
    }

    /** The JSON the real runcode_debugger.py publishes, parsed by the Kotlin side. */
    @Test fun `parses what the python debugger actually sends`() {
        val python = listOfNotNull(System.getenv("RUNCODE_TEST_PYTHON"), System.getenv("RUNCODE_BUILD_PYTHON"), "python3")
            .firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) }
        org.junit.Assume.assumeTrue("No Python 3 on this machine", python != null)

        val dir = kotlin.io.path.createTempDirectory("dbg").toFile()
        val sourceDir = File(dir, "source").apply { mkdirs() }
        File(sourceDir, "main.py").writeText("def area(w, h):\n    result = w * h\n    return result\n\nprint(area(3, 4))\n")
        val driver = """
            import json, sys, threading
            sys.path.insert(0, sys.argv[1])
            import runcode_debugger as d
            class Sink:
                def onOutput(self, level, line): pass
            # The runner routes print() on the script thread into the project log, so write to
            # the real stdout directly.
            def emit(raw):
                sys.__stdout__.write("STATE " + raw + "\n")
                sys.__stdout__.flush()
            class Listener:
                def onState(self, raw):
                    state = json.loads(raw)
                    if state["status"] == "paused":
                        emit(raw)
                        threading.Thread(target=lambda: d.command("svc", "continue")).start()
                    elif state["status"] == "finished":
                        emit(raw)
            script = sys.argv[2] + "/source/main.py"
            d.debug_script("svc", script, sys.argv[2], [], Sink(), Listener(), json.dumps({script: [3]}))
        """.trimIndent()
        val process = ProcessBuilder(python!!, "-c", driver, File("src/main/python").absolutePath, dir.absolutePath)
            .redirectErrorStream(true).start()
        val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("debug driver did not finish: $output", finished)
        val states = output.lines().filter { it.startsWith("STATE ") }.map { DebugState.parse("p1", it.removePrefix("STATE ")) }
        assertEquals(output, 2, states.size)

        val pausedState = states[0]
        assertEquals(DebugStatus.PAUSED, pausedState.status)
        assertEquals(Triple("main.py", 3, "area"), Triple(pausedState.file, pausedState.line, pausedState.function))
        assertEquals("breakpoint", pausedState.reason)
        assertEquals(listOf("area", "<module>"), pausedState.stack.map { it.function })
        assertEquals(mapOf("w" to "3", "h" to "4", "result" to "12"), pausedState.locals.associate { it.name to it.value })
        assertEquals(DebugStatus.FINISHED, states[1].status)
        assertEquals("completed", states[1].outcome)
        dir.deleteRecursively()
    }
}
