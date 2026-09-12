package com.runcode.app.security

import java.util.regex.Matcher
import java.util.regex.Pattern

object SecretRedactor {

    // Common sensitive patterns: Telegram bot tokens (e.g. 123456789:ABCdef-GHIjkl...), API keys, JWT, passwords
    private val TELEGRAM_BOT_TOKEN = Pattern.compile("\\b(\\d{8,10}:[A-Za-z0-9_-]{35})\\b")
    private val GENERIC_API_KEY = Pattern.compile("(?i)\\b(api[_-]?key|secret|token|password|auth)['\"]?\\s*[:=]\\s*['\"]?([A-Za-z0-9_\\-.~!@#\$%^&*+=]{8,})['\"]?")
    private val BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9_\\-./+=]{16,})")
    private val PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+ PRIVATE KEY-----")

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var sanitized = input

        // Mask Telegram Bot tokens: keep first 4 digits, mask rest
        sanitized = replaceAll(TELEGRAM_BOT_TOKEN, sanitized) { matcher ->
            val token = matcher.group(1) ?: return@replaceAll "[REDACTED_BOT_TOKEN]"
            val colonIndex = token.indexOf(':')
            if (colonIndex != -1) {
                "${token.substring(0, minOf(4, colonIndex))}...:***REDACTED***"
            } else {
                "[REDACTED_BOT_TOKEN]"
            }
        }

        // Mask Bearer tokens
        sanitized = replaceAll(BEARER_TOKEN, sanitized) { "Bearer [REDACTED_TOKEN]" }

        // Mask private keys
        sanitized = replaceAll(PRIVATE_KEY, sanitized) { "[REDACTED_PRIVATE_KEY]" }

        // Mask generic key/secret assignments
        sanitized = replaceAll(GENERIC_API_KEY, sanitized) { matcher ->
            "${matcher.group(1) ?: "secret"}=***REDACTED***"
        }

        return sanitized
    }

    /**
     * Equivalent of Matcher.replaceAll(Function), which is only available from API 34.
     * The value returned by [replacement] is inserted literally, never interpreted as
     * a replacement expression, so group references and backslashes are safe.
     */
    private inline fun replaceAll(pattern: Pattern, input: String, replacement: (Matcher) -> String): String {
        val matcher = pattern.matcher(input)
        if (!matcher.find()) return input

        val builder = StringBuffer(input.length)
        do {
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement(matcher)))
        } while (matcher.find())
        matcher.appendTail(builder)
        return builder.toString()
    }
}
