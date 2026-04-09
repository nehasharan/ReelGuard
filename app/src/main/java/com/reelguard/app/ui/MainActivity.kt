package com.reelguard.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.reelguard.app.R
import com.reelguard.app.databinding.ActivityMainBinding
import com.reelguard.app.service.MediaCaptureService
import com.reelguard.app.service.ReelGuardAccessibilityService
import com.reelguard.app.util.PrefsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // MediaProjection permission launcher
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start the MediaCaptureService with the projection result
            val intent = Intent(this, MediaCaptureService::class.java).apply {
                action = MediaCaptureService.ACTION_START
                putExtra(MediaCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MediaCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            Toast.makeText(this, "Video analysis enabled!", Toast.LENGTH_SHORT).show()
            updateVideoCaptureStatus()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Audio permission launcher
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Audio permission granted, now request screen capture
            requestScreenCapture()
        } else {
            Toast.makeText(this, "Audio permission needed for speech-to-text", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMasterToggle()
        setupPermissionRows()
        setupVideoCaptureButton()
        setupHowItWorks()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateOverallStatus()
        updateVideoCaptureStatus()
        populateAppList()
    }

    // ─── Master Toggle ───

    private fun setupMasterToggle() {
        binding.masterToggle.isChecked = PrefsManager.isAgentEnabled(this)
        binding.masterToggle.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setAgentEnabled(this, isChecked)
            updateOverallStatus()
        }
    }

    // ─── Video Capture ───

    private fun setupVideoCaptureButton() {
        binding.videoCaptureButton.setOnClickListener {
            if (MediaCaptureService.instance != null) {
                // Already running — stop it
                val intent = Intent(this, MediaCaptureService::class.java).apply {
                    action = MediaCaptureService.ACTION_STOP
                }
                startService(intent)
                Toast.makeText(this, "Video analysis disabled", Toast.LENGTH_SHORT).show()
                updateVideoCaptureStatus()
            } else {
                // Request audio permission first, then screen capture
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    requestScreenCapture()
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun updateVideoCaptureStatus() {
        val isActive = MediaCaptureService.instance != null
        binding.videoCaptureButton.text = if (isActive) "Disable Video Analysis" else "Enable Video Analysis"
        binding.videoCaptureStatus.text = if (isActive)
            "Active — OCR + speech-to-text enabled"
        else
            "Not active — text-only mode"
        binding.videoCaptureStatus.setTextColor(
            getColor(if (isActive) R.color.status_ok else R.color.text_tertiary)
        )
    }

    // ─── Permission Rows ───

    private fun setupPermissionRows() {
        binding.accessibilityRow.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.overlayRow.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun updatePermissionStatus() {
        val a11yEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)

        // Accessibility row
        binding.accessibilityStatus.text = if (a11yEnabled) "Enabled" else "Tap to enable"
        binding.accessibilityStatus.setTextColor(
            getColor(if (a11yEnabled) R.color.status_ok else R.color.text_secondary)
        )
        binding.accessibilityIcon.background = getDrawable(
            if (a11yEnabled) R.drawable.circle_bg_green else R.drawable.circle_bg_grey
        )
        binding.accessibilityCheck.setImageResource(
            if (a11yEnabled) R.drawable.ic_check_circle else R.drawable.ic_chevron_right
        )
        binding.accessibilityCheck.imageTintList = getColorStateList(
            if (a11yEnabled) R.color.status_ok else R.color.text_tertiary
        )

        // Overlay row
        binding.overlayStatus.text = if (overlayEnabled) "Enabled" else "Tap to enable"
        binding.overlayStatus.setTextColor(
            getColor(if (overlayEnabled) R.color.status_ok else R.color.text_secondary)
        )
        binding.overlayIcon.background = getDrawable(
            if (overlayEnabled) R.drawable.circle_bg_green else R.drawable.circle_bg_grey
        )
        binding.overlayCheck.setImageResource(
            if (overlayEnabled) R.drawable.ic_check_circle else R.drawable.ic_chevron_right
        )
        binding.overlayCheck.imageTintList = getColorStateList(
            if (overlayEnabled) R.color.status_ok else R.color.text_tertiary
        )
    }

    // ─── Overall Status ───

    private fun updateOverallStatus() {
        val a11yEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)
        val agentEnabled = PrefsManager.isAgentEnabled(this)

        val (statusText, chipIconTint) = when {
            !a11yEnabled || !overlayEnabled -> "Setup required" to R.color.status_warning
            !agentEnabled -> "Paused" to R.color.status_idle
            else -> "Active & protecting" to R.color.status_ok
        }

        binding.statusChip.text = statusText
        binding.statusChip.chipIconTint = getColorStateList(chipIconTint)

        binding.toggleSubtitle.text = when {
            !a11yEnabled || !overlayEnabled -> "Complete setup first"
            agentEnabled -> "Monitoring your selected apps"
            else -> "Toggle to enable monitoring"
        }
    }

    // ─── App List ───

    private fun populateAppList() {
        binding.appListContainer.removeAllViews()
        val apps = PrefsManager.getMonitoredApps(this)

        apps.forEach { app ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_app_row, binding.appListContainer, false)

            row.findViewById<TextView>(R.id.appName).text = app.displayName
            row.findViewById<TextView>(R.id.appPackage).text = app.packageName

            // Try to load the actual app icon from the device
            val iconView = row.findViewById<ImageView>(R.id.appIcon)
            try {
                val appIcon = packageManager.getApplicationIcon(app.packageName)
                iconView.setImageDrawable(appIcon)
                iconView.imageTintList = null
                iconView.setPadding(4, 4, 4, 4)
            } catch (_: Exception) {
                // App not installed on this device, keep default icon
            }

            val toggle = row.findViewById<SwitchCompat>(R.id.appToggle)
            toggle.isChecked = app.isEnabled
            toggle.setOnCheckedChangeListener { _, isChecked ->
                PrefsManager.setAppMonitored(this, app.packageName, isChecked)
            }

            binding.appListContainer.addView(row)
        }
    }

    // ─── How It Works ───

    private fun setupHowItWorks() {
        data class Step(val emoji: String, val title: String, val desc: String)

        val steps = listOf(
            Step("📱", "Browse normally", "Open any monitored app and scroll as usual"),
            Step("🔍", "Auto-detect claims", "ReelGuard reads on-screen text and finds factual claims"),
            Step("🌐", "Verify with sources", "Claims are checked against reliable sources in real time"),
            Step("🛡️", "See the verdict", "A floating bubble shows green, yellow, or red — tap for details")
        )

        val container = binding.howItWorksContainer
        container.removeAllViews()

        steps.forEach { step ->
            val view = LayoutInflater.from(this)
                .inflate(R.layout.item_how_step, container, false)

            view.findViewById<TextView>(R.id.stepEmoji).text = step.emoji
            view.findViewById<TextView>(R.id.stepTitle).text = step.title
            view.findViewById<TextView>(R.id.stepDesc).text = step.desc

            container.addView(view)
        }
    }

    // ─── Helpers ───

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.name ==
                    ReelGuardAccessibilityService::class.java.name
        }
    }
}
