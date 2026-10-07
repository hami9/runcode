package com.runcode.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.runcode.app.BuildConfig
import com.runcode.app.RuncodeApp
import com.runcode.app.mcp.TunnelStatus
import com.runcode.app.runtime.PythonEngine
import com.runcode.app.service.RuntimeForegroundService
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Collects the facts for a diagnostics report from Android and the running app, runs the
 * network probes in parallel, and hands everything to [DiagnosticRules].
 *
 * Blocking: call it off the main thread. The slowest probe bounds it at about 8 seconds.
 */
class DiagnosticsRunner(private val app: RuncodeApp) {

    fun run(): DiagnosticReport {
        val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "runcode-diagnostics").apply { isDaemon = true } }
        try {
            fun <T> async(block: () -> T): Future<T> = pool.submit(Callable(block))

            val dns = async { NetworkProbes.resolves(DNS_HOST) }
            val https = async { firstReachable(HTTPS_TARGETS) }
            val telegram = async { NetworkProbes.httpReachable(TELEGRAM_URL) }

            val bridge = app.mcpServer.state.value
            val health = if (bridge.isRunning) async { NetworkProbes.mcpHealth(bridge.port) } else null

            val running = app.serviceSupervisor.instances.value.values.filter { it.isRunning }
            val serviceProbes = running.map { instance ->
                val probe = if (instance.profile in DiagnosticRules.LISTENING_PROFILES && instance.port > 0) {
                    async { NetworkProbes.tcpAccepts("127.0.0.1", instance.port) }
                } else null
                instance to probe
            }

            val device = deviceSnapshot(foregroundWork = running.size + if (bridge.isRunning) 1 else 0)
            val network = networkSnapshot(dns.get(), https.get(), telegram.get())
            val services = serviceProbes.map { (instance, probe) ->
                ServiceSnapshot(instance.projectName, instance.profile, instance.port, probe?.get())
            }
            val tunnel = app.mcpTunnel.state.value
            val bridgeSnapshot = BridgeSnapshot(
                running = bridge.isRunning,
                port = bridge.port,
                health = health?.get(),
                tunnelEnabled = tunnel.status != TunnelStatus.OFF,
                tunnelOnline = tunnel.status == TunnelStatus.ONLINE,
                tunnelUrl = tunnel.url,
                tunnelError = tunnel.lastError
            )

            val checks = DiagnosticRules.device(device) +
                DiagnosticRules.network(network) +
                DiagnosticRules.services(services) +
                DiagnosticRules.bridge(bridgeSnapshot)
            return DiagnosticReport(
                System.currentTimeMillis(),
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                checks
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun deviceSnapshot(foregroundWork: Int): DeviceSnapshot {
        val caps = app.compatibilityManager.getCapabilities(runningServicesCount = 0, wakeLockActive = false)
        return DeviceSnapshot(
            apiLevel = caps.androidApi,
            release = caps.releaseVersion,
            abis = caps.supportedAbis,
            freeStorageMb = caps.freeStorageMb,
            availableMemoryMb = caps.availableMemoryMb,
            totalMemoryMb = caps.totalMemoryMb,
            lowMemory = caps.isLowMemory,
            notificationsAllowed = caps.notificationsAllowed,
            batteryExempt = caps.isIgnoringBatteryOptimizations,
            pythonVersion = if (app.isPythonAvailable) PythonEngine.pythonVersion() else null,
            lastCrash = app.lastCrashReport(),
            inForeground = RuntimeForegroundService.isInForeground,
            foregroundWork = foregroundWork
        )
    }

    private fun networkSnapshot(dns: ProbeResult, https: ProbeResult, telegram: ProbeResult): NetworkSnapshot {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val transports = caps?.let { c ->
            TRANSPORTS.filter { (transport, _) -> c.hasTransport(transport) }.map { it.second }
        }
        return NetworkSnapshot(
            transports = transports,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            lanIp = app.portManager.getLanIp(),
            reservedPorts = app.portManager.getActiveReservations(),
            dns = dns,
            https = https,
            telegram = telegram
        )
    }

    /** Some networks block one big provider but not another, so either one counts. */
    private fun firstReachable(urls: List<String>): ProbeResult {
        var last: ProbeResult? = null
        for (url in urls) {
            val result = NetworkProbes.httpReachable(url)
            if (result.ok) return result.copy(detail = "${result.detail} from ${hostOf(url)}")
            last = result
        }
        return last!!.copy(detail = "no HTTPS site answered (${last.detail})")
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

    private companion object {
        const val DNS_HOST = "connectivitycheck.gstatic.com"
        const val TELEGRAM_URL = "https://api.telegram.org/"
        val HTTPS_TARGETS = listOf(
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        val TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "mobile data",
            NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth"
        )
    }
}
