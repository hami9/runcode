package com.runcode.app.mcp

import com.runcode.app.domain.models.LogLevel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class McpTunnelTest {

    private val relayA = TunnelProvider("A", "a.example", 443, "u", 0, listOf("a.example"))
    private val relayB = TunnelProvider("B", "b.example", 22, "u", 80, listOf("b.example"))
    private var tunnel: McpTunnel? = null

    @After fun tearDown() {
        tunnel?.stop()
    }

    // ------------------------------------------------------------ URL parsing

    @Test fun `pinggy banner yields the tunnel URL, not its own links`() {
        val banner = """
            You are not authenticated.
            Your tunnel will expire in 60 minutes. Upgrade to Pinggy Pro to get unrestricted tunnels. https://dashboard.pinggy.io

            http://rnxyz-1-2-3-4.run.pinggy-free.link
            https://rnxyz-1-2-3-4.run.pinggy-free.link
        """.trimIndent()
        assertEquals("https://rnxyz-1-2-3-4.run.pinggy-free.link", TunnelUrls.find(banner, TunnelProvider.PINGGY))
    }

    @Test fun `localhost run banner yields the lhr life URL`() {
        val banner = """
            ** your connection id is 1234, please mention it if you send me a message about an issue. **
            To set up and manage custom domains go to https://admin.localhost.run/
            More details on custom domains (and how to enable subdomains of your custom domain) at https://localhost.run/docs/custom-domains

            a1b2c3d4e5f6.lhr.life tunneled with tls termination, https://a1b2c3d4e5f6.lhr.life
        """.trimIndent()
        assertEquals("https://a1b2c3d4e5f6.lhr.life", TunnelUrls.find(banner, TunnelProvider.LOCALHOST_RUN))
    }

    @Test fun `ANSI colour codes around the URL are ignored`() {
        val banner = "\u001B[32mhttps://abc.run.pinggy-free.link\u001B[0m\r\n"
        assertEquals("https://abc.run.pinggy-free.link", TunnelUrls.find(banner, TunnelProvider.PINGGY))
    }

    @Test fun `no URL until the relay prints one`() {
        assertNull(TunnelUrls.find("Connecting…\r\nhttps://pinggy.io/docs/ for help", TunnelProvider.PINGGY))
        assertNull(TunnelUrls.find("https://abc.run.pinggy-fr", TunnelProvider.PINGGY)?.takeIf { it.endsWith(".link") })
    }

    // ------------------------------------------------------------ supervision

    private class FakeConnection : TunnelConnection {
        val alive = AtomicBoolean(true)
        override val isAlive get() = alive.get()
        override fun close() = alive.set(false)
    }

    private fun newTunnel(connector: TunnelConnector) = McpTunnel(
        connector = connector,
        onLog = { _: LogLevel, _: String -> },
        providers = listOf(relayA, relayB),
        pollMs = 20,
        backoffMs = { 20L }
    ).also { tunnel = it }

    private fun awaitState(tunnel: McpTunnel, predicate: (McpTunnelState) -> Boolean): McpTunnelState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val state = tunnel.state.value
            if (predicate(state)) return state
            Thread.sleep(10)
        }
        fail("state never matched; last = ${tunnel.state.value}")
        throw AssertionError()
    }

    @Test fun `a failing relay falls through to the next one`() {
        val tried = CopyOnWriteArrayList<String>()
        val t = newTunnel { provider, _, _ ->
            tried += provider.name
            if (provider == relayA) throw IOException("port 443 blocked")
            OpenTunnel(FakeConnection(), "https://b.tunnel")
        }
        t.start(8765)
        val online = awaitState(t) { it.status == TunnelStatus.ONLINE }
        assertEquals("https://b.tunnel", online.url)
        assertEquals("B", online.provider)
        assertEquals(listOf("A", "B"), tried.take(2))
    }

    @Test fun `a dropped tunnel reconnects and publishes the new URL`() {
        val connections = CopyOnWriteArrayList<FakeConnection>()
        val t = newTunnel { _, _, _ ->
            val c = FakeConnection().also { connections += it }
            OpenTunnel(c, "https://tunnel-${connections.size}")
        }
        t.start(8765)
        awaitState(t) { it.url == "https://tunnel-1" }
        connections[0].alive.set(false)
        awaitState(t) { it.status == TunnelStatus.ONLINE && it.url == "https://tunnel-2" }
    }

    @Test fun `a network change replaces a tunnel that still looks alive`() {
        val connections = CopyOnWriteArrayList<FakeConnection>()
        val t = newTunnel { _, _, _ ->
            val c = FakeConnection().also { connections += it }
            OpenTunnel(c, "https://tunnel-${connections.size}")
        }
        t.start(8765)
        awaitState(t) { it.url == "https://tunnel-1" }
        t.onNetworkChanged()
        awaitState(t) { it.url == "https://tunnel-2" }
        assertFalse("old socket must be closed", connections[0].isAlive)
    }

    @Test fun `stop closes the connection and resets the state`() {
        val connection = FakeConnection()
        val t = newTunnel { _, _, _ -> OpenTunnel(connection, "https://x.tunnel") }
        t.start(8765)
        awaitState(t) { it.status == TunnelStatus.ONLINE }
        t.stop()
        assertEquals(McpTunnelState(), t.state.value)
        assertFalse(connection.isAlive)
    }

    @Test fun `a tunnel that opens after stop is closed, not adopted`() {
        val release = Object()
        var go = false
        val entered = java.util.concurrent.CountDownLatch(1)
        val late = FakeConnection()
        val t = newTunnel { _, _, _ ->
            entered.countDown()
            synchronized(release) { while (!go) release.wait() }
            OpenTunnel(late, "https://late.tunnel")
        }
        t.start(8765)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        t.stop()
        synchronized(release) { go = true; release.notifyAll() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (late.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(late.isAlive)
        assertEquals(TunnelStatus.OFF, t.state.value.status)
    }
}
