package com.reelguard.app.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.reelguard.app.R
import com.reelguard.app.model.VerdictLevel
import com.reelguard.app.ui.MainActivity
import com.reelguard.app.util.PrefsManager

/**
 * Overlay Service — Floating bubble with progress feedback.
 *
 * Shows a color-coded bubble + status label:
 *   🔵 Pulsing blue  = Analyzing content...
 *   🟢 Green         = Content appears accurate
 *   🟡 Yellow        = Unverified / mixed signals
 *   🔴 Red           = Likely false or scam detected
 *   ⚪ Grey          = Idle / error
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "ReelGuardOverlay"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "reelguard_overlay"

        const val ACTION_SHOW = "com.reelguard.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.reelguard.HIDE_OVERLAY"
        const val ACTION_UPDATE = "com.reelguard.UPDATE_OVERLAY"

        const val EXTRA_PROCESSING = "processing"
        const val EXTRA_VERDICT = "verdict"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_ERROR = "error"
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var expandedView: View? = null
    private var dismissZoneView: View? = null
    private var isExpanded = false
    private var isDismissed = false

    // UI references inside the bubble
    private var indicatorView: View? = null
    private var statusLabel: TextView? = null
    private var pulseAnimator: ObjectAnimator? = null

    // Timer for showing elapsed time during processing
    private val handler = Handler(Looper.getMainLooper())
    private var processingStartTime = 0L
    private var timerRunnable: Runnable? = null

    private var currentSummary = "Tap bubble to check this content"
    private var currentVerdict = VerdictLevel.LEGIT  // Start as idle, not processing

    // ─── Service Lifecycle ───

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("ReelGuard is active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                isDismissed = false  // Reset dismiss state
                showBubble()
            }
            ACTION_HIDE -> hideBubble()
            ACTION_UPDATE -> {
                if (isDismissed) return START_STICKY  // Don't show updates if user dismissed
                val processing = intent.getBooleanExtra(EXTRA_PROCESSING, false)
                val error = intent.getBooleanExtra(EXTRA_ERROR, false)
                val verdict = intent.getStringExtra(EXTRA_VERDICT)
                val summary = intent.getStringExtra(EXTRA_SUMMARY)

                when {
                    processing -> startProcessingState()
                    error -> showResult(VerdictLevel.UNVERIFIED, "Could not verify — tap to retry")
                    verdict != null -> showResult(
                        VerdictLevel.valueOf(verdict),
                        summary ?: "No details"
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTimer()
        stopPulse()
        hideBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Processing State (pulsing + timer) ───

    private fun startProcessingState() {
        showBubble()
        currentVerdict = VerdictLevel.PROCESSING
        currentSummary = "Analyzing content..."

        // Update bubble color to blue
        val processingColor = 0xFF2196F3.toInt() // Blue
        indicatorView?.background = createCircleDrawable(processingColor)

        // Start pulse animation
        startPulse()

        // Start elapsed timer
        processingStartTime = System.currentTimeMillis()
        startTimer()

        // Update status label
        statusLabel?.text = "Analyzing..."
        statusLabel?.visibility = View.VISIBLE
        statusLabel?.setTextColor(0xFF2196F3.toInt())

        updateNotification("🔍 Analyzing content...")
    }

    private fun showResult(verdict: VerdictLevel, summary: String) {
        showBubble()
        stopPulse()
        stopTimer()

        currentVerdict = verdict
        currentSummary = summary

        val color = when (verdict) {
            VerdictLevel.LEGIT -> 0xFF4CAF50.toInt()        // Green
            VerdictLevel.UNVERIFIED -> 0xFFFFC107.toInt()   // Amber
            VerdictLevel.LIKELY_FALSE -> 0xFFF44336.toInt() // Red
            VerdictLevel.SCAM -> 0xFFD32F2F.toInt()         // Dark red
            VerdictLevel.PROCESSING -> 0xFF9E9E9E.toInt()   // Grey
        }

        indicatorView?.alpha = 1f
        indicatorView?.background = createCircleDrawable(color)

        // Update status label
        val labelText = when (verdict) {
            VerdictLevel.LEGIT -> "✓ Legit"
            VerdictLevel.UNVERIFIED -> "? Unverified"
            VerdictLevel.LIKELY_FALSE -> "✗ False"
            VerdictLevel.SCAM -> "⚠ Scam"
            VerdictLevel.PROCESSING -> "..."
        }
        statusLabel?.text = labelText
        statusLabel?.visibility = View.VISIBLE
        statusLabel?.setTextColor(color)

        val notifText = when (verdict) {
            VerdictLevel.LEGIT -> "✅ Content looks accurate"
            VerdictLevel.UNVERIFIED -> "⚠️ Claims unverified"
            VerdictLevel.LIKELY_FALSE -> "❌ Likely misinformation detected"
            VerdictLevel.SCAM -> "🚨 Possible scam detected!"
            VerdictLevel.PROCESSING -> "🔍 Checking..."
        }
        updateNotification(notifText)

        // Auto-collapse expanded view if showing old result
        if (isExpanded) {
            collapseExpanded()
        }

        // Reset to "tap to check" after 10 seconds so user can check next reel
        handler.postDelayed({
            if (currentVerdict == verdict) {  // Only reset if no new result came in
                currentSummary = "Tap bubble to check this content"
                currentVerdict = VerdictLevel.LEGIT
                indicatorView?.background = createCircleDrawable(0xFF9E9E9E.toInt())
                statusLabel?.text = "Tap to check"
                statusLabel?.setTextColor(0xFFFFFFFF.toInt())
                updateNotification("ReelGuard is active")
            }
        }, 10_000)
    }

    // ─── Pulse Animation ───

    private fun startPulse() {
        stopPulse()
        indicatorView?.let { view ->
            pulseAnimator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.3f, 1f).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        indicatorView?.alpha = 1f
    }

    // ─── Elapsed Timer ───

    private fun startTimer() {
        stopTimer()
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - processingStartTime) / 1000
                statusLabel?.text = "Analyzing... ${elapsed}s"
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(timerRunnable!!, 1000)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    // ─── Bubble UI ───

    private fun showBubble() {
        if (bubbleView != null) return
        if (isDismissed) return  // User dismissed, don't show until they reopen the app

        // Build bubble: circle indicator + status label below it
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val bubbleSize = dpToPx(44)
        val indicator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
            background = createCircleDrawable(0xFF9E9E9E.toInt())
            elevation = 8f
        }
        indicatorView = indicator
        container.addView(indicator)

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 10f
            setPadding(dpToPx(4), dpToPx(2), dpToPx(4), 0)
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            text = "Tap to check"
            visibility = View.VISIBLE
            setSingleLine(true)
        }
        statusLabel = label
        container.addView(label)

        bubbleView = container

        // Make draggable + tappable + dismissable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val screenHeight = resources.displayMetrics.heightPixels

        container.setOnTouchListener { view, event ->
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    showDismissZone()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    params.x = initialX - dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(view, params)

                    // Highlight dismiss zone when bubble is near bottom
                    val isNearBottom = event.rawY > screenHeight - dpToPx(120)
                    updateDismissZoneHighlight(isNearBottom)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideDismissZone()
                    // Check if dropped in dismiss zone
                    val isNearBottom = event.rawY > screenHeight - dpToPx(120)
                    if (isDragging && isNearBottom) {
                        dismissBubble()
                    } else if (!isDragging) {
                        onBubbleTapped()
                    }
                    true
                }
                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        try {
            windowManager?.addView(container, params)
            Log.i(TAG, "Bubble shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show bubble", e)
        }
    }

    private fun hideBubble() {
        stopPulse()
        stopTimer()
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            bubbleView = null
            indicatorView = null
            statusLabel = null
        }
        hideDismissZone()
        collapseExpanded()
    }

    // ─── Dismiss Zone (drag-to-close) ───

    /**
     * Show a dismiss zone at the bottom of the screen.
     * Appears when user starts dragging the bubble.
     */
    private fun showDismissZone() {
        if (dismissZoneView != null) return

        val screenWidth = resources.displayMetrics.widthPixels
        val zoneHeight = dpToPx(80)

        val zone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x60000000)

            val icon = TextView(this@OverlayService).apply {
                text = "✕"
                textSize = 22f
                setTextColor(0xAAFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            addView(icon)

            val label = TextView(this@OverlayService).apply {
                text = "Drag here to close"
                textSize = 12f
                setTextColor(0x99FFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(2), 0, 0)
            }
            addView(label)
        }

        val params = WindowManager.LayoutParams(
            screenWidth,
            zoneHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        try {
            dismissZoneView = zone
            windowManager?.addView(zone, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show dismiss zone", e)
        }
    }

    private fun updateDismissZoneHighlight(isNearBottom: Boolean) {
        dismissZoneView?.setBackgroundColor(
            if (isNearBottom) 0xA0FF0000.toInt() else 0x60000000
        )
    }

    private fun hideDismissZone() {
        dismissZoneView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            dismissZoneView = null
        }
    }

    /**
     * Dismiss the bubble. It stays hidden until the user reopens ReelGuard
     * or switches to a monitored app again.
     */
    private fun dismissBubble() {
        Log.i(TAG, "Bubble dismissed by user")
        isDismissed = true
        PrefsManager.setBubbleActive(this, false)
        hideBubble()
        updateNotification("ReelGuard paused — open app to resume")
    }

    // ─── Tap Handling ───

    /**
     * When bubble is tapped:
     * - If idle (no result yet): trigger fact-check of current screen content
     * - If processing: expand to show progress
     * - If showing a result: expand/collapse the detail card
     */
    private fun onBubbleTapped() {
        when (currentVerdict) {
            VerdictLevel.PROCESSING -> {
                // Already analyzing — show expanded card with progress
                toggleExpanded()
            }
            else -> {
                // Check if there's a result showing — if so, toggle card
                if (currentVerdict != VerdictLevel.PROCESSING && currentSummary != "Tap to see details" && currentSummary != "Tap bubble to check this content") {
                    toggleExpanded()
                    return
                }

                // No result yet — trigger analysis
                val service = ReelGuardAccessibilityService.instance
                if (service != null) {
                    Log.i(TAG, "User tapped bubble — triggering fact-check")
                    service.analyzeCurrentContent()
                } else {
                    Log.w(TAG, "Accessibility service not running")
                    statusLabel?.text = "Service off"
                    statusLabel?.visibility = View.VISIBLE
                }
            }
        }
    }

    // ─── Expanded Card ───

    private fun toggleExpanded() {
        if (isExpanded) {
            collapseExpanded()
        } else {
            showExpandedCard()
        }
    }

    private fun collapseExpanded() {
        expandedView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            expandedView = null
        }
        isExpanded = false
    }

    private fun showExpandedCard() {
        collapseExpanded()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            setBackgroundColor(0xF2FFFFFF.toInt())
            elevation = 16f
        }

        // Title with emoji
        val title = TextView(this).apply {
            text = when (currentVerdict) {
                VerdictLevel.LEGIT -> "✅ Looks Legit"
                VerdictLevel.UNVERIFIED -> "⚠️ Unverified"
                VerdictLevel.LIKELY_FALSE -> "❌ Likely False"
                VerdictLevel.SCAM -> "🚨 Scam Alert"
                VerdictLevel.PROCESSING -> "🔍 Checking..."
            }
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1A1A1A.toInt())
        }

        // Summary body
        val body = TextView(this).apply {
            text = currentSummary
            textSize = 13f
            setPadding(0, dpToPx(6), 0, 0)
            maxLines = 8
            setTextColor(0xFF444444.toInt())
            lineHeight = dpToPx(18)
        }

        // Elapsed time if still processing
        val elapsed = if (currentVerdict == VerdictLevel.PROCESSING) {
            val secs = (System.currentTimeMillis() - processingStartTime) / 1000
            "Elapsed: ${secs}s — this can take up to 60s"
        } else {
            "Tap bubble to close"
        }

        val footer = TextView(this).apply {
            text = elapsed
            textSize = 11f
            setPadding(0, dpToPx(8), 0, 0)
            setTextColor(0xFF999999.toInt())
        }

        card.addView(title)
        card.addView(body)
        card.addView(footer)

        val params = WindowManager.LayoutParams(
            dpToPx(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 280
        }

        try {
            expandedView = card
            windowManager?.addView(card, params)
            isExpanded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show expanded card", e)
        }
    }

    // ─── Helpers ───

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun createCircleDrawable(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(dpToPx(2), 0xFFFFFFFF.toInt())
        }
    }

    // ─── Notifications ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReelGuard Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when ReelGuard is monitoring content"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ReelGuard")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }
}