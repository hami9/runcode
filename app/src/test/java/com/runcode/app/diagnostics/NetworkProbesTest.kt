package com.runcode.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

class NetworkProbesTest {

    /** Answers every connection with [response] until closed. */
    private fun httpServer(response: String): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                client.use {
                    it.getInputStream().read(ByteArray(2048))
                    it.getOutputStream().write(response.toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    private fun closedPort() = ServerSocket(0).use { it.localPort }

    @Test fun `tcp probe sees a listener and a closed port`() {
        ServerSocket(0).use { server ->
            assertTrue(NetworkProbes.tcpAccepts("127.0.0.1", server.localPort).ok)
        }
        val closed = NetworkProbes.tcpAccepts("127.0.0.1", closedPort())
        assertFalse(closed.ok)
        assertTrue(closed.detail.isNotBlank())
    }

    @Test fun `any HTTP answer counts as reachable, redirects are not followed`() {
        httpServer("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/\r\nContent-Length: 0\r\n\r\n").use { server ->
            val result = NetworkProbes.httpReachable("http://127.0.0.1:${server.localPort}/")
            assertTrue(result.detail, result.ok)
            assertEquals("HTTP 302", result.detail)
        }
        assertFalse(NetworkProbes.httpReachable("http://127.0.0.1:${closedPort()}/", 2_000).ok)
    }

    @Test fun `mcp health accepts only the bridge's own answer`() {
        val body = """{"status":"ok","service":"runcode-mcp"}"""
        httpServer("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body").use { server ->
            assertTrue(NetworkProbes.mcpHealth(server.localPort).ok)
        }
        httpServer("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello").use { server ->
            val result = NetworkProbes.mcpHealth(server.localPort)
            assertFalse(result.ok)
            assertTrue(result.detail.startsWith("unexpected answer"))
        }
        assertFalse(NetworkProbes.mcpHealth(closedPort()).ok)
    }

    @Test fun `unresolvable names fail with a reason`() {
        val result = NetworkProbes.resolves("does-not-exist.invalid", 5_000)
        assertFalse(result.ok)
        assertTrue(result.detail.isNotBlank())
    }
}
