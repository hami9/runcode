package com.runcode.app.mcp

import org.apache.sshd.common.forward.PortForwardingEventListener
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the real JSch connector against a local SSH server that behaves like Pinggy: any
 * credentials, a remote forward on port 0, and the public URL printed on the shell.
 */
class SshTunnelConnectorTest {

    private lateinit var relay: SshServer
    private lateinit var bridge: ServerSocket
    private val knownHosts = File.createTempFile("known_hosts", "").apply { delete() }
    private val forwardedPort = AtomicInteger(-1)
    private val shellOpened = CountDownLatch(1)

    @Before fun setUp() {
        bridge = ServerSocket(0)
        Thread {
            while (!bridge.isClosed) {
                val client = try { bridge.accept() } catch (_: Exception) { break }
                client.use {
                    it.getInputStream().read(ByteArray(1024))
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\npong".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }

        relay = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            shellFactory = ShellFactory { BannerShell() }
            addPortForwardingEventListener(object : PortForwardingEventListener {
                override fun establishedExplicitTunnel(
                    session: Session,
                    local: SshdSocketAddress?,
                    remote: SshdSocketAddress?,
                    localForwarding: Boolean,
                    boundAddress: SshdSocketAddress?,
                    reason: Throwable?
                ) {
                    // A client's -R is a listener on the server, so the flag does not tell us much here.
                    if (boundAddress != null && boundAddress.port > 0) forwardedPort.set(boundAddress.port)
                }
            })
        }
        relay.start()
    }

    @After fun tearDown() {
        relay.stop(true)
        bridge.close()
    }

    private inner class BannerShell : Command {
        private var out: OutputStream? = null
        override fun setInputStream(`in`: InputStream?) = Unit
        override fun setOutputStream(out: OutputStream?) { this.out = out }
        override fun setErrorStream(err: OutputStream?) = Unit
        override fun setExitCallback(callback: ExitCallback?) = Unit
        override fun start(channel: ChannelSession?, env: Environment?) {
            out?.apply {
                write("Upgrade at https://dashboard.pinggy.io\r\n".toByteArray())
                write("https://test-1-2-3-4.run.pinggy-free.link\r\n".toByteArray())
                flush()
            }
            shellOpened.countDown()
        }
        override fun destroy(channel: ChannelSession?) = Unit
    }

    private fun relayProvider(remotePort: Int) =
        TunnelProvider.PINGGY.copy(host = "127.0.0.1", port = relay.port, remotePort = remotePort)

    private fun requestThroughRelay(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (forwardedPort.get() <= 0 && System.nanoTime() < deadline) Thread.sleep(10)
        Socket("127.0.0.1", forwardedPort.get()).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            return socket.getInputStream().readBytes().decodeToString()
        }
    }

    @Test fun `port 0 forward reaches the local bridge and reports the relay URL`() {
        val opened = SshTunnelConnector(knownHosts).open(relayProvider(remotePort = 0), bridge.localPort, 10_000)
        try {
            assertEquals("https://test-1-2-3-4.run.pinggy-free.link", opened.url)
            assertTrue(opened.connection.isAlive)
            assertTrue(requestThroughRelay().endsWith("pong"))
        } finally {
            opened.connection.close()
        }
        assertFalse(opened.connection.isAlive)
    }

    @Test fun `fixed remote port works as localhost run expects`() {
        val fixed = ServerSocket(0).use { it.localPort }
        val opened = SshTunnelConnector(knownHosts).open(relayProvider(remotePort = fixed), bridge.localPort, 10_000)
        try {
            assertEquals(fixed, forwardedPort.get())
            assertTrue(requestThroughRelay().endsWith("pong"))
        } finally {
            opened.connection.close()
        }
    }

    @Test fun `an interrupt left behind by JSch is cleared, a pending one is kept`() {
        // What JSch does when the forward's reply arrives before the caller sleeps.
        assertEquals(7, SshTunnelConnector.withoutStrayInterrupt { Thread.currentThread().interrupt(); 7 })
        assertFalse(Thread.currentThread().isInterrupted)

        Thread.currentThread().interrupt()
        SshTunnelConnector.withoutStrayInterrupt { }
        assertTrue("an interrupt from before the call belongs to the caller", Thread.interrupted())
    }

    @Test fun `opening never leaves the calling thread interrupted`() {
        repeat(10) { attempt ->
            val opened = SshTunnelConnector(knownHosts).open(relayProvider(remotePort = 0), bridge.localPort, 10_000)
            val interrupted = Thread.interrupted()
            opened.connection.close()
            assertFalse("interrupt flag left set after open #$attempt", interrupted)
        }
    }

    @Test fun `relay stopping is seen as a dead connection`() {
        val opened = SshTunnelConnector(knownHosts).open(relayProvider(remotePort = 0), bridge.localPort, 10_000)
        assertTrue(shellOpened.await(5, TimeUnit.SECONDS))
        relay.stop(true)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (opened.connection.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(opened.connection.isAlive)
        opened.connection.close()
    }

    @Test fun `unreachable relay fails fast with a reason`() {
        val closed = ServerSocket(0).use { it.localPort }
        val error = runCatching {
            SshTunnelConnector(knownHosts).open(TunnelProvider.PINGGY.copy(host = "127.0.0.1", port = closed), bridge.localPort, 3_000)
        }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test fun `the relay's key is trusted on first use and checked afterwards`() {
        SshTunnelConnector(knownHosts).open(relayProvider(remotePort = 0), bridge.localPort, 10_000).connection.close()
        val saved = knownHosts.readText()
        assertTrue(saved, saved.startsWith("[127.0.0.1]:${relay.port} "))

        // Same server, same key: accepted, nothing new stored.
        SshTunnelConnector(knownHosts).open(relayProvider(remotePort = 0), bridge.localPort, 10_000).connection.close()
        assertEquals(saved, knownHosts.readText())
    }

    @Test fun `a different key for a known relay is refused`() {
        // Learn some other server's key, then record it as if it belonged to this relay.
        val impostor = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            shellFactory = ShellFactory { BannerShell() }
        }
        impostor.start()
        try {
            SshTunnelConnector(knownHosts).open(relayProvider(remotePort = 0).copy(port = impostor.port), bridge.localPort, 10_000)
                .connection.close()
        } finally {
            impostor.stop(true)
        }
        knownHosts.writeText(knownHosts.readText().replace("[127.0.0.1]:${impostor.port}", "[127.0.0.1]:${relay.port}"))

        val error = runCatching {
            SshTunnelConnector(knownHosts).open(relayProvider(remotePort = 0), bridge.localPort, 10_000)
        }.exceptionOrNull()
        assertTrue(error.toString(), error is IOException)
        assertTrue(error!!.message!!.contains("different host key"))
        assertEquals("no forward may be set up for an untrusted relay", -1, forwardedPort.get())

        TofuHostKeys.forgetAll(knownHosts)
        SshTunnelConnector(knownHosts).open(relayProvider(remotePort = 0), bridge.localPort, 10_000).connection.close()
    }
}
