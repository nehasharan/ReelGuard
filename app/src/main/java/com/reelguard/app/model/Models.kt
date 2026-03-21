package com.reelguard.app.model

import java.util.UUID

// ─── Verdict from the fact-checking pipeline ───

enum class VerdictLevel {
    LEGIT,          // Green — claims appear accurate
    UNVERIFIED,     // Yellow — can't confirm or deny
    LIKELY_FALSE,   // Red — contradicted by reliable sources
    SCAM,           // Red+alert — known scam patterns detected
    PROCESSING      // Grey — still checking
}

data class Claim(
    val text: String,
    val verdict: VerdictLevel = VerdictLevel.PROCESSING,
    val explanation: String = "",
    val sources: List<String> = emptyList()
)

data class FactCheckResult(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val sourceApp: String,
    val extractedText: String,
    val claims: List<Claim>,
    val overallVerdict: VerdictLevel,
    val summary: String
)

// ─── User configuration: which apps to monitor ───

data class MonitoredApp(
    val packageName: String,
    val displayName: String,
    val isEnabled: Boolean = true
) {
    companion object {
        val DEFAULTS = listOf(
            MonitoredApp("com.instagram.android", "Instagram"),
            MonitoredApp("com.google.android.youtube", "YouTube"),
            MonitoredApp("com.twitter.android", "X (Twitter)"),
            MonitoredApp("com.zhiliaoapp.musically", "TikTok"),
            MonitoredApp("com.android.chrome", "Chrome"),
            MonitoredApp("com.brave.browser", "Brave Browser")
        )
    }
}

// ─── Content extracted from screen ───

data class ScreenContent(
    val appPackage: String,
    val texts: List<String>,       // All text nodes found on screen
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Combine all text into a single block for analysis */
    fun mergedText(): String = texts
        .filter { it.isNotBlank() && it.length > 10 } // Skip UI labels like "Like", "Share"
        .distinct()
        .joinToString("\n")

    /** Quick check: is there enough content worth fact-checking? */
    fun hasSubstantiveContent(): Boolean {
        val merged = mergedText()
        return merged.length > 50 && merged.split(" ").size > 10
    }
}
