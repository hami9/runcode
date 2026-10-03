package com.runcode.app.diagnostics

import com.runcode.app.RuncodeApp
import com.runcode.app.mcp.McpTools
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket

/** The whole runner against the real application object and a real bridge listener. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RuncodeApp::class)
class DiagnosticsRunnerTest {

    private val app get() = RuntimeEnvironment.getApplication() as RuncodeApp

    @After fun tearDown() {
        app.mcpServer.stop()
    }

    @Test fun `report covers every group and self-tests a running bridge`() {
        val port = ServerSocket(0).use { it.localPort }
        app.mcpServer.start(port, "test-token", allowLan = false)
        assertTrue(app.mcpServer.state.value.isRunning)

        val report = app.diagnostics.run()
        println(report.toText())
        val groups = report.checks.map { it.group }.toSet()
        assertEquals(setOf(DiagnosticRules.APP, DiagnosticRules.NETWORK, DiagnosticRules.SERVICES, DiagnosticRules.BRIDGE), groups)

        val bridge = report.checks.single { it.name == "Bridge" }
        assertEquals(bridge.detail, CheckStatus.PASS, bridge.status)
        // Chaquopy does not run on the JVM, and the report must say so rather than pretend.
        assertEquals(CheckStatus.FAIL, report.checks.single { it.name == "Python" }.status)
        // The bridge is running but nothing put the app in the foreground in this test.
        assertEquals(CheckStatus.FAIL, report.checks.single { it.name == "Foreground" }.status)
    }

    @Test fun `stopped bridge is reported as information, not failure`() {
        val bridge = app.diagnostics.run().checks.single { it.name == "Bridge" }
        assertEquals(CheckStatus.INFO, bridge.status)
    }

    @Test fun `run_diagnostics is listed and callable over MCP`() {
        val names = (0 until McpTools.descriptors().length()).map { McpTools.descriptors().getJSONObject(it).getString("name") }
        assertTrue("run_diagnostics" in names)
    }
}
