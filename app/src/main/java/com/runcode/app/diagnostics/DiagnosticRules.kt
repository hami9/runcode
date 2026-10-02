package com.runcode.app.diagnostics

import com.runcode.app.diagnostics.CheckStatus.FAIL
import com.runcode.app.diagnostics.CheckStatus.INFO
import com.runcode.app.diagnostics.CheckStatus.PASS
import com.runcode.app.diagnostics.CheckStatus.WARN
import com.runcode.app.domain.models.ProjectProfile

data class DeviceSnapshot(
    val apiLevel: Int,
    val release: String,
    val abis: List<String>,
    val freeStorageMb: Long,
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val lowMemory: Boolean,
    val notificationsAllowed: Boolean,
    val batteryExempt: Boolean,
    val pythonVersion: String?,
    val lastCrash: String?,
    val inForeground: Boolean,
    /** Supervised services running plus the MCP bridge: anything that needs the foreground. */
    val foregroundWork: Int
)

data class ServiceSnapshot(
    val name: String,
    val profile: ProjectProfile,
    val port: Int,
    val probe: ProbeResult?
)

data class NetworkSnapshot(
    /** Null when no network is connected at all. */
    val transports: List<String>?,
    val validated: Boolean,
    val vpn: Boolean,
    val lanIp: String,
    val reservedPorts: Map<Int, String>,
    val dns: ProbeResult,
    val https: ProbeResult,
    val telegram: ProbeResult
)

data class BridgeSnapshot(
    val running: Boolean,
    val port: Int,
    val health: ProbeResult?,
    val tunnelEnabled: Boolean,
    val tunnelOnline: Boolean,
    val tunnelUrl: String?,
    val tunnelError: String?
)

/** Turns observed facts into checks. No Android and no I/O here, so it is unit tested. */
object DiagnosticRules {

    const val APP = "App"
    const val NETWORK = "Network"
    const val SERVICES = "Services"
    const val BRIDGE = "MCP bridge"

    /** Profiles whose runtime serves HTTP on the project's port. The rest never listen. */
    val LISTENING_PROFILES = setOf(ProjectProfile.STATIC_WEB, ProjectProfile.PYTHON_HTTP)

    fun device(s: DeviceSnapshot): List<DiagnosticCheck> = buildList {
        add(check(APP, "Android", INFO, "${s.release} (API ${s.apiLevel}), ${s.abis.joinToString()}"))

        add(when {
            s.freeStorageMb < 50 -> check(APP, "Storage", FAIL, "${s.freeStorageMb} MB free — saves and logs will fail")
            s.freeStorageMb < 200 -> check(APP, "Storage", WARN, "${s.freeStorageMb} MB free")
            else -> check(APP, "Storage", PASS, "${s.freeStorageMb} MB free")
        })

        val memory = "${s.availableMemoryMb} of ${s.totalMemoryMb} MB available"
        add(when {
            s.totalMemoryMb <= 0 -> check(APP, "Memory", INFO, "not reported by Android")
            s.lowMemory -> check(APP, "Memory", WARN, "$memory — Android reports low memory")
            else -> check(APP, "Memory", PASS, memory)
        })

        add(if (s.notificationsAllowed) check(APP, "Notifications", PASS, "allowed")
        else check(APP, "Notifications", WARN, "blocked — the running-services notification is hidden"))

        add(if (s.batteryExempt) check(APP, "Battery", PASS, "exempt from battery optimisation")
        else check(APP, "Battery", WARN, "optimised — Android may stop services in the background"))

        add(if (s.pythonVersion != null) check(APP, "Python", PASS, "CPython ${s.pythonVersion} started")
        else check(APP, "Python", FAIL, "embedded interpreter did not start"))

        add(when {
            s.foregroundWork == 0 -> check(APP, "Foreground", INFO, "not needed — nothing is running")
            s.inForeground -> check(APP, "Foreground", PASS, "held for ${s.foregroundWork} running item(s)")
            else -> check(APP, "Foreground", FAIL, "work is running but the foreground service is not")
        })

        add(if (s.lastCrash == null) check(APP, "Last crash", PASS, "none recorded")
        else check(APP, "Last crash", WARN, crashSummary(s.lastCrash)))
    }

    /** "time · exception" from CrashReporter's header block and the trace that follows it. */
    fun crashSummary(report: String): String {
        val lines = report.lines()
        val time = lines.firstOrNull { it.startsWith("time:") }?.substringAfter(':')?.trim()
        val headerEnd = lines.indexOfFirst { it.isBlank() }
        val exception = lines.drop(headerEnd + 1).firstOrNull { it.isNotBlank() }?.trim()?.take(120)
        return listOfNotNull(time, exception).joinToString(" · ").ifEmpty { "recorded" }
    }

    fun network(n: NetworkSnapshot): List<DiagnosticCheck> = buildList {
        if (n.transports == null) {
            add(check(NETWORK, "Connection", FAIL, "no network connected"))
        } else {
            val via = n.transports.joinToString(" + ").ifEmpty { "unknown" }
            add(if (n.validated) check(NETWORK, "Connection", PASS, "$via, internet validated")
            else check(NETWORK, "Connection", WARN, "$via, Android could not validate internet access"))
        }
        if (n.vpn) add(check(NETWORK, "VPN", INFO, "active — traffic leaves through the VPN"))

        add(if (n.lanIp == "127.0.0.1") check(NETWORK, "LAN address", INFO, "none — not on Wi-Fi, hotspot or Ethernet")
        else check(NETWORK, "LAN address", INFO, n.lanIp))

        add(check(NETWORK, "Reserved ports", INFO,
            n.reservedPorts.entries.sortedBy { it.key }.joinToString { "${it.key}" }.ifEmpty { "none" }))

        add(probe(NETWORK, "DNS", n.dns))
        add(probe(NETWORK, "HTTPS", n.https))
        // Blocked on some networks; only bots need it, so it is a warning.
        add(if (n.telegram.ok) check(NETWORK, "Telegram API", PASS, "${n.telegram.detail} in ${n.telegram.millis} ms")
        else check(NETWORK, "Telegram API", WARN, "unreachable (${n.telegram.detail}) — bots need it; a VPN may be required"))
    }

    fun services(list: List<ServiceSnapshot>): List<DiagnosticCheck> {
        if (list.isEmpty()) return listOf(check(SERVICES, "Running", INFO, "no services running"))
        return list.map { s ->
            when {
                s.profile !in LISTENING_PROFILES ->
                    check(SERVICES, s.name, INFO, "${s.profile.displayName}, does not serve a port")
                s.probe == null -> check(SERVICES, s.name, INFO, "port ${s.port} not probed")
                s.probe.ok -> check(SERVICES, s.name, PASS, "port ${s.port} accepts connections")
                else -> check(SERVICES, s.name, FAIL, "port ${s.port} is not listening (${s.probe.detail})")
            }
        }
    }

    fun bridge(b: BridgeSnapshot): List<DiagnosticCheck> = buildList {
        if (!b.running) {
            add(check(BRIDGE, "Bridge", INFO, "stopped"))
        } else {
            val health = b.health
            add(when {
                health == null -> check(BRIDGE, "Bridge", INFO, "port ${b.port}, not probed")
                health.ok -> check(BRIDGE, "Bridge", PASS, "port ${b.port}, ${health.detail}")
                else -> check(BRIDGE, "Bridge", FAIL, "port ${b.port} did not answer (${health.detail})")
            })
        }
        if (b.tunnelEnabled) {
            add(when {
                b.tunnelOnline -> check(BRIDGE, "Public URL", PASS, "${b.tunnelUrl}/mcp")
                !b.running -> check(BRIDGE, "Public URL", INFO, "waits for the bridge")
                else -> check(BRIDGE, "Public URL", WARN, b.tunnelError ?: "connecting")
            })
        }
    }

    private fun probe(group: String, name: String, result: ProbeResult) =
        if (result.ok) check(group, name, PASS, "${result.detail} in ${result.millis} ms")
        else check(group, name, FAIL, result.detail)

    private fun check(group: String, name: String, status: CheckStatus, detail: String) =
        DiagnosticCheck(group, name, status, detail)
}
