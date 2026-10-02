package com.runcode.app.mcp

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.JSchChangedHostKeyException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Opens a reverse tunnel the way `ssh -p 443 -R0:localhost:8765 free.pinggy.io` does: remote
 * forward to the local bridge, then read the public URL from the relay's shell output.
 */
class SshTunnelConnector(private val knownHosts: File) : TunnelConnector {

    override fun open(provider: TunnelProvider, localPort: Int, timeoutMs: Int): OpenTunnel {
        val session = JSch().getSession(provider.user, provider.host, provider.port)
        // Free relays take no credentials and publish no host keys to pin, so the first key
        // each relay presents is trusted and a different one later is refused.
        val hostKeys = TofuHostKeys(knownHosts)
        session.hostKeyRepository = hostKeys
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", "none,keyboard-interactive,password")
        session.userInfo = hostKeys.prompts
        session.setPassword("")
        try {
            try {
                session.connect(timeoutMs)
            } catch (e: JSchChangedHostKeyException) {
                throw IOException(
                    "${provider.host} presented a different host key than on first use. Someone may be " +
                        "intercepting the connection. If the relay changed its key, use Forget relay keys."
                )
            }
            // Keepalives notice a dead socket within about 45 seconds.
            session.setServerAliveInterval(KEEPALIVE_MS)
            session.setServerAliveCountMax(3)
            session.setPortForwardingR(null, provider.remotePort, "127.0.0.1", localPort)

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPty(false)
            val output = channel.inputStream
            // No timeout here: with one, JSch gives up on the first wake-up of its wait loop,
            // which fails about one open in fifty. Without one it polls for up to 20 seconds.
            channel.connect()

            val url = awaitUrl(provider, session, output, channel, timeoutMs)
            return OpenTunnel(SshConnection(session, channel, output), url)
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }

    private fun awaitUrl(
        provider: TunnelProvider,
        session: Session,
        output: InputStream,
        channel: ChannelShell,
        timeoutMs: Int
    ): String {
        val text = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val available = output.available()
            if (available > 0) {
                val n = output.read(buffer, 0, minOf(available, buffer.size))
                if (n > 0 && text.length < MAX_BANNER) text.append(String(buffer, 0, n, Charsets.UTF_8))
                TunnelUrls.find(text.toString(), provider)?.let { return it }
                continue
            }
            if (channel.isClosed || !session.isConnected) {
                throw IOException(closedReason(text))
            }
            Thread.sleep(100)
        }
        throw IOException("No public URL within ${timeoutMs / 1000}s" + tail(text))
    }

    private fun closedReason(text: CharSequence): String = "Relay closed the session" + tail(text)

    private fun tail(text: CharSequence): String {
        val last = text.toString().trim().lines().lastOrNull { it.isNotBlank() }?.trim()?.take(160)
        return if (last.isNullOrEmpty()) "" else ": $last"
    }

    private class SshConnection(
        private val session: Session,
        private val channel: ChannelShell,
        private val output: InputStream
    ) : TunnelConnection {

        init {
            // Keep reading so the relay's status lines never fill the channel window.
            Thread({
                val sink = ByteArray(1024)
                try {
                    while (output.read(sink) >= 0) Unit
                } catch (_: IOException) {
                }
            }, "runcode-mcp-tunnel-drain").apply { isDaemon = true; start() }
        }

        override val isAlive: Boolean
            get() = session.isConnected && !channel.isClosed

        override fun close() {
            channel.disconnect()
            session.disconnect()
        }
    }

    private companion object {
        const val KEEPALIVE_MS = 15_000
        const val MAX_BANNER = 64 * 1024
    }
}
