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

    fun getLanIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
