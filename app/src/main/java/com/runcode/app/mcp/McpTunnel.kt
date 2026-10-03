package com.runcode.app.mcp

import com.runcode.app.domain.models.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/** An SSH relay that hands out a public HTTPS URL for a reverse-forwarded port. */
data class TunnelProvider(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    /** Port requested with tcpip-forward. 0 lets the relay choose. */
    val remotePort: Int,
    /** The relay's own sites, which its banner links to. Never the tunnel URL. */
    val siteDomains: List<String>
) {
    companion object {
        // SSH on 443 gets through networks that only allow web traffic.
        val PINGGY = TunnelProvider("Pinggy", "free.pinggy.io", 443, "runcode", 0, listOf("pinggy.io"))
        val LOCALHOST_RUN = TunnelProvider("localhost.run", "localhost.run", 22, "nokey", 80, listOf("localhost.run"))
        val DEFAULTS = listOf(PINGGY, LOCALHOST_RUN)
    }
}

enum class TunnelStatus { OFF, CONNECTING, ONLINE, RETRYING }

data class McpTunnelState(
    val status: TunnelStatus = TunnelStatus.OFF,
    /** Public base URL, without the /mcp path. */
    val url: String? = null,
    val provider: String? = null,
    val lastError: String? = null,
    val failures: Int = 0,
    val retryInSeconds: Int = 0
)

interface TunnelConnection : Closeable {
    val isAlive: Boolean
}

class OpenTunnel(val connection: TunnelConnection, val url: String)

fun interface TunnelConnector {
    /** Opens the tunnel and returns once the relay has announced its public URL. */
    fun open(provider: TunnelProvider, localPort: Int, timeoutMs: Int): OpenTunnel
}

object TunnelUrls {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val HTTPS = Regex("https://([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)/?(?=[\\s,;)\"'<>]|$)")

    /** First bare https://host in the relay's output that is not one of its own sites. */
    fun find(output: String, provider: TunnelProvider): String? {
        val clean = ANSI.replace(output, "")
        return HTTPS.findAll(clean)
            .map { it.groupValues[1].lowercase() }
            .firstOrNull { host -> provider.siteDomains.none { host == it || host.endsWith(".$it") } }
            ?.let { "https://$it" }
    }
}

/**
 * Keeps the MCP bridge reachable from the internet through an outbound SSH reverse tunnel.
 *
 * The connection starts on the device, so it works behind NAT, carrier networks and VPNs:
 * with a VPN up, Android routes it through the VPN like any other app traffic. Relays are
 * tried in turn; a dropped tunnel reconnects with backoff, and a network change (Wi-Fi to
 * mobile data, a VPN reconnecting) reconnects straight away. Free relays hand out a new URL
 * on every connection.
 */
class McpTunnel(
    private val connector: TunnelConnector,
    private val onLog: (LogLevel, String) -> Unit,
    private val providers: List<TunnelProvider> = TunnelProvider.DEFAULTS,
    private val connectTimeoutMs: Int = 20_000,
    private val pollMs: Long = 1_000,
    private val backoffMs: (Int) -> Long = { failures -> minOf(30_000L, 2_000L shl minOf(failures - 1, 4)) }
) {

    private val _state = MutableStateFlow(McpTunnelState())
    val state: StateFlow<McpTunnelState> = _state.asStateFlow()

    private val lock = Object()
    private var generation = 0
    private var running = false
    private var wakeRequested = false
    private var reconnectRequested = false
    private var current: TunnelConnection? = null

    fun start(localPort: Int) {
        val gen: Int
        synchronized(lock) {
            if (running) return
            running = true
            reconnectRequested = false
            gen = ++generation
        }
        _state.value = McpTunnelState(status = TunnelStatus.CONNECTING)
        Thread({ run(gen, localPort) }, "runcode-mcp-tunnel").apply { isDaemon = true; start() }
    }

    fun stop() {
        val connection: TunnelConnection?
        synchronized(lock) {
            if (!running) return
            running = false
            generation++
            connection = current
            current = null
            wakeRequested = true
            lock.notifyAll()
        }
        closeQuietly(connection)
        _state.value = McpTunnelState()
        onLog(LogLevel.SYSTEM, "[MCP] Public tunnel stopped.")
    }

    /** The default network changed; the old socket is probably dead, so reconnect now. */
    fun onNetworkChanged() {
        synchronized(lock) {
            if (!running) return
            reconnectRequested = true
            wakeRequested = true
            lock.notifyAll()
        }
    }

    private fun run(gen: Int, localPort: Int) {
        var failures = 0
        var index = 0
        while (isCurrent(gen)) {
            val provider = providers[index % providers.size]
            update(gen) { it.copy(status = TunnelStatus.CONNECTING, provider = provider.name, retryInSeconds = 0) }

            val opened = try {
                connector.open(provider, localPort, connectTimeoutMs)
            } catch (e: Exception) {
                failures++
                index++
                val reason = describe(e)
                onLog(LogLevel.WARN, "[MCP] Tunnel via ${provider.name} failed: $reason")
                update(gen) { it.copy(lastError = "${provider.name}: $reason", failures = failures) }
                if (!pause(gen, backoffMs(failures))) break
                continue
            }

            if (!adopt(gen, opened.connection)) {
                closeQuietly(opened.connection)
                break
            }
            failures = 0
            update(gen) {
                McpTunnelState(status = TunnelStatus.ONLINE, url = opened.url, provider = provider.name)
            }
            onLog(LogLevel.SYSTEM, "[MCP] Public endpoint via ${provider.name}: ${opened.url}/mcp")

            val networkChanged = hold(gen, opened.connection)
            release(opened.connection)
            if (!isCurrent(gen)) break

            if (networkChanged) {
                onLog(LogLevel.INFO, "[MCP] Network changed; reconnecting the tunnel.")
                continue
            }
            // Dropped after working: retry the same relay after a short pause.
            failures = 1
            onLog(LogLevel.WARN, "[MCP] Tunnel via ${provider.name} dropped.")
            update(gen) { it.copy(url = null, lastError = "${provider.name}: connection lost", failures = failures) }
            if (!pause(gen, backoffMs(failures))) break
        }
    }

    /** Waits while the tunnel is up. Returns true when a network change asked for a reconnect. */
    private fun hold(gen: Int, connection: TunnelConnection): Boolean {
        synchronized(lock) {
            while (generation == gen && running && !reconnectRequested && connection.isAlive) {
                if (!wakeRequested) lock.wait(pollMs)
                wakeRequested = false
            }
            val changed = reconnectRequested
            reconnectRequested = false
            return changed
        }
    }

    /** Sleeps before the next attempt; a network change cuts it short. Returns false once stopped. */
    private fun pause(gen: Int, delayMs: Long) = synchronized(lock) {
        if (generation != gen) return@synchronized false
        update(gen) { it.copy(status = TunnelStatus.RETRYING, retryInSeconds = ((delayMs + 999) / 1000).toInt()) }
        if (!wakeRequested && !reconnectRequested) lock.wait(delayMs)
        wakeRequested = false
        reconnectRequested = false
        generation == gen && running
    }

    private fun adopt(gen: Int, connection: TunnelConnection) = synchronized(lock) {
        if (generation != gen || !running) return@synchronized false
        current = connection
        true
    }

    private fun release(connection: TunnelConnection) {
        synchronized(lock) { if (current === connection) current = null }
        closeQuietly(connection)
    }

    private fun isCurrent(gen: Int) = synchronized(lock) { generation == gen && running }

    private fun update(gen: Int, change: (McpTunnelState) -> McpTunnelState) {
        synchronized(lock) {
            if (generation == gen && running) _state.value = change(_state.value)
        }
    }

    private fun describe(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}
