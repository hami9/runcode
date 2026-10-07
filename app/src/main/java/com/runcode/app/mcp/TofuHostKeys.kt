package com.runcode.app.mcp

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.File

/**
 * Trust on first use for relay host keys, keyed by "[host]:port" as JSch names them.
 *
 * The free relays publish no keys to pin, so the first key seen is saved and any later
 * connection that presents a different key is refused. Without this, anyone on the network
 * path could pose as the relay, hand out a URL of their own and collect the bearer token.
 *
 * One instance per connection: [lastCheck] tells [prompts] which question JSch is asking.
 */
class TofuHostKeys(private val file: File) : HostKeyRepository {

    @Volatile
    private var lastCheck = HostKeyRepository.OK

    /**
     * Answers JSch's prompts: accept an unknown host once, never a changed key, and give the
     * free relays the empty password they expect, as pressing Enter at the ssh prompt does.
     */
    val prompts: UserInfo = object : UserInfo, UIKeyboardInteractive {
        override fun promptYesNo(message: String?) = lastCheck == HostKeyRepository.NOT_INCLUDED
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String> = Array(prompt?.size ?: 0) { "" }
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = ""
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun showMessage(message: String?) = Unit
    }

    override fun check(host: String, key: ByteArray): Int {
        val offered = HostKey(host, key).key
        val known = read().filter { it.host == host }
        lastCheck = when {
            known.isEmpty() -> HostKeyRepository.NOT_INCLUDED
            known.any { it.key == offered } -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
        return lastCheck
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        synchronized(LOCK) {
            if (read().any { it.host == hostkey.host && it.key == hostkey.key }) return
            file.parentFile?.mkdirs()
            file.appendText("${hostkey.host} ${hostkey.type} ${hostkey.key}\n")
        }
    }

    override fun remove(host: String, type: String?) = remove(host, type, null)

    override fun remove(host: String, type: String?, key: ByteArray?) {
        synchronized(LOCK) {
            val keep = read().filterNot { it.host == host && (type == null || it.type == type) }
            file.writeText(keep.joinToString("") { "${it.host} ${it.type} ${it.key}\n" })
        }
    }

    override fun getKnownHostsRepositoryID(): String = file.path

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        read().filter { (host == null || it.host == host) && (type == null || it.type == type) }
            .mapNotNull { entry ->
                runCatching { HostKey(entry.host, java.util.Base64.getDecoder().decode(entry.key)) }.getOrNull()
            }
            .toTypedArray()

    private data class Entry(val host: String, val type: String, val key: String)

    private fun read(): List<Entry> = synchronized(LOCK) {
        if (!file.isFile) return emptyList()
        file.readLines().mapNotNull { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 3) Entry(parts[0], parts[1], parts[2]) else null
        }
    }

    companion object {
        private val LOCK = Any()

        /** Forgets every saved relay key, for when a relay really did change its key. */
        fun forgetAll(file: File) = synchronized(LOCK) { file.delete() }
    }
}
