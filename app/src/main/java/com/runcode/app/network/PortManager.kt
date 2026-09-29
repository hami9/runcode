package com.runcode.app.network

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

class PortManager {

    private val reservations = ConcurrentHashMap<Int, String>() // port -> serviceId

    fun isPortAvailable(port: Int): Boolean {
        if (port < 1024 || port > 65535) return false
        if (reservations.containsKey(port)) return false

        return try {
            ServerSocket(port).use { ss ->
                ss.reuseAddress = true
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun reservePort(port: Int, serviceId: String): Boolean {
        if (isPortAvailable(port)) {
            reservations[port] = serviceId
            return true
        }
        return false
    }

    fun releasePort(port: Int) {
        reservations.remove(port)
    }

    fun releaseServicePorts(serviceId: String) {
        reservations.entries.removeIf { it.value == serviceId }
    }

    fun getActiveReservations(): Map<Int, String> {
        return reservations.toMap()
    }

    fun getNextAvailablePort(preferredPort: Int = 8080, serviceId: String): Int {
        if (reservePort(preferredPort, serviceId)) {
            return preferredPort
        }
        for (candidate in (preferredPort + 1)..8999) {
            if (reservePort(candidate, serviceId)) {
                return candidate
            }
        }
        throw IllegalStateException("No open developer port found in range 8080-8999")
    }

    /**
     * Address other devices on the local network can reach, or 127.0.0.1 when there is none.
     *
     * Interface order is arbitrary, so taking the first IPv4 address often picked the mobile
     * data interface, whose carrier-assigned address nothing on the Wi-Fi can reach.
     */
    fun getLanIp(): String {
        return try {
            val candidates = mutableListOf<LanCandidate>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return LOOPBACK
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        candidates += LanCandidate(iface.name, host, addr.isSiteLocalAddress)
                    }
                }
            }
            selectLanIp(candidates) ?: LOOPBACK
        } catch (_: Exception) {
            LOOPBACK
        }
    }

    data class LanCandidate(val interfaceName: String, val address: String, val isPrivate: Boolean)

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        // Wi-Fi, hotspot and Ethernet, then USB/Bluetooth tethering and Wi-Fi Direct.
        private val LAN_PREFIXES = listOf("wlan", "swlan", "ap", "softap", "eth")
        private val TETHER_PREFIXES = listOf("rndis", "usb", "ncm", "bt-pan", "p2p")

        // Mobile data (rmnet/ccmni/...), 464xlat, VPN tunnels and virtual links.
        private val UNREACHABLE_PREFIXES = listOf(
            "rmnet", "ccmni", "pdp", "seth", "clat", "v4-", "tun", "ppp", "ipsec", "dummy", "ifb"
        )

        /** Picks the most reachable address, or null when only unreachable ones exist. */
        fun selectLanIp(candidates: List<LanCandidate>): String? {
            return candidates
                .mapNotNull { candidate -> rank(candidate)?.let { it to candidate } }
                .minWithOrNull(compareBy<Pair<Int, LanCandidate>> { it.first }.thenBy { !it.second.isPrivate })
                ?.second
                ?.address
        }

        private fun rank(candidate: LanCandidate): Int? {
            val name = candidate.interfaceName.lowercase()
            return when {
                UNREACHABLE_PREFIXES.any { name.startsWith(it) } -> null
                LAN_PREFIXES.any { name.startsWith(it) } -> 0
                TETHER_PREFIXES.any { name.startsWith(it) } -> 1
                // Unknown interface names count only with a private address.
                candidate.isPrivate -> 2
                else -> null
            }
        }
    }
}
