package com.runcode.app.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CheckStatus { PASS, WARN, FAIL, INFO }

data class DiagnosticCheck(
    val group: String,
    val name: String,
    val status: CheckStatus,
    val detail: String
)

data class DiagnosticReport(
    val generatedAt: Long,
    val appVersion: String,
    val checks: List<DiagnosticCheck>
) {
    fun count(status: CheckStatus) = checks.count { it.status == status }

    val summary: String
        get() = "${count(CheckStatus.PASS)} pass, ${count(CheckStatus.WARN)} warn, ${count(CheckStatus.FAIL)} fail"

    /** Plain text meant to be pasted into an issue or a chat. */
    fun toText(): String = buildString {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(generatedAt))
        append("runcode diagnostics — $time — v$appVersion\n")
        append("Summary: $summary\n")
        val width = checks.maxOfOrNull { it.name.length } ?: 0
        checks.groupBy { it.group }.forEach { (group, items) ->
            append("\n[$group]\n")
            items.forEach { check ->
                append("  ${check.status.name.padEnd(4)}  ${check.name.padEnd(width)}  ${check.detail}\n")
            }
        }
    }
}
