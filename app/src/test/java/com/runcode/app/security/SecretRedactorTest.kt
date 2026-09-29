package com.runcode.app.security

import org.junit.Assert.*
import org.junit.Test

class SecretRedactorTest {
    @Test fun `registered values are redacted without labels`() {
        SecretRedactor.register("arbitrary test value & x")
        assertEquals("before [REDACTED] after", SecretRedactor.redact("before arbitrary test value & x after"))
    }

    @Test fun `overlapping values and replacement characters stay hidden`() {
        SecretRedactor.register("literal${'$'}value\\test")
        SecretRedactor.register("literal${'$'}value\\test-longer")
        assertEquals("[REDACTED]", SecretRedactor.redact("literal${'$'}value\\test-longer"))
    }
}
