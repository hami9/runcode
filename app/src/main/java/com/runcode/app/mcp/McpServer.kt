package com.runcode.app.mcp

import com.runcode.app.domain.models.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class McpServerState(
    val isRunning: Boolean = false,
    val port: Int = 0,
    val allowLan: Boolean = false,
    val boundAddress: String = "127.0.0.1",
    val lastError: String? = null,
    val requestCount: Int = 0,
    val lastRequest: String? = null
)

/**
 * Model Context Protocol endpoint for this device.
 *
 * Speaks JSON-RPC 2.0 over HTTP POST /mcp, which is what MCP's Streamable HTTP transport
 * expects for request/response traffic. Notifications get an empty 202, matching the spec.
 *
 * Security posture, because this hands an AI client real control of the device's projects,
 * shell and database:
 *  - a bearer token is ALWAYS required, including on loopback;
 *  - the listener binds 127.0.0.1 unless LAN exposure is explicitly switched on;
 *  - every accepted call is counted and surfaced in the UI so exposure stays visible.
 */
class McpServer(
    private val tools: McpToolHost,
    private val onLog: (LogLevel, String) -> Unit
) {

    private val _state = MutableStateFlow(McpServerState())
    val state: StateFlow<McpServerState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var workers: java.util.concurrent.ExecutorService? = null
    private val running = AtomicBoolean(false)

    @Synchronized
    fun start(port: Int, token: String, allowLan: Boolean) {
        if (running.get()) return
        if (token.isBlank()) {
            _state.value = _state.value.copy(lastError = "Refusing to start without an access token")
            onLog(LogLevel.ERROR, "[MCP] Refusing to start without an access token.")
            return
        }

        val host = if (allowLan) "0.0.0.0" else "127.0.0.1"
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(host), port))
            serverSocket = socket

            val pool = Executors.newFixedThreadPool(4)
            workers = pool
            running.set(true)

            _state.value = McpServerState(
                isRunning = true,
                port = port,
                allowLan = allowLan,
                boundAddress = host
            )

            Thread({
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    try {
                        pool.execute { handle(client, token) }
                    } catch (_: Exception) {
                        closeQuietly(client)
                    }
                }
            }, "runcode-mcp-$port").apply { isDaemon = true; start() }

            onLog(LogLevel.SYSTEM, "[MCP] Bridge listening on http://$host:$port/mcp")
            if (allowLan) {
                onLog(LogLevel.WARN, "[MCP] LAN exposure is ON — anyone on this network with the token can drive this device.")
            }
        } catch (e: Exception) {
            running.set(false)
            _state.value = McpServerState(lastError = "${e.javaClass.simpleName}: ${e.message}")
            onLog(LogLevel.ERROR, "[MCP] Could not start: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeQuietly(serverSocket)
        workers?.shutdownNow()
        try {
            workers?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        serverSocket = null
        workers = null
        _state.value = _state.value.copy(isRunning = false, boundAddress = "127.0.0.1")
        onLog(LogLevel.SYSTEM, "[MCP] Bridge stopped.")
    }

    private fun handle(socket: Socket, token: String) {
        socket.use { client ->
            client.soTimeout = 20_000
            val output = BufferedOutputStream(client.getOutputStream())
            val input = client.getInputStream()

            val requestLine = readLine(input)
            if (requestLine == null) {
                respond(output, 400, jsonError("Malformed request"))
                return
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(output, 400, jsonError("Malformed request line"))
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            var authorization: String? = null
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                val name = header.substringBefore(':').trim().lowercase()
                val value = header.substringAfter(':').trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "authorization" -> authorization = value
                }
            }

            // Unauthenticated liveness probe: deliberately says nothing about the device.
            if (method == "GET" && path == "/health") {
                respond(output, 200, JSONObject().put("status", "ok").put("service", "runcode-mcp").toString())
                return
            }

            if (path != "/mcp") {
                respond(output, 404, jsonError("Unknown endpoint. Use POST /mcp"))
                return
            }

            val presented = authorization?.removePrefix("Bearer ")?.trim()
            if (presented == null || !constantTimeEquals(presented, token)) {
                onLog(LogLevel.WARN, "[MCP] Rejected unauthenticated request from ${client.inetAddress?.hostAddress}")
                respond(output, 401, jsonError("Missing or invalid bearer token"), extraHeaders = "WWW-Authenticate: Bearer\r\n")
                return
            }

            if (method != "POST") {
                respond(output, 405, jsonError("Use POST for JSON-RPC"))
                return
            }

            val body = readBody(input, contentLength)
            val response = try {
                dispatch(JSONObject(body))
            } catch (e: Exception) {
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", JSONObject.NULL)
                    .put("error", JSONObject().put("code", -32700).put("message", "Parse error: ${e.message}"))
            }

            if (response == null) {
                // Notification: acknowledged, no body.
                respond(output, 202, "")
            } else {
                respond(output, 200, response.toString())
            }
        }
    }

    /** Returns null for notifications, which carry no reply. */
    private fun dispatch(request: JSONObject): JSONObject? {
        val method = request.optString("method")
        val id = if (request.has("id")) request.get("id") else null
        val params = request.optJSONObject("params") ?: JSONObject()

        bumpCounter(method)

        if (id == null) return null // notification

        return try {
            val result: JSONObject = when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put(
                        "serverInfo",
                        JSONObject().put("name", "runcode").put("version", "1.0.0")
                    )
                    .put("instructions", INSTRUCTIONS)

                "ping" -> JSONObject()

                "tools/list" -> JSONObject().put("tools", McpTools.descriptors())

                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    McpTools.call(tools, name, args)
                }

                else -> return errorResponse(id, -32601, "Method not found: $method")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (e: Exception) {
            errorResponse(id, -32603, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun errorResponse(id: Any, code: Int, message: String): JSONObject {
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
    }

    private fun bumpCounter(method: String) {
        _state.value = _state.value.copy(
            requestCount = _state.value.requestCount + 1,
            lastRequest = method
        )
    }

    private fun readBody(input: InputStream, contentLength: Int): String {
        if (contentLength <= 0) return "{}"
        val capped = contentLength.coerceAtMost(MAX_BODY_BYTES)
        val buffer = ByteArray(capped)
        var read = 0
        while (read < capped) {
            val n = input.read(buffer, read, capped - read)
            if (n == -1) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (builder.isEmpty()) null else builder.toString()
            if (b == 10) return builder.toString().removeSuffix("\r")
            if (builder.length > MAX_LINE) return null
            builder.append(b.toChar())
        }
    }

    private fun respond(output: OutputStream, status: Int, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                extraHeaders +
                "\r\n"
        try {
            output.write(head.toByteArray(Charsets.US_ASCII))
            if (bytes.isNotEmpty()) output.write(bytes)
            output.flush()
        } catch (_: IOException) {
        }
    }

    private fun jsonError(message: String): String =
        JSONObject().put("error", message).toString()

    /** Length-independent comparison so a wrong token cannot be probed byte by byte. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        var diff = left.size xor right.size
        for (i in left.indices) {
            diff = diff or (left[i].toInt() xor right[i % right.size.coerceAtLeast(1)].toInt())
        }
        return diff == 0
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        const val MAX_LINE = 8192
        const val INSTRUCTIONS =
            "runcode is a local developer runtime on an Android device. Use these tools to " +
                "inspect and edit projects, start and stop supervised services, run shell " +
                "commands and Python, and query project SQLite databases. Everything acts on " +
                "the device's own app sandbox."
    }
}
