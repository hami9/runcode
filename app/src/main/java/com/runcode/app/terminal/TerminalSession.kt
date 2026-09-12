package com.runcode.app.terminal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.atomic.AtomicBoolean

data class TerminalLine(
    val id: Long,
    val text: String,
    val isInput: Boolean = false
)

/**
 * An interactive `sh` session running inside the app sandbox.
 *
 * This is a pipe-backed shell, not a PTY: there is no job control, no line editing and no
 * curses support, and it can only touch what the app's own UID can touch. Within those
 * limits it is a real shell — the same one every Android device ships at /system/bin/sh.
 */
class TerminalSession(private val context: Context) {

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _workingDirectory = MutableStateFlow("")
    val workingDirectory: StateFlow<String> = _workingDirectory.asStateFlow()

    private var process: Process? = null
    private var stdin: Writer? = null
    private var pumpThread: Thread? = null
    private val nextId = java.util.concurrent.atomic.AtomicLong(0)
    private val starting = AtomicBoolean(false)

    @Synchronized
    fun start(workingDir: File = context.filesDir, environment: Map<String, String> = emptyMap()) {
        if (_isRunning.value || !starting.compareAndSet(false, true)) return

        try {
            // Deliberately not `sh -i`: interactive mode wants a tty, and without one mksh
            // prints "can't find tty fd" and exits immediately. Reading commands from the
            // pipe keeps the session alive; the UI draws the prompt itself.
            val builder = ProcessBuilder(SHELL)
            builder.directory(if (workingDir.isDirectory) workingDir else context.filesDir)
            builder.redirectErrorStream(true)
            builder.environment().apply {
                put("HOME", context.filesDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("TERM", "dumb")
                putAll(environment)
            }

            val started = builder.start()
            process = started
            stdin = OutputStreamWriter(started.outputStream)
            _workingDirectory.value = builder.directory().absolutePath
            _isRunning.value = true

            append("runcode shell — ${builder.directory().absolutePath}", isInput = false)
            append("App-sandbox shell (no root). Type 'exit' to end the session.", isInput = false)

            pumpThread = Thread({
                try {
                    BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            append(line, isInput = false)
                        }
                    }
                } catch (_: Exception) {
                    // stream closed with the process
                } finally {
                    val code = try {
                        started.waitFor()
                    } catch (_: InterruptedException) {
                        -1
                    }
                    append("[process exited with code $code]", isInput = false)
                    _isRunning.value = false
                }
            }, "runcode-shell-pump").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            append("Could not start $SHELL: ${e.message}", isInput = false)
            _isRunning.value = false
        } finally {
            starting.set(false)
        }
    }

    fun send(command: String) {
        val writer = stdin
        if (writer == null || !_isRunning.value) {
            append("Shell is not running. Start a session first.", isInput = false)
            return
        }
        append("$ $command", isInput = true)
        try {
            writer.write(command)
            writer.write("\n")
            writer.flush()
        } catch (e: Exception) {
            append("Write failed: ${e.message}", isInput = false)
            _isRunning.value = false
        }
    }

    /** Runs a command in a throwaway shell and returns its combined output. */
    fun runOneShot(command: String, workingDir: File, timeoutMs: Long = 30_000): String {
        return try {
            val builder = ProcessBuilder(SHELL, "-c", command)
            builder.directory(if (workingDir.isDirectory) workingDir else context.filesDir)
            builder.redirectErrorStream(true)
            builder.environment().apply {
                put("HOME", context.filesDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
            }
            val proc = builder.start()
            val output = StringBuilder()
            val reader = Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            synchronized(output) {
                                if (output.length < MAX_ONE_SHOT_CHARS) output.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }, "runcode-oneshot-read").apply { isDaemon = true; start() }

            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(500)
                return synchronized(output) { output.toString() } + "\n[timed out after ${timeoutMs}ms]"
            }
            reader.join(1000)
            val exit = proc.exitValue()
            val text = synchronized(output) { output.toString() }
            if (exit == 0) text else text + "\n[exit code $exit]"
        } catch (e: Exception) {
            "Failed to run command: ${e.message}"
        }
    }

    @Synchronized
    fun stop() {
        try {
            stdin?.close()
        } catch (_: Exception) {
        }
        process?.destroy()
        pumpThread?.interrupt()
        process = null
        stdin = null
        _isRunning.value = false
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private fun append(text: String, isInput: Boolean) {
        _lines.update { current ->
            val next = current + TerminalLine(nextId.incrementAndGet(), text, isInput)
            if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
        }
    }

    private companion object {
        const val SHELL = "/system/bin/sh"
        const val MAX_LINES = 800
        const val MAX_ONE_SHOT_CHARS = 200_000
    }
}
