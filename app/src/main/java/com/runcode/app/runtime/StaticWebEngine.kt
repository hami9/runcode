package com.runcode.app.runtime

import android.content.Context
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.network.PortManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class StaticWebEngine(private val portManager: PortManager) : RuntimeEngine {

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
            details = "Built-in Android HTTP server with static file resolution"
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
        val address = InetSocketAddress(bindHost, port)
        val server = HttpServer.create(address, 0)
        val sourceDir = File(project.projectRoot, "source")
        val executor = Executors.newFixedThreadPool(2)

        server.createContext("/", object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                try {
                    val rawPath = exchange.requestURI.path.trimStart('/')
                    val targetName = if (rawPath.isEmpty()) project.entryPoint else rawPath
                    val file = File(sourceDir, targetName).canonicalFile

                    // Prevent directory escape
                    if (!file.path.startsWith(sourceDir.canonicalPath)) {
                        val response = "403 Forbidden".toByteArray()
                        exchange.sendResponseHeaders(403, response.size.toLong())
                        exchange.responseBody.use { it.write(response) }
                        sink.onEvent(LogLevel.WARN, "${exchange.requestMethod} /$rawPath -> 403 Forbidden")
                        return
                    }

                    if (!file.exists() || file.isDirectory) {
                        // Fallback to entrypoint for single-page apps
                        val fallback = File(sourceDir, project.entryPoint)
                        if (fallback.exists()) {
                            serveFile(exchange, fallback, sink, rawPath)
                        } else {
                            val response = "404 Not Found".toByteArray()
                            exchange.sendResponseHeaders(404, response.size.toLong())
                            exchange.responseBody.use { it.write(response) }
                            sink.onEvent(LogLevel.WARN, "${exchange.requestMethod} /$rawPath -> 404 Not Found")
                        }
                    } else {
                        serveFile(exchange, file, sink, rawPath)
                    }
                } catch (e: Exception) {
                    sink.onEvent(LogLevel.ERROR, "HTTP Error: ${e.message}")
                    val err = "500 Internal Server Error".toByteArray()
                    exchange.sendResponseHeaders(500, err.size.toLong())
                    exchange.responseBody.use { it.write(err) }
                }
            }
        })

        server.executor = executor
        server.start()

        val lanIp = portManager.getLanIp()
        sink.onEvent(LogLevel.INFO, "Static Web Server running successfully!")
        sink.onEvent(LogLevel.INFO, "Local address: http://127.0.0.1:$port")
        if (project.network.allowLan) {
            sink.onEvent(LogLevel.WARN, "LAN address (exposed): http://$lanIp:$port")
        }

        object : RuntimeHandle {
            private var alive = true
            override val serviceId = "svc_web_${project.id}"
            override val projectId = project.id
            override val isAlive get() = alive
            override val boundPort = port

            override suspend fun stop() = withContext(Dispatchers.IO) {
                if (!alive) return@withContext
                sink.onEvent(LogLevel.SYSTEM, "Shutting down HTTP server on port $port...")
                server.stop(1)
                executor.shutdownNow()
                alive = false
                sink.onEvent(LogLevel.SYSTEM, "HTTP server stopped gracefully.")
            }

            override suspend fun forceKill() = stop()

            override suspend fun checkHealth(): RuntimeHealth {
                return RuntimeHealth(
                    isHealthy = alive,
                    cpuPercent = if (alive) 1 else 0,
                    memoryMb = 4.2,
                    message = if (alive) "Listening on port $port" else "Stopped"
                )
            }
        }
    }

    private fun serveFile(exchange: HttpExchange, file: File, sink: RuntimeEventSink, rawPath: String) {
        val mime = getMimeType(file.extension)
        exchange.responseHeaders.set("Content-Type", mime)
        exchange.responseHeaders.set("Server", "runcode-local/1.0")
        exchange.sendResponseHeaders(200, file.length())
        FileInputStream(file).use { fis ->
            exchange.responseBody.use { os ->
                fis.copyTo(os)
            }
        }
        sink.onEvent(LogLevel.INFO, "${exchange.requestMethod} /$rawPath -> 200 OK (${file.length()} bytes)")
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
}
