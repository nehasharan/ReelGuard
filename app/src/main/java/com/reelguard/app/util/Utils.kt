package com.reelguard.app.util

import android.content.Context
import android.content.SharedPreferences
import com.reelguard.app.model.MonitoredApp
import java.util.LinkedHashMap

/**
 * Content Deduplicator
 *
 * Keeps track of recently-seen content hashes to avoid re-checking
 * the same reel/post multiple times. Uses an LRU cache that evicts
 * the oldest entries when full.
 */
class ContentDeduplicator(private val maxSize: Int = 100) {

    // LRU cache: oldest entries get evicted when maxSize is exceeded
    private val seen = object : LinkedHashMap<String, Long>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > maxSize
        }
    }

    /**
     * Check if this content hash was already processed recently.
     * If not, marks it as seen and returns false.
     */
    fun isDuplicate(contentHash: String): Boolean {
        synchronized(seen) {
            if (seen.containsKey(contentHash)) return true
            seen[contentHash] = System.currentTimeMillis()
            return false
        }
    }

    fun clear() {
        synchronized(seen) { seen.clear() }
    }
}

/**
 * Preferences Manager
 *
 * Stores user configuration: which apps to monitor, toggle state, etc.
 */
object PrefsManager {

    private const val PREFS_NAME = "reelguard_prefs"
    private const val KEY_ENABLED = "agent_enabled"
    private const val KEY_BUBBLE_ACTIVE = "bubble_active"
    private const val KEY_MONITORED_PREFIX = "monitored_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── Agent Toggle ───

    fun isAgentEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setAgentEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ─── Bubble State ───
    // Tracks whether the bubble should be shown.
    // false by default (user hasn't opened app yet)
    // true when user opens the app with agent enabled
    // false when user dismisses the bubble

    fun isBubbleActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE_ACTIVE, false)

    fun setBubbleActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_ACTIVE, active).apply()
    }

    // ─── Monitored Apps ───

    fun getMonitoredApps(context: Context): List<MonitoredApp> {
        val prefs = prefs(context)
        return MonitoredApp.DEFAULTS.map { app ->
            app.copy(
                isEnabled = prefs.getBoolean(
                    "$KEY_MONITORED_PREFIX${app.packageName}",
                    app.isEnabled
                )
            )
        }
    }

    fun setAppMonitored(context: Context, packageName: String, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean("$KEY_MONITORED_PREFIX$packageName", enabled)
            .apply()
    }
}