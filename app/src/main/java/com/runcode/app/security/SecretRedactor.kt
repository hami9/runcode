package com.runcode.app.security

import java.util.regex.Pattern

object SecretRedactor {

    // Common sensitive patterns: Telegram bot tokens (e.g. 123456789:ABCdef-GHIjkl...), API keys, JWT, passwords
    private val TELEGRAM_BOT_TOKEN = Pattern.compile("\\b(\\d{8,10}:[A-Za-z0-9_-]{35})\\b")
    private val GENERIC_API_KEY = Pattern.compile("(?i)\\b(api[_-]?key|secret|token|password|auth)['\"]?\\s*[:=]\\s*['\"]?([A-Za-z0-9_\\-.~!@#$%^&*+=]{8,})['\"]?")
    private val BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9_\\-./+=]{16,})")
    private val PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+ PRIVATE KEY-----")

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var sanitized = input

        // Mask Telegram Bot tokens: keep first 4 digits, mask rest
        sanitized = TELEGRAM_BOT_TOKEN.matcher(sanitized).replaceAll { mr ->
            val token = mr.group(1) ?: return@replaceAll "[REDACTED_BOT_TOKEN]"
            val colonIndex = token.indexOf(':')
            if (colonIndex != -1) {
                "${token.substring(0, minOf(4, colonIndex))}...:***REDACTED***"
            } else {
                "[REDACTED_BOT_TOKEN]"
            }
        }

        // Mask Bearer tokens
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer [REDACTED_TOKEN]")

        // Mask private keys
        sanitized = PRIVATE_KEY.matcher(sanitized).replaceAll("[REDACTED_PRIVATE_KEY]")

        // Mask generic key/secret assignments
        sanitized = GENERIC_API_KEY.matcher(sanitized).replaceAll { mr ->
            val key = mr.group(1) ?: "secret"
            "$key=***REDACTED***"
        }

        return sanitized
    }
}
