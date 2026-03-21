package com.reelguard.app.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.reelguard.app.R
import com.reelguard.app.model.VerdictLevel
import com.reelguard.app.ui.MainActivity

/**
 * Overlay Service — Manages the floating bubble that shows fact-check verdicts.
 *
 * Displays a small draggable bubble on top of other apps. The bubble changes
 * color based on the verdict:
 *   🟢 Green  = Content appears accurate
 *   🟡 Yellow = Unverified / mixed signals
 *   🔴 Red    = Likely false or scam detected
 *   ⚪ Grey   = Processing / checking
 *
 * Tapping the bubble expands it to show the full summary.
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
    private var isExpanded = false

    // ─── Service Lifecycle ───

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("ReelGuard is watching for misinformation"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubble()
            ACTION_UPDATE -> {
                val processing = intent.getBooleanExtra(EXTRA_PROCESSING, false)
                val error = intent.getBooleanExtra(EXTRA_ERROR, false)
                val verdict = intent.getStringExtra(EXTRA_VERDICT)
                val summary = intent.getStringExtra(EXTRA_SUMMARY)

                when {
                    processing -> updateBubble(VerdictLevel.PROCESSING, "Checking claims...")
                    error -> updateBubble(VerdictLevel.UNVERIFIED, "Could not verify")
                    verdict != null -> updateBubble(
                        VerdictLevel.valueOf(verdict),
                        summary ?: "No details"
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Bubble UI ───

    private fun showBubble() {
        if (bubbleView != null) return // Already showing

        bubbleView = createBubbleView()

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
            windowManager?.addView(bubbleView, params)
            Log.i(TAG, "Bubble shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show bubble — missing overlay permission?", e)
        }
    }

    private fun hideBubble() {
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            bubbleView = null
        }
        expandedView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            expandedView = null
        }
        isExpanded = false
    }

    private fun createBubbleView(): View {
        // Programmatic layout for the floating bubble
        // In production, inflate from R.layout.overlay_bubble
        val size = dpToPx(48)
        val frame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
        }

        val indicator = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = createCircleDrawable(0xFF9E9E9E.toInt()) // Grey = idle
            elevation = 8f
        }
        frame.addView(indicator)

        // Make bubble draggable + tappable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        frame.setOnTouchListener { view, event ->
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    params.x = initialX - dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggleExpanded()
                    true
                }
                else -> false
            }
        }

        return frame
    }

    /**
     * Update the bubble color and cached summary text.
     */
    private fun updateBubble(verdict: VerdictLevel, summary: String) {
        val color = when (verdict) {
            VerdictLevel.LEGIT -> 0xFF4CAF50.toInt()        // Green
            VerdictLevel.UNVERIFIED -> 0xFFFFC107.toInt()   // Amber
            VerdictLevel.LIKELY_FALSE -> 0xFFF44336.toInt() // Red
            VerdictLevel.SCAM -> 0xFFD32F2F.toInt()         // Dark red
            VerdictLevel.PROCESSING -> 0xFF9E9E9E.toInt()   // Grey
        }

        bubbleView?.let { view ->
            val frame = view as? FrameLayout ?: return
            val indicator = frame.getChildAt(0)
            indicator?.background = createCircleDrawable(color)
        }

        // Store for expanded view
        currentSummary = summary
        currentVerdict = verdict

        // Update notification too
        val notifText = when (verdict) {
            VerdictLevel.LEGIT -> "✅ Content looks accurate"
            VerdictLevel.UNVERIFIED -> "⚠️ Claims unverified"
            VerdictLevel.LIKELY_FALSE -> "❌ Likely misinformation detected"
            VerdictLevel.SCAM -> "🚨 Possible scam detected!"
            VerdictLevel.PROCESSING -> "🔍 Checking claims..."
        }
        updateNotification(notifText)
    }

    private var currentSummary = "Tap to see details"
    private var currentVerdict = VerdictLevel.PROCESSING

    /**
     * Toggle between compact bubble and expanded card view.
     */
    private fun toggleExpanded() {
        if (isExpanded) {
            expandedView?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
                expandedView = null
            }
            isExpanded = false
        } else {
            showExpandedCard()
            isExpanded = true
        }
    }

    private fun showExpandedCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            setBackgroundColor(0xF0FFFFFF.toInt())
            elevation = 16f
        }

        val title = TextView(this).apply {
            text = when (currentVerdict) {
                VerdictLevel.LEGIT -> "✅ Looks Legit"
                VerdictLevel.UNVERIFIED -> "⚠️ Unverified"
                VerdictLevel.LIKELY_FALSE -> "❌ Likely False"
                VerdictLevel.SCAM -> "🚨 Scam Alert"
                VerdictLevel.PROCESSING -> "🔍 Checking..."
            }
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val body = TextView(this).apply {
            text = currentSummary
            textSize = 13f
            setPadding(0, dpToPx(4), 0, 0)
            maxLines = 6
        }

        val dismiss = TextView(this).apply {
            text = "Tap bubble to close"
            textSize = 11f
            setPadding(0, dpToPx(8), 0, 0)
            setTextColor(0xFF999999.toInt())
        }

        card.addView(title)
        card.addView(body)
        card.addView(dismiss)

        val params = WindowManager.LayoutParams(
            dpToPx(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 260
        }

        try {
            expandedView = card
            windowManager?.addView(card, params)
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
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
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
            .setSmallIcon(R.drawable.ic_shield) // You'll create this icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
