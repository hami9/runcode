package com.runcode.app.diagnostics

import com.runcode.app.domain.models.ProjectProfile
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRulesTest {

    private val ok = ProbeResult(true, "HTTP 204", 40)
    private val down = ProbeResult(false, "connect timed out", 6000)

    private val healthyDevice = DeviceSnapshot(
        apiLevel = 36, release = "16", abis = listOf("arm64-v8a"),
        freeStorageMb = 4000, availableMemoryMb = 3000, totalMemoryMb = 8000, lowMemory = false,
        notificationsAllowed = true, batteryExempt = true, pythonVersion = "3.12.4",
        lastCrash = null, inForeground = false, foregroundWork = 0
    )

    private val healthyNetwork = NetworkSnapshot(
        transports = listOf("Wi-Fi"), validated = true, vpn = false, lanIp = "192.168.1.5",
        reservedPorts = mapOf(8080 to "svc"), dns = ok, https = ok, telegram = ok
    )

    private fun List<DiagnosticCheck>.status(name: String) = single { it.name == name }.status

    @Test fun `a healthy device has no warnings or failures`() {
        val checks = DiagnosticRules.device(healthyDevice) + DiagnosticRules.network(healthyNetwork)
        assertTrue(checks.joinToString("\n"), checks.none { it.status == CheckStatus.WARN || it.status == CheckStatus.FAIL })
    }

    @Test fun `blocked notifications and battery optimisation are warnings`() {
        val checks = DiagnosticRules.device(healthyDevice.copy(notificationsAllowed = false, batteryExempt = false))
        assertEquals(CheckStatus.WARN, checks.status("Notifications"))
        assertEquals(CheckStatus.WARN, checks.status("Battery"))
    }

    @Test fun `running work without the foreground service fails`() {
        val checks = DiagnosticRules.device(healthyDevice.copy(foregroundWork = 2, inForeground = false))
        assertEquals(CheckStatus.FAIL, checks.status("Foreground"))
        assertEquals(CheckStatus.PASS, DiagnosticRules.device(healthyDevice.copy(foregroundWork = 2, inForeground = true)).status("Foreground"))
        assertEquals(CheckStatus.INFO, DiagnosticRules.device(healthyDevice).status("Foreground"))
    }

    @Test fun `storage thresholds`() {
        assertEquals(CheckStatus.WARN, DiagnosticRules.device(healthyDevice.copy(freeStorageMb = 150)).status("Storage"))
        assertEquals(CheckStatus.FAIL, DiagnosticRules.device(healthyDevice.copy(freeStorageMb = 20)).status("Storage"))
    }

    @Test fun `missing python fails`() {
        assertEquals(CheckStatus.FAIL, DiagnosticRules.device(healthyDevice.copy(pythonVersion = null)).status("Python"))
    }

    @Test fun `crash summary shows the time and the exception line`() {
        val report = """
            runcode crash report
            time:    2026-09-30 10:11:12
            thread:  main
            device:  Samsung SM-A546E
            android: 16 (API 36)
            abi:     arm64-v8a

            java.lang.IndexOutOfBoundsException: Index 1 out of bounds for length 1
            	at androidx.compose.material3.TabRowKt.foo(TabRow.kt:12)
        """.trimIndent()
        assertEquals(
            "2026-09-30 10:11:12 · java.lang.IndexOutOfBoundsException: Index 1 out of bounds for length 1",
            DiagnosticRules.crashSummary(report)
        )
        assertEquals(CheckStatus.WARN, DiagnosticRules.device(healthyDevice.copy(lastCrash = report)).status("Last crash"))
    }

    @Test fun `no network at all fails, unvalidated warns`() {
        assertEquals(CheckStatus.FAIL, DiagnosticRules.network(healthyNetwork.copy(transports = null)).status("Connection"))
        assertEquals(CheckStatus.WARN, DiagnosticRules.network(healthyNetwork.copy(validated = false)).status("Connection"))
    }

    @Test fun `a blocked Telegram API only warns, broken DNS fails`() {
        val checks = DiagnosticRules.network(healthyNetwork.copy(telegram = down, dns = down))
        assertEquals(CheckStatus.WARN, checks.status("Telegram API"))
        assertEquals(CheckStatus.FAIL, checks.status("DNS"))
    }

    @Test fun `vpn is reported`() {
        val checks = DiagnosticRules.network(healthyNetwork.copy(vpn = true, transports = listOf("Wi-Fi", "VPN")))
        assertEquals(CheckStatus.INFO, checks.status("VPN"))
        assertTrue(checks.single { it.name == "Connection" }.detail.startsWith("Wi-Fi + VPN"))
    }

    @Test fun `only web profiles are judged by their port`() {
        val checks = DiagnosticRules.services(listOf(
            ServiceSnapshot("site", ProjectProfile.STATIC_WEB, 8080, ProbeResult(false, "Connection refused", 1)),
            ServiceSnapshot("api", ProjectProfile.PYTHON_HTTP, 8081, ProbeResult(true, "accepts connections", 1)),
            ServiceSnapshot("bot", ProjectProfile.TELEGRAM_BOT, 8082, null)
        ))
        assertEquals(CheckStatus.FAIL, checks.status("site"))
        assertEquals(CheckStatus.PASS, checks.status("api"))
        assertEquals(CheckStatus.INFO, checks.status("bot"))
    }

    @Test fun `bridge health and tunnel state`() {
        val bridge = BridgeSnapshot(true, 8765, ProbeResult(false, "Connection refused", 1), true, false, null, "Pinggy: timeout")
        val checks = DiagnosticRules.bridge(bridge)
        assertEquals(CheckStatus.FAIL, checks.status("Bridge"))
        assertEquals(CheckStatus.WARN, checks.status("Public URL"))
        val online = DiagnosticRules.bridge(bridge.copy(health = ok, tunnelOnline = true, tunnelUrl = "https://x.run.pinggy-free.link"))
        assertEquals("https://x.run.pinggy-free.link/mcp", online.single { it.name == "Public URL" }.detail)
    }

    @Test fun `report text groups checks and counts them`() {
        val report = DiagnosticReport(0, "1.5.0 (6)", DiagnosticRules.device(healthyDevice.copy(batteryExempt = false)))
        val text = report.toText()
        assertTrue(text.contains("v1.5.0 (6)"))
        assertTrue(text.contains("[App]"))
        assertTrue(text.contains("WARN  Battery"))
        assertEquals(1, report.count(CheckStatus.WARN))
    }
}
