package com.reelguard.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelguard.app.model.MonitoredApp
import com.reelguard.app.model.ScreenContent
import com.reelguard.app.network.FactCheckClient
import com.reelguard.app.util.ContentDeduplicator
import com.reelguard.app.util.PrefsManager
import kotlinx.coroutines.*

/**
 * ReelGuard Accessibility Service
 *
 * This is the core agent. It:
 * 1. Listens for screen content changes in user-approved apps
 * 2. Extracts visible text from the screen's view hierarchy
 * 3. Deduplicates to avoid re-checking the same content
 * 4. Sends novel claims to the fact-checking backend
 * 5. Notifies the OverlayService to update the floating bubble
 *
 * PRIVACY: Only activates for apps the user explicitly enables.
 * Raw screen content is never stored — only extracted text is sent to the API.
 */
class ReelGuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelGuardA11y"

        // Debounce: don't process events faster than this
        private const val DEBOUNCE_MS = 2000L

        // Singleton ref so other components can check if service is running
        var instance: ReelGuardAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val factCheckClient = FactCheckClient()
    private val deduplicator = ContentDeduplicator()

    private var lastProcessedTime = 0L
    private var currentAppPackage: String? = null
    private var processingJob: Job? = null

    // ─── Lifecycle ───

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ReelGuard Accessibility Service connected")

        // Dynamically configure which events we care about
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 500
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        Log.i(TAG, "ReelGuard Accessibility Service destroyed")
    }

    // ─── Event Handling ───

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return

        // PRIVACY GATE: Only process events from user-approved apps
        if (!isMonitoredApp(packageName)) return

        // Track which app is in foreground
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleAppSwitch(packageName)
            return
        }

        // Debounce rapid events (scrolling generates many)
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < DEBOUNCE_MS) return
        lastProcessedTime = now

        // Cancel previous processing if still running
        processingJob?.cancel()

        // Extract and process screen content
        processingJob = serviceScope.launch {
            try {
                processScreenContent(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing screen content", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        processingJob?.cancel()
    }

    // ─── Content Extraction ───

    /**
     * Walk the view hierarchy and extract all visible text.
     * This is how we "see" what's on screen without taking screenshots.
     */
    private suspend fun processScreenContent(packageName: String) {
        val rootNode = rootInActiveWindow ?: return

        val texts = mutableListOf<String>()
        extractTextsFromNode(rootNode, texts)
        rootNode.recycle()

        val screenContent = ScreenContent(
            appPackage = packageName,
            texts = texts
        )

        // Skip if not enough substantive content
        if (!screenContent.hasSubstantiveContent()) {
            Log.d(TAG, "Skipping — not enough substantive content")
            return
        }

        // Skip if we've already checked this content
        val contentHash = screenContent.mergedText().hashCode().toString()
        if (deduplicator.isDuplicate(contentHash)) {
            Log.d(TAG, "Skipping — duplicate content")
            return
        }

        Log.i(TAG, "New content detected in $packageName, sending for fact-check")
        Log.d(TAG, "Content preview: ${screenContent.mergedText().take(200)}...")

        // Update overlay to show "checking..." state
        notifyOverlay(processing = true)

        // Send to fact-checking backend
        val result = factCheckClient.checkContent(screenContent)

        if (result != null) {
            Log.i(TAG, "Verdict: ${result.overallVerdict} — ${result.summary}")
            notifyOverlay(processing = false, result = result)
        } else {
            Log.w(TAG, "Fact-check returned null (API error or timeout)")
            notifyOverlay(processing = false, error = true)
        }
    }

    /**
     * Recursively traverse the accessibility node tree and collect text.
     * Filters out very short strings (likely UI buttons) to reduce noise.
     */
    private fun extractTextsFromNode(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        node ?: return

        // Extract text from this node
        node.text?.toString()?.let { text ->
            if (text.isNotBlank()) {
                texts.add(text)
            }
        }

        // Also check content description (used for image alt text, etc.)
        node.contentDescription?.toString()?.let { desc ->
            if (desc.isNotBlank() && desc.length > 15) {
                texts.add("[desc] $desc")
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            extractTextsFromNode(child, texts)
            child?.recycle()
        }
    }

    // ─── App Filtering ───

    private fun isMonitoredApp(packageName: String): Boolean {
        // Check against user's configured list of apps
        val monitoredApps = PrefsManager.getMonitoredApps(this)
        return monitoredApps.any { it.packageName == packageName && it.isEnabled }
    }

    private fun handleAppSwitch(newPackage: String) {
        if (newPackage != currentAppPackage) {
            currentAppPackage = newPackage
            Log.d(TAG, "App switched to: $newPackage")

            if (isMonitoredApp(newPackage)) {
                // Show overlay when entering a monitored app
                startOverlayService()
            } else {
                // Hide overlay when leaving monitored apps
                hideOverlay()
            }
        }
    }

    // ─── Overlay Communication ───

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        }
        startForegroundService(intent)
    }

    private fun hideOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE
        }
        startService(intent)
    }

    private fun notifyOverlay(
        processing: Boolean = false,
        result: com.reelguard.app.model.FactCheckResult? = null,
        error: Boolean = false
    ) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE
            putExtra(OverlayService.EXTRA_PROCESSING, processing)
            putExtra(OverlayService.EXTRA_ERROR, error)
            result?.let {
                putExtra(OverlayService.EXTRA_VERDICT, it.overallVerdict.name)
                putExtra(OverlayService.EXTRA_SUMMARY, it.summary)
            }
        }
        startService(intent)
    }
}
