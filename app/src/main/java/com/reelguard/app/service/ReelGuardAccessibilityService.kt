package com.reelguard.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelguard.app.model.ScreenContent
import com.reelguard.app.network.FactCheckClient
import com.reelguard.app.util.ContentDeduplicator
import com.reelguard.app.util.PrefsManager
import kotlinx.coroutines.*

/**
 * ReelGuard Accessibility Service
 *
 * This service:
 * 1. Shows the bubble when a monitored app is in foreground
 * 2. Silently captures visible text in the background (no API calls)
 * 3. Only sends text for fact-checking when user TAPS the bubble
 *
 * This is much more efficient — no wasted API calls on content
 * the user scrolls past quickly.
 */
class ReelGuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelGuardA11y"
        private const val TEXT_CAPTURE_DEBOUNCE_MS = 1000L

        var instance: ReelGuardAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val factCheckClient = FactCheckClient()
    private val deduplicator = ContentDeduplicator()

    private var lastCaptureTime = 0L
    private var currentAppPackage: String? = null
    private var processingJob: Job? = null

    // Cached screen content — updated on scroll, analyzed on tap
    @Volatile
    var currentScreenContent: ScreenContent? = null
        private set

    // ─── Lifecycle ───

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ReelGuard Accessibility Service connected")

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

        if (!isMonitoredApp(packageName)) return

        // Track app switches — show/hide bubble
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleAppSwitch(packageName)
            return
        }

        // Silently capture text (debounced) — NO API call here
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < TEXT_CAPTURE_DEBOUNCE_MS) return
        lastCaptureTime = now

        captureScreenText(packageName)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        processingJob?.cancel()
    }

    // ─── Silent Text Capture (no API call) ───

    private fun captureScreenText(packageName: String) {
        val rootNode = rootInActiveWindow ?: return

        val texts = mutableListOf<String>()
        extractTextsFromNode(rootNode, texts)
        rootNode.recycle()

        val content = ScreenContent(appPackage = packageName, texts = texts)

        if (content.hasSubstantiveContent()) {
            currentScreenContent = content
            Log.d(TAG, "Screen text captured (${content.mergedText().length} chars)")
        }
    }

    // ─── Fact-Check on Demand (called when user taps bubble) ───

    /**
     * Called by OverlayService when the user taps the bubble.
     *
     * Smart detection:
     * - Text post (lots of a11y text): skip audio, just quick OCR + text → fast (~5s)
     * - Video/reel (little a11y text): full OCR + 30s audio capture → thorough (~35s)
     */
    fun analyzeCurrentContent() {
        // Cancel previous analysis
        processingJob?.cancel()

        processingJob = serviceScope.launch {
            try {
                notifyOverlay(processing = true)

                val allTexts = mutableListOf<String>()

                // Source 1: Accessibility tree text (already captured)
                val a11yContent = currentScreenContent
                val a11yText = a11yContent?.mergedText() ?: ""

                if (a11yText.isNotBlank()) {
                    allTexts.add(a11yText)
                    Log.i(TAG, "A11y text: ${a11yText.take(100)}...")
                }

                // Decide: is this a text-heavy post or a video?
                // Text posts typically have 200+ chars of readable text
                // Videos/reels have mostly UI labels with little substantive text
                val isTextPost = a11yText.length > 200
                    && a11yText.split(" ").size > 30

                val captureService = MediaCaptureService.instance

                if (captureService != null) {
                    if (isTextPost) {
                        // TEXT POST: just do a quick OCR for any text in images, skip audio
                        Log.i(TAG, "Text post detected (${a11yText.length} chars) — quick OCR only, skipping audio")
                        try {
                            val ocrText = captureService.captureScreenAndOCR()
                            if (ocrText.isNotBlank()) {
                                allTexts.add("[Image text]: $ocrText")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Quick OCR failed", e)
                        }
                    } else {
                        // VIDEO/REEL: full OCR + 30s audio capture
                        Log.i(TAG, "Video/reel detected (${a11yText.length} chars) — full OCR + 30s audio")
                        val mediaText = captureService.captureAndExtractAll()
                        if (mediaText.isNotBlank()) {
                            allTexts.add(mediaText)
                            Log.i(TAG, "Media text: ${mediaText.take(100)}...")
                        }
                    }
                } else {
                    Log.d(TAG, "MediaCaptureService not running — text-only analysis")
                }

                // Combine all sources
                val combinedText = allTexts.joinToString("\n\n---\n\n")

                if (combinedText.isBlank() || combinedText.length < 20) {
                    Log.w(TAG, "Not enough content to analyze")
                    notifyOverlay(processing = false, error = true)
                    return@launch
                }

                val enrichedContent = com.reelguard.app.model.ScreenContent(
                    appPackage = currentAppPackage ?: "unknown",
                    texts = listOf(combinedText)
                )

                val contentHash = combinedText.hashCode().toString()
                if (deduplicator.isDuplicate(contentHash)) {
                    Log.d(TAG, "Already analyzed this content")
                }

                val mode = if (isTextPost) "text-post" else "video"
                Log.i(TAG, "Sending ${combinedText.length} chars ($mode mode) for fact-check")

                val result = factCheckClient.checkContent(enrichedContent)

                if (result != null) {
                    Log.i(TAG, "Verdict: ${result.overallVerdict} — ${result.summary}")
                    notifyOverlay(processing = false, result = result)
                } else {
                    Log.w(TAG, "Fact-check returned null")
                    notifyOverlay(processing = false, error = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during fact-check", e)
                notifyOverlay(processing = false, error = true)
            }
        }
    }

    // ─── Text Extraction ───

    private fun extractTextsFromNode(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        node ?: return

        node.text?.toString()?.let { text ->
            if (text.isNotBlank()) texts.add(text)
        }

        node.contentDescription?.toString()?.let { desc ->
            if (desc.isNotBlank() && desc.length > 15) texts.add("[desc] $desc")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            extractTextsFromNode(child, texts)
            child?.recycle()
        }
    }

    // ─── App Filtering ───

    private fun isMonitoredApp(packageName: String): Boolean {
        val monitoredApps = PrefsManager.getMonitoredApps(this)
        return monitoredApps.any { it.packageName == packageName && it.isEnabled }
    }

    private fun handleAppSwitch(newPackage: String) {
        if (newPackage != currentAppPackage) {
            currentAppPackage = newPackage
            Log.d(TAG, "App switched to: $newPackage")

            if (isMonitoredApp(newPackage)) {
                startOverlayService()
            } else {
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
