package com.runcode.app.runtime

import android.content.Context
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.network.PortManager
import com.runcode.app.system.ProcessMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal HTTP/1.1 file server.
 *
 * Deliberately built on raw sockets: `com.sun.net.httpserver` ships with the JDK but not with
 * the Android platform, so referencing it compiles and then dies with NoClassDefFoundError on
 * device. Every response closes its connection, which keeps the state machine trivial.
 */
class StaticWebEngine(
    private val portManager: PortManager,
    private val monitor: ProcessMonitor
) : RuntimeEngine {

    override val descriptor = RuntimeDescriptor(
        id = "static_web",
        displayName = "Static Web Server",
        version = "1.0.0",
        supportedProfiles = listOf(ProjectProfile.STATIC_WEB),
        supportsNetworking = true,
        supportsLongRunning = true
    )

    override suspend fun checkCompatibility(context: Context): CompatibilityReport {
        return CompatibilityReport(
            isCompatible = true,
            status = "Available",
            details = "Built-in HTTP/1.1 server with static file resolution"
        )
    }

    override suspend fun prepare(project: Project): PrepareResult = withContext(Dispatchers.IO) {
        val sourceDir = File(project.projectRoot, "source")
        val entry = File(sourceDir, project.entryPoint)
        if (!entry.exists()) {
            return@withContext PrepareResult.Failure("Entrypoint file '${project.entryPoint}' not found in source directory")
        }
        PrepareResult.Success
    }

    override suspend fun start(project: Project, sink: RuntimeEventSink): RuntimeHandle = withContext(Dispatchers.IO) {
        val bindHost = if (project.network.allowLan) "0.0.0.0" else "127.0.0.1"
        val port = project.network.port

        sink.onEvent(LogLevel.SYSTEM, "Binding Static Web Server to $bindHost:$port...")

        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(InetAddress.getByName(bindHost), port))

        val sourceDir = File(project.projectRoot, "source").canonicalFile
        val workers = Executors.newFixedThreadPool(4)
        val running = AtomicBoolean(true)
        val acceptTid = java.util.concurrent.atomic.AtomicReference<Long?>(null)
        val requestCount = java.util.concurrent.atomic.AtomicLong(0)
        val lastRequestAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        val acceptThread = Thread({
            acceptTid.set(android.os.Process.myTid().toLong())
            while (running.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break // socket closed during shutdown
                }
                try {
                    requestCount.incrementAndGet()
                    lastRequestAt.set(System.currentTimeMillis())
                    workers.execute { handleConnection(socket, sourceDir, project.entryPoint, sink) }
                } catch (_: Exception) {
                    closeQuietly(socket)
                }
            }
        }, "runcode-http-$port")
        acceptThread.isDaemon = true
        acceptThread.start()

        sink.onEvent(LogLevel.INFO, "Static Web Server running successfully!")
        sink.onEvent(LogLevel.INFO, "Local address: http://127.0.0.1:$port")
        if (project.network.allowLan) {
            sink.onEvent(LogLevel.WARN, "LAN address (exposed): http://${portManager.getLanIp()}:$port")
        }

        object : RuntimeHandle {
            @Volatile
            private var reason = ExitReason.RUNNING

            override val serviceId = "svc_web_${project.id}"
            override val projectId = project.id
            override val isAlive get() = running.get()
            override val boundPort = port
            override val exitReason get() = reason
            override val threadId get() = acceptTid.get()

            override suspend fun stop() = withContext(Dispatchers.IO) {
                if (!running.compareAndSet(true, false)) return@withContext
                sink.onEvent(LogLevel.SYSTEM, "Shutting down HTTP server on port $port...")
                closeQuietly(serverSocket)
                workers.shutdownNow()
                try {
                    workers.awaitTermination(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                reason = ExitReason.STOPPED
                sink.onEvent(LogLevel.SYSTEM, "HTTP server stopped gracefully.")
            }

            override suspend fun forceKill() = stop()

            override suspend fun checkHealth(): RuntimeHealth {
                val alive = running.get()
                val cpu = if (alive) acceptTid.get()?.let { monitor.cpuPercentFor(it) } ?: 0 else 0
                val idleSeconds = (System.currentTimeMillis() - lastRequestAt.get()) / 1000
                return RuntimeHealth(
                    isHealthy = alive,
                    cpuPercent = cpu,
                    memoryMb = monitor.javaHeapMb(),
                    message = if (alive) {
                        "Listening on port $port - ${requestCount.get()} requests, idle ${idleSeconds}s"
                    } else {
                        "Stopped"
                    }
                )
            }
        }
    }

    private fun handleConnection(socket: Socket, sourceDir: File, entryPoint: String, sink: RuntimeEventSink) {
        socket.use { client ->
            client.soTimeout = 15_000
            val output = BufferedOutputStream(client.getOutputStream())

            val request = try {
                readRequest(client.getInputStream())
            } catch (_: Exception) {
                null
            }

            if (request == null) {
                sendStatus(output, 400, "400 Bad Request", withBody = true)
                return
            }

            val (method, rawPath) = request
            if (method != "GET" && method != "HEAD") {
                sendStatus(output, 405, "405 Method Not Allowed", withBody = true)
                sink.onEvent(LogLevel.WARN, "$method /$rawPath -> 405 Method Not Allowed")
                return
            }

            val includeBody = method == "GET"
            val target = resolveTarget(sourceDir, rawPath, entryPoint)

            if (target == null) {
                sendStatus(output, 403, "403 Forbidden", includeBody)
                sink.onEvent(LogLevel.WARN, "$method /$rawPath -> 403 Forbidden")
                return
            }

            if (!target.isFile) {
                sendStatus(output, 404, "404 Not Found", includeBody)
                sink.onEvent(LogLevel.WARN, "$method /$rawPath -> 404 Not Found")
                return
            }

            try {
                writeHeaders(output, 200, getMimeType(target.extension), target.length())
                if (includeBody) {
                    FileInputStream(target).use { it.copyTo(output) }
                }
                output.flush()
                sink.onEvent(LogLevel.INFO, "$method /$rawPath -> 200 OK (${target.length()} bytes)")
            } catch (e: Exception) {
                // Headers are already on the wire, so the only honest move is to drop the
                // connection and record why.
                sink.onEvent(LogLevel.ERROR, "HTTP error while serving /$rawPath: ${e.message}")
            }
        }
    }

    /** Returns the method and the raw (still URL-encoded) path, or null if the request is unusable. */
    private fun readRequest(input: InputStream): Pair<String, String>? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        // Drain the headers so the client does not see a reset before we answer.
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }

        val path = parts[1].substringBefore('?').substringBefore('#')
        return parts[0].uppercase() to path.trimStart('/')
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == LF) return buffer.toString().removeSuffix("\r")
            if (buffer.length > MAX_LINE_LENGTH) return null
            buffer.append(b.toChar())
        }
    }

    /**
     * Maps a request path onto a file inside [sourceDir], falling back to the entrypoint so
     * single-page apps keep working. Returns null when the path escapes the source directory.
     */
    private fun resolveTarget(sourceDir: File, rawPath: String, entryPoint: String): File? {
        val decoded = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (_: Exception) {
            return null
        }

        val requested = if (decoded.isEmpty()) entryPoint else decoded
        val candidate = File(sourceDir, requested).canonicalFile
        if (!isInside(candidate, sourceDir)) return null
        if (candidate.isFile) return candidate

        val fallback = File(sourceDir, entryPoint).canonicalFile
        return if (isInside(fallback, sourceDir)) fallback else null
    }

    private fun isInside(candidate: File, root: File): Boolean {
        return candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
    }

    private fun sendStatus(output: OutputStream, status: Int, message: String, withBody: Boolean) {
        val body = message.toByteArray(Charsets.UTF_8)
        try {
            writeHeaders(output, status, "text/plain; charset=utf-8", body.size.toLong())
            if (withBody) output.write(body)
            output.flush()
        } catch (_: IOException) {
            // client went away
        }
    }

    private fun writeHeaders(output: OutputStream, status: Int, contentType: String, contentLength: Long) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Server: runcode-local/1.0\r\n" +
                "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: Exception) {
        }
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
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

    private companion object {
        const val LF = 10
        const val MAX_LINE_LENGTH = 8192
    }
}
