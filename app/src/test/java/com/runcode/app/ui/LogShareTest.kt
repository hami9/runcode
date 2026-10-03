package com.runcode.app.ui

import android.content.Intent
import android.net.Uri
import com.runcode.app.RuncodeApp
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.security.SecretRedactor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RuncodeApp::class)
class LogShareTest {

    @Test fun `shared logs go through the file provider with secrets redacted`() {
        val app = RuntimeEnvironment.getApplication() as RuncodeApp
        SecretRedactor.register("bot-token-123456:ABCDEF")
        app.logManager.log("p1", "Bot", LogLevel.INFO, "connecting with bot-token-123456:ABCDEF")
        app.logManager.log("p1", "Bot", LogLevel.WARN, "second line")

        val chooser = MainViewModel(app).logShareIntent()
        assertNotNull(chooser)
        @Suppress("DEPRECATION")
        val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.files", uri.authority)

        val text = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertTrue(text.contains("second line"))
        assertTrue(text.contains("[REDACTED]"))
        assertFalse(text.contains("bot-token-123456:ABCDEF"))
    }
}
