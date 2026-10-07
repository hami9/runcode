package com.runcode.app.diagnostics

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Outcome of a probe: ok plus what was observed, or why it failed. */
data class ProbeResult(val ok: Boolean, val detail: String, val millis: Long)

/** Real network probes. Each one has a hard deadline so a report never hangs. */
object NetworkProbes {

    /** Does something accept TCP connections on host:port? */
    fun tcpAccepts(host: String, port: Int, timeoutMs: Int = 2_000): ProbeResult = timed {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        "accepts connections"
    }

    /** DNS lookup with a deadline; InetAddress has no timeout of its own. */
    fun resolves(host: String, timeoutMs: Long = 5_000): ProbeResult = timed {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "runcode-dns").apply { isDaemon = true } }
        try {
            val addresses = pool.submit(Callable { InetAddress.getAllByName(host) })
                .get(timeoutMs, TimeUnit.MILLISECONDS)
            addresses.joinToString(", ") { it.hostAddress ?: "?" }.take(80)
        } catch (_: TimeoutException) {
            throw IllegalStateException("no answer within ${timeoutMs / 1000}s")
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Any HTTP response at all proves the host is reachable: DNS, TCP, TLS and HTTP all worked.
     * Redirects are not followed, so a 301 counts as reachable instead of chasing it.
     */
    fun httpReachable(url: String, timeoutMs: Int = 6_000): ProbeResult = timed {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "runcode-diagnostics")
            "HTTP ${connection.responseCode}"
        } finally {
            connection.disconnect()
        }
    }

    /** Self-test of the MCP bridge's unauthenticated health route. */
    fun mcpHealth(port: Int, timeoutMs: Int = 3_000): ProbeResult {
        val result = httpBody("http://127.0.0.1:$port/health", timeoutMs)
        if (!result.ok) return result
        return if (result.detail.contains("runcode-mcp")) {
            result.copy(detail = "GET /health answered")
        } else {
            result.copy(ok = false, detail = "unexpected answer: ${result.detail.take(80)}")
        }
    }

    private fun httpBody(url: String, timeoutMs: Int): ProbeResult = timed {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            val code = connection.responseCode
            check(code == 200) { "HTTP $code" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private inline fun timed(block: () -> String): ProbeResult {
        val start = System.nanoTime()
        return try {
            val detail = block()
            ProbeResult(true, detail, elapsed(start))
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            ProbeResult(false, reason, elapsed(start))
        }
    }

    private fun elapsed(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
}
