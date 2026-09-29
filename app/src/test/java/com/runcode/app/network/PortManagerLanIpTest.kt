package com.runcode.app.network

import com.runcode.app.network.PortManager.Companion.selectLanIp
import com.runcode.app.network.PortManager.LanCandidate
import org.junit.Assert.*
import org.junit.Test

class PortManagerLanIpTest {
    private fun c(name: String, address: String, private: Boolean) = LanCandidate(name, address, private)

    @Test fun `wifi wins over mobile data listed first`() {
        val picked = selectLanIp(listOf(
            c("rmnet_data0", "21.91.237.60", false),
            c("wlan0", "192.168.1.23", true)
        ))
        assertEquals("192.168.1.23", picked)
    }

    @Test fun `mobile data alone is not a LAN address`() {
        assertNull(selectLanIp(listOf(c("rmnet_data0", "21.91.237.60", false), c("ccmni1", "10.12.0.4", true))))
    }

    @Test fun `vpn and 464xlat interfaces are skipped`() {
        assertNull(selectLanIp(listOf(c("tun0", "10.8.0.2", true), c("v4-rmnet_data0", "192.0.0.4", false))))
    }

    @Test fun `hotspot is used when wifi is off`() {
        assertEquals("192.168.43.1", selectLanIp(listOf(
            c("rmnet_data0", "21.91.237.60", false),
            c("swlan0", "192.168.43.1", true)
        )))
    }

    @Test fun `wifi beats usb tethering`() {
        assertEquals("192.168.1.23", selectLanIp(listOf(
            c("rndis0", "192.168.42.129", true),
            c("wlan0", "192.168.1.23", true)
        )))
    }

    @Test fun `unknown interface counts only with a private address`() {
        assertEquals("10.0.0.5", selectLanIp(listOf(c("mystery0", "10.0.0.5", true))))
        assertNull(selectLanIp(listOf(c("mystery0", "21.91.237.60", false))))
    }

    @Test fun `interface names are matched case-insensitively`() {
        assertEquals("192.168.1.23", selectLanIp(listOf(
            c("RMNET0", "21.91.237.60", false),
            c("WLAN0", "192.168.1.23", true)
        )))
    }
}
