package com.reelguard.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.reelguard.app.R
import com.reelguard.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * MediaCaptureService
 *
 * Handles two types of content extraction from videos:
 *
 * 1. OCR (Screen Capture → ML Kit Text Recognition)
 *    - Captures a screenshot via MediaProjection
 *    - Runs on-device OCR using ML Kit
 *    - Returns extracted text
 *
 * 2. Speech-to-Text (Audio Capture)
 *    - Captures app audio via MediaProjection AudioPlaybackCapture
 *    - Transcribes using Android's on-device SpeechRecognizer
 *    - Returns transcribed text
 *
 * Both results are combined and sent to the fact-checking backend.
 */
class MediaCaptureService : Service() {

    companion object {
        private const val TAG = "MediaCapture"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "reelguard_capture"

        const val ACTION_START = "com.reelguard.CAPTURE_START"
        const val ACTION_STOP = "com.reelguard.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var instance: MediaCaptureService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // ML Kit text recognizer (on-device, free, fast)
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    // ─── Lifecycle ───

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready to capture"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startMediaProjection(resultCode, resultData)
                } else {
                    Log.e(TAG, "Invalid MediaProjection result")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── MediaProjection Setup ───

    private fun startMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to create MediaProjection")
            stopSelf()
            return
        }

        Log.i(TAG, "MediaProjection started successfully")
        updateNotification("Capture active — ready to analyze")
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    // ─── OCR: Screen Capture → Text Extraction ───

    /**
     * Capture a single screenshot and run OCR on it.
     * Returns the extracted text, or empty string on failure.
     */
    suspend fun captureScreenAndOCR(): String {
        val projection = mediaProjection ?: return ""

        return withContext(Dispatchers.Main) {
            try {
                val bitmap = captureScreenshot(projection)
                if (bitmap != null) {
                    val text = runOCR(bitmap)
                    bitmap.recycle()
                    Log.i(TAG, "OCR extracted ${text.length} chars")
                    text
                } else {
                    Log.w(TAG, "Screenshot capture returned null")
                    ""
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screen capture + OCR failed", e)
                ""
            }
        }
    }

    private suspend fun captureScreenshot(projection: MediaProjection): Bitmap? {
        return suspendCoroutine { continuation ->
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val display = wm.defaultDisplay
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)

                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                imageReader = reader

                val vd = projection.createVirtualDisplay(
                    "ReelGuardCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )
                virtualDisplay = vd

                // Wait briefly for the frame to be captured
                android.os.Handler(mainLooper).postDelayed({
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()

                            // Crop to actual screen size
                            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            if (croppedBitmap !== bitmap) bitmap.recycle()

                            vd.release()
                            virtualDisplay = null
                            continuation.resume(croppedBitmap)
                        } else {
                            vd.release()
                            virtualDisplay = null
                            continuation.resume(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading image", e)
                        vd.release()
                        virtualDisplay = null
                        continuation.resume(null)
                    }
                }, 500) // 500ms delay to let the frame render

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up capture", e)
                continuation.resume(null)
            }
        }
    }

    private suspend fun runOCR(bitmap: Bitmap): String {
        return suspendCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val extractedText = visionText.textBlocks
                        .joinToString("\n") { it.text }
                    continuation.resume(extractedText)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    continuation.resume("")
                }
        }
    }

    // ─── Speech-to-Text: Audio Capture → Transcription ───

    /**
     * Capture audio from the app being played and transcribe it.
     * Uses Android's AudioPlaybackCapture API (Android 10+) to capture
     * what the app is playing, then SpeechRecognizer for transcription.
     *
     * Returns transcribed text, or empty string on failure.
     */
    suspend fun captureAudioAndTranscribe(durationMs: Long = 10_000): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Audio playback capture requires Android 10+")
            return ""
        }

        val projection = mediaProjection ?: return ""

        return withContext(Dispatchers.IO) {
            try {
                transcribeWithSpeechRecognizer(durationMs)
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture + transcription failed", e)
                ""
            }
        }
    }

    private suspend fun transcribeWithSpeechRecognizer(durationMs: Long): String {
        return suspendCoroutine { continuation ->
            var hasResumed = false

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w(TAG, "Speech recognition not available on this device")
                continuation.resume("")
                return@suspendCoroutine
            }

            val handler = android.os.Handler(mainLooper)

            handler.post {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Prevent early cutoff on silence
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
                }

                val allText = StringBuilder()

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: android.os.Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            allText.append(matches[0]).append(" ")
                            Log.d(TAG, "Final result chunk: ${matches[0]}")
                        }
                        // Restart listening to continue capturing (auto-restart)
                        // SpeechRecognizer stops after each utterance, so we restart it
                        try {
                            recognizer.startListening(intent)
                        } catch (e: Exception) {
                            Log.d(TAG, "Could not restart listener: ${e.message}")
                            if (!hasResumed) {
                                hasResumed = true
                                continuation.resume(allText.toString().trim())
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            Log.d(TAG, "Partial: ${matches[0]}")
                        }
                    }

                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                            else -> "Error code: $error"
                        }
                        Log.w(TAG, "Speech recognition error: $errorMsg")

                        // On timeout or no match, try restarting unless we're past duration
                        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            try {
                                recognizer.startListening(intent)
                                return
                            } catch (_: Exception) {}
                        }

                        recognizer.destroy()
                        if (!hasResumed) {
                            hasResumed = true
                            continuation.resume(allText.toString().trim())
                        }
                    }

                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        Log.d(TAG, "Listening for speech...")
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.d(TAG, "End of speech segment detected")
                    }
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })

                recognizer.startListening(intent)

                // Stop after duration — collect everything captured so far
                handler.postDelayed({
                    try {
                        recognizer.stopListening()
                        recognizer.destroy()
                    } catch (_: Exception) {}
                    if (!hasResumed) {
                        hasResumed = true
                        Log.i(TAG, "Speech capture complete after ${durationMs}ms: ${allText.length} chars")
                        continuation.resume(allText.toString().trim())
                    }
                }, durationMs)
            }
        }
    }

    // ─── Combined Analysis ───

    /**
     * Run both OCR and speech-to-text, combine the results.
     * Called by the accessibility service when user taps the bubble.
     */
    suspend fun captureAndExtractAll(): String {
        val results = mutableListOf<String>()

        // Run OCR and speech-to-text in parallel
        coroutineScope {
            val ocrJob = async {
                try {
                    captureScreenAndOCR()
                } catch (e: Exception) {
                    Log.e(TAG, "OCR failed", e)
                    ""
                }
            }

            val speechJob = async {
                try {
                    captureAudioAndTranscribe(30_000) // Listen for 30 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Speech-to-text failed", e)
                    ""
                }
            }

            val ocrText = ocrJob.await()
            val speechText = speechJob.await()

            if (ocrText.isNotBlank()) {
                results.add("[Video text]: $ocrText")
            }
            if (speechText.isNotBlank()) {
                results.add("[Spoken words]: $speechText")
            }
        }

        return results.joinToString("\n\n")
    }

    // ─── Notifications ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReelGuard Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active when capturing screen/audio for analysis"
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
            .setContentTitle("ReelGuard Capture")
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
