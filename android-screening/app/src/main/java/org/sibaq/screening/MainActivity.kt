package org.sibaq.screening

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

private data class ScreeningTarget(
    val packageName: String,
    val appName: String
)

private data class ScreeningMatch(
    val packageName: String,
    val appName: String
)

private data class ScanReport(
    val scannedAt: String,
    val matches: List<ScreeningMatch>
)

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appUpdater: AppUpdater

    private lateinit var deviceNameInput: EditText
    private lateinit var masterIpInput: EditText
    private lateinit var supabaseUrlInputEditText: EditText
    private lateinit var supabaseAnonKeyInputEditText: EditText
    private lateinit var autoStartCheckBox: CheckBox

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var hubUrlText: TextView
    private lateinit var liveStatsText: TextView
    private lateinit var cloudStatusText: TextView
    private lateinit var persistentStatusText: TextView
    private lateinit var toggleStreamButton: Button
    private lateinit var copyUrlButton: Button
    private lateinit var updateCheckButton: Button
    private lateinit var scanQrBtn: Button
    private lateinit var accessibilityBtn: Button
    private lateinit var uninstallProtectBtn: Button
    private lateinit var appLockBtn: Button
    private lateinit var batteryOptBtn: Button

    private lateinit var scaleSeekBar: SeekBar
    private lateinit var scaleValText: TextView
    private lateinit var fpsSeekBar: SeekBar
    private lateinit var fpsValText: TextView

    private lateinit var appScanStatusText: TextView
    private lateinit var resultsText: TextView
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var submitButton: Button
    private var latestReport: ScanReport? = null

    private var captureService: ScreenCaptureService? = null
    private var isServiceBound = false
    private var isAppUnlocked = false
    private var isRequestingCapture = false

    private var customDeviceName: String = ""
    private var masterServerIp: String = ""
    private var supabaseUrlSetting: String = ""
    private var supabaseAnonKeySetting: String = ""
    private var adminPin: String = ""
    private var selectedScaleFactor = 0.4f
    private var selectedFps = 30
    private var selectedQuality = 50

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ScreenCaptureService.LocalBinder
            captureService = binder?.getService()
            isServiceBound = true
            captureService?.updateCustomDeviceName(customDeviceName)
            captureService?.updateMasterServerIp(masterServerIp)
            updateStreamUiState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isServiceBound = false
            updateStreamUiState()
        }
    }

    private val statusRunnable = object : Runnable {
        override fun run() {
            updateStreamUiState()
            mainHandler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appUpdater = AppUpdater(this)
        loadSettings()
        renderScreen()
        checkNotificationPermission()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val isPromptStop = intent.action == ScreenCaptureService.ACTION_REQUEST_STOP ||
                intent.getBooleanExtra(ScreenCaptureService.EXTRA_PROMPT_STOP, false) ||
                intent.action == "org.sibaq.screening.action.REQUEST_STOP"

        if (isPromptStop) {
            mainHandler.postDelayed({
                promptStopStreaming()
            }, 300)
            return
        }

        val isAutoResume = intent.getBooleanExtra("auto_resume_stream", false) ||
                intent.getBooleanExtra("auto_started_on_boot", false) ||
                intent.getBooleanExtra(ScreenCaptureService.EXTRA_AUTO_RESUME, false)

        if (isAutoResume) {
            mainHandler.postDelayed({
                checkAndAutoResumeStream()
            }, 500)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, ScreenCaptureService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        mainHandler.post(statusRunnable)
    }

    override fun onResume() {
        super.onResume()
        checkAppLockGate()
        updatePermissionsUiState()
        if (isStreamShouldRun() && (captureService == null || !captureService!!.isStreaming)) {
            mainHandler.postDelayed({
                checkAndAutoResumeStream()
            }, 600)
        }
    }

    override fun onStop() {
        mainHandler.removeCallbacks(statusRunnable)
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
        customDeviceName = prefs.getString("custom_device_name", "") ?: ""
        masterServerIp = prefs.getString("master_server_ip", "") ?: ""
        supabaseUrlSetting = prefs.getString("supabase_url", null) ?: BuildConfig.SUPABASE_URL.ifBlank { LabStreamServer.DEFAULT_SUPABASE_URL }
        supabaseAnonKeySetting = prefs.getString("supabase_anon_key", null) ?: BuildConfig.SUPABASE_ANON_KEY.ifBlank { LabStreamServer.DEFAULT_SUPABASE_ANON_KEY }
        adminPin = prefs.getString("admin_pin", "") ?: ""
    }

    private fun saveCustomDeviceName(name: String) {
        customDeviceName = name
        getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_device_name", name)
            .apply()
        captureService?.updateCustomDeviceName(name)
    }

    private fun saveMasterServerIp(ip: String) {
        masterServerIp = ip
        getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("master_server_ip", ip)
            .apply()
        captureService?.updateMasterServerIp(ip)
    }

    private fun saveSupabaseUrl(url: String) {
        supabaseUrlSetting = url
        getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("supabase_url", url)
            .apply()
    }

    private fun saveSupabaseAnonKey(key: String) {
        supabaseAnonKeySetting = key
        getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("supabase_anon_key", key)
            .apply()
    }

    private fun saveAdminPin(pin: String) {
        adminPin = pin
        getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("admin_pin", pin)
            .apply()
    }

    private fun scanQrCodeSetup() {
        try {
            val intent = Intent("com.google.zxing.client.android.SCAN").apply {
                putExtra("SCAN_MODE", "QR_CODE_MODE")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, REQUEST_CODE_QR_SCAN)
            } else {
                showManualQrInputDialog()
            }
        } catch (_: Exception) {
            showManualQrInputDialog()
        }
    }

    private fun processQrCodePayload(payload: String) {
        try {
            var url = ""
            var key = ""
            var master = ""

            if (payload.trim().startsWith("{")) {
                val json = JSONObject(payload)
                url = json.optString("supabaseUrl", json.optString("url", ""))
                key = json.optString("supabaseAnonKey", json.optString("key", ""))
                master = json.optString("masterIp", json.optString("master", ""))
            } else if (payload.contains("url=") || payload.contains("key=")) {
                val clean = payload.removePrefix("SIBAQ:")
                clean.split("&", ";").forEach { pair ->
                    val parts = pair.split("=")
                    if (parts.size >= 2) {
                        when (parts[0].trim().lowercase()) {
                            "url", "supabaseurl" -> url = parts[1].trim()
                            "key", "anonkey", "supabasekey" -> key = parts[1].trim()
                            "master", "masterip" -> master = parts[1].trim()
                        }
                    }
                }
            } else if (payload.startsWith("http://") || payload.startsWith("https://")) {
                url = payload.trim()
            }

            var updatedCount = 0
            if (url.isNotBlank()) {
                saveSupabaseUrl(url)
                supabaseUrlInputEditText.setText(url)
                updatedCount++
            }
            if (key.isNotBlank()) {
                saveSupabaseAnonKey(key)
                supabaseAnonKeyInputEditText.setText(key)
                updatedCount++
            }
            if (master.isNotBlank()) {
                saveMasterServerIp(master)
                masterIpInput.setText(master)
                updatedCount++
            }

            if (updatedCount > 0) {
                Toast.makeText(this, "✅ Applied Supabase URL & Credentials from QR Code!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "QR Code did not contain recognized Supabase credentials", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Invalid QR Code payload format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManualQrInputDialog() {
        val input = EditText(this).apply {
            hint = "Paste QR Code text, JSON, or URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("Scan / Paste Setup Credentials")
            .setMessage("Paste the Supabase Setup QR Code payload (JSON or url=...&key=...):")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                processQrCodePayload(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAppLockGate() {
        if (adminPin.isNotBlank() && !isAppUnlocked) {
            val input = EditText(this).apply {
                hint = "Enter 4-digit Admin PIN"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this)
                .setTitle("🔒 AppLock Active")
                .setMessage("SIBAQ Lab Screen Monitor is locked. Enter secret Admin PIN to unlock settings:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Unlock") { dialog, _ ->
                    if (input.text.toString().trim() == adminPin) {
                        isAppUnlocked = true
                        Toast.makeText(this, "AppUnlocked successfully!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this, "Incorrect Admin PIN!", Toast.LENGTH_LONG).show()
                        finish() // Exit app if incorrect
                    }
                }
                .setNegativeButton("Exit") { _, _ ->
                    finish()
                }
                .show()
        }
    }

    private fun renderScreen() {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = 20.dp()
            setPadding(p, p, p, p)
        }
        root.addView(content)

        // Title Header
        content.addView(textView("SIBAQ Lab Screen Monitor", 24f, true, "#38BDF8"))
        content.addView(textView(
            "Real-time ~30 FPS screen sharing, cloud auto-sync, AppLock & uninstall protection.",
            14f, false, "#94A3B8"
        ))

        addSpacer(content, 12)

        // PROMINENT IN-APP OTA UPDATER BUTTON (LARGE FULL-WIDTH GREEN CARD)
        updateCheckButton = createButton("🔄 Check for Software Updates", "#10B981") {
            runInAppUpdater()
        }.apply {
            textSize = 15f
            setPadding(0, 14.dp(), 0, 14.dp())
        }
        content.addView(updateCheckButton)

        addSpacer(content, 14)

        // Custom Device Naming & Security Settings Card
        val nameCard = createCardLayout()
        nameCard.addView(textView("Device Name & Security Settings", 16f, true, "#F8FAFC"))
        nameCard.addView(textView("Type a name to identify this device in the lab:", 13f, false, "#94A3B8"))

        deviceNameInput = createEditText("Type your Name or Device Label (e.g. Alex, Lab Desk 05)").apply {
            setText(customDeviceName)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    saveCustomDeviceName(s?.toString()?.trim() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        nameCard.addView(deviceNameInput)

        addSpacer(nameCard, 6)
        nameCard.addView(textView("Central Master Server IP (For Local Network Setup):", 13f, false, "#94A3B8"))

        masterIpInput = createEditText("Master Admin Server IP (e.g. 192.168.1.100)").apply {
            setText(masterServerIp)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    saveMasterServerIp(s?.toString()?.trim() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        nameCard.addView(masterIpInput)

        addSpacer(nameCard, 6)

        val supabaseHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        supabaseHeaderRow.addView(textView("☁️ Supabase Cloud Auto-Sync:", 13f, true, "#38BDF8").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        scanQrBtn = createButton("📷 Scan Setup QR", "#10B981") {
            scanQrCodeSetup()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            textSize = 12f
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
        }
        supabaseHeaderRow.addView(scanQrBtn)
        nameCard.addView(supabaseHeaderRow)

        supabaseUrlInputEditText = createEditText("Supabase URL (e.g. https://xyz.supabase.co)").apply {
            setText(supabaseUrlSetting)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    saveSupabaseUrl(s?.toString()?.trim() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        nameCard.addView(supabaseUrlInputEditText)

        supabaseAnonKeyInputEditText = createEditText("Supabase Anon Key").apply {
            setText(supabaseAnonKeySetting)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    saveSupabaseAnonKey(s?.toString()?.trim() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        nameCard.addView(supabaseAnonKeyInputEditText)

        addSpacer(nameCard, 8)

        val btnRowSettings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        accessibilityBtn = createButton("Remote Control Actions", "#0284C7") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings -> Accessibility -> Enable SIBAQ Remote Control Helper", Toast.LENGTH_LONG).show()
            }
        }

        uninstallProtectBtn = createButton("Enable Uninstall Protection (Device Admin)", "#D97706") {
            toggleUninstallProtection()
        }

        appLockBtn = createButton(if (adminPin.isNotBlank()) "🔒 Change AppLock PIN (Active)" else "🔒 Set Admin AppLock PIN", "#3B82F6") {
            showSetAdminPinDialog()
        }

        btnRowSettings.addView(accessibilityBtn)
        btnRowSettings.addView(uninstallProtectBtn)
        btnRowSettings.addView(appLockBtn)

        batteryOptBtn = createButton("🔋 Disable Battery Restrictions (Run Forever)", "#059669") {
            requestIgnoreBatteryOptimizations()
        }
        btnRowSettings.addView(batteryOptBtn)
        nameCard.addView(btnRowSettings)

        addSpacer(nameCard, 6)

        val restrictedBtn = createButton("Allow Restricted Settings", "#D97706") {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Tap the 3 dots (⋮) in top-right corner -> Tap 'Allow restricted settings'", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
        nameCard.addView(restrictedBtn)

        val prefs = getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
        autoStartCheckBox = CheckBox(this).apply {
            text = "Auto-start screen monitor when device turns on (Boot)"
            setTextColor(Color.parseColor("#CBD5E1"))
            isChecked = prefs.getBoolean("auto_start_on_boot", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("auto_start_on_boot", isChecked).apply()
            }
        }
        nameCard.addView(autoStartCheckBox)

        content.addView(nameCard)

        addSpacer(content, 14)

        // Stream Control Card
        val streamCard = createCardLayout()
        streamCard.addView(textView("Computer Admin Web Link", 18f, true, "#F8FAFC"))
        streamCard.addView(textView("Open this URL on your Admin PC to view this device:", 13f, false, "#94A3B8"))

        val ipAddress = getLocalIpAddress()
        val adminUrl = "http://$ipAddress:8080"
        val hubUrl = "http://$ipAddress:8080"

        urlText = textView(adminUrl, 20f, true, "#38BDF8").apply {
            setPadding(0, 8.dp(), 0, 4.dp())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        streamCard.addView(urlText)

        hubUrlText = textView("Central Lab Hub: $hubUrl", 13f, true, "#22C55E").apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        streamCard.addView(hubUrlText)

        cloudStatusText = textView("☁️ Supabase Cloud Auto-Sync: Active", 12f, true, "#38BDF8").apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        streamCard.addView(cloudStatusText)

        persistentStatusText = textView(
            if (isStreamShouldRun()) "⚡ Persistent Mode ACTIVE: Auto-runs across reboots until stopped"
            else "Continuous Mode: Click Start to keep screening active until explicitly stopped",
            12f, true, "#38BDF8"
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 2.dp(), 0, 4.dp())
        }
        streamCard.addView(persistentStatusText)

        addSpacer(streamCard, 8)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        toggleStreamButton = createButton("Start Screen Sharing", "#0284C7") {
            toggleScreenStreaming()
        }
        copyUrlButton = createButton("Copy Hub Link", "#334155") {
            copyToClipboard(hubUrl)
        }

        btnRow.addView(toggleStreamButton)
        btnRow.addView(copyUrlButton)
        streamCard.addView(btnRow)

        addSpacer(streamCard, 10)

        statusText = textView("Status: Stopped", 14f, true, "#EF4444")
        liveStatsText = textView("Connect your Admin PC browser to view live stream.", 13f, false, "#94A3B8")
        streamCard.addView(statusText)
        streamCard.addView(liveStatsText)

        content.addView(streamCard)

        addSpacer(content, 14)

        // Settings Card
        val settingsCard = createCardLayout()
        settingsCard.addView(textView("Stream Speed & Quality Controls", 16f, true, "#F8FAFC"))

        // Resolution Scale
        scaleValText = textView("Resolution Scale: 40% (Recommended for 30 FPS Fast Performance)", 13f, false, "#94A3B8")
        settingsCard.addView(scaleValText)
        scaleSeekBar = SeekBar(this).apply {
            max = 100
            progress = 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress.coerceAtLeast(25)
                    selectedScaleFactor = p / 100f
                    scaleValText.text = "Resolution Scale: $p%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        settingsCard.addView(scaleSeekBar)

        addSpacer(settingsCard, 8)

        // Frame Rate
        fpsValText = textView("Target Frame Rate: 30 FPS (Ultra Smooth)", 13f, false, "#94A3B8")
        settingsCard.addView(fpsValText)
        fpsSeekBar = SeekBar(this).apply {
            max = 30
            progress = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress.coerceAtLeast(10)
                    selectedFps = p
                    fpsValText.text = "Target Frame Rate: $p FPS"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        settingsCard.addView(fpsSeekBar)

        content.addView(settingsCard)

        addSpacer(content, 18)

        // Legacy App Screening Section
        val scanCard = createCardLayout()
        scanCard.addView(textView("Device Security Scan", 16f, true, "#F8FAFC"))
        scanCard.addView(textView("Check for installed third-party remote desktop software:", 13f, false, "#94A3B8"))

        appScanStatusText = textView("Checking managed status...", 13f, false, "#CBD5E1")
        scanCard.addView(appScanStatusText)

        val scanButton = createButton("Scan Remote Apps", "#334155") {
            scanInstalledApps()
        }
        scanCard.addView(scanButton)

        resultsText = textView("No scan run yet.", 13f, false, "#94A3B8")
        scanCard.addView(resultsText)

        addSpacer(scanCard, 10)
        scanCard.addView(textView("Submit Scan to SIBAQ Supabase", 15f, true, "#F8FAFC"))

        emailInput = createEditText("Supabase Email")
        passwordInput = createEditText("Supabase Password").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        scanCard.addView(emailInput)
        scanCard.addView(passwordInput)

        submitButton = createButton("Sign In & Submit Scan", "#334155") {
            submitLatestReport()
        }.apply {
            isEnabled = false
        }
        scanCard.addView(submitButton)

        content.addView(scanCard)

        setContentView(root)
        updatePermissionsUiState()
        updateEnrollmentStatus()
    }

    private fun updatePermissionsUiState() {
        // Dynamic Accessibility check
        val isAccessEnabled = LabAccessibilityService.isServiceEnabled(this)
        if (isAccessEnabled) {
            accessibilityBtn.text = "✅ Remote Control Actions Active"
            accessibilityBtn.background = createRoundedDrawable("#10B981")
        } else {
            accessibilityBtn.text = "Enable Remote Control Actions"
            accessibilityBtn.background = createRoundedDrawable("#0284C7")
        }

        // Device Admin Uninstall Protection check
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComp = ComponentName(this, SibaqDeviceAdminReceiver::class.java)
        val isAdminActive = dpm.isAdminActive(adminComp)
        if (isAdminActive) {
            uninstallProtectBtn.text = "✅ Uninstall Protection Active"
            uninstallProtectBtn.background = createRoundedDrawable("#10B981")
        } else {
            uninstallProtectBtn.text = "Enable Uninstall Protection (Device Admin)"
            uninstallProtectBtn.background = createRoundedDrawable("#D97706")
        }

        appLockBtn.text = if (adminPin.isNotBlank()) "🔒 Change AppLock PIN (Active)" else "🔒 Set Admin AppLock PIN"

        if (::batteryOptBtn.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                batteryOptBtn.text = "✅ Battery Optimization Disabled (Safe)"
                batteryOptBtn.background = createRoundedDrawable("#10B981")
            } else {
                batteryOptBtn.text = "🔋 Disable Battery Restrictions (Run Forever)"
                batteryOptBtn.background = createRoundedDrawable("#059669")
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Battery optimizations are already disabled.", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        }
    }

    private fun isStreamShouldRun(): Boolean {
        return getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            .getBoolean("stream_should_run", false)
    }

    private fun checkAndAutoResumeStream() {
        val service = captureService
        if (isStreamShouldRun() && (service == null || !service.isStreaming) && !isRequestingCapture) {
            startScreenStreaming()
        }
    }

    private fun startScreenStreaming() {
        if (isRequestingCapture) return
        isRequestingCapture = true
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
        } catch (e: Exception) {
            isRequestingCapture = false
            Toast.makeText(this, "Could not start screen capture: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun promptStopStreaming() {
        val service = captureService
        if (service == null || !service.isStreaming) {
            Toast.makeText(this, "Screen sharing is not currently running.", Toast.LENGTH_SHORT).show()
            return
        }

        if (adminPin.isNotBlank()) {
            val input = EditText(this).apply {
                hint = "Enter 4-digit Admin PIN"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this)
                .setTitle("🔒 Admin PIN Required to Stop")
                .setMessage("Screen monitoring is protected. Enter the secret Admin PIN to stop screen sharing:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Verify & Stop") { dialog, _ ->
                    val enteredPin = input.text.toString().trim()
                    if (enteredPin == adminPin) {
                        dialog.dismiss()
                        executeAuthorizedStop()
                    } else {
                        Toast.makeText(this, "❌ Incorrect Admin PIN! Stream continues running.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel (Keep Running)", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Stop Screen Sharing?")
                .setMessage("Are you sure you want to stop live screening? Computer Lab Admin will lose access to this device's screen.")
                .setCancelable(false)
                .setPositiveButton("Stop Sharing") { dialog, _ ->
                    dialog.dismiss()
                    executeAuthorizedStop()
                }
                .setNegativeButton("Cancel (Keep Running)", null)
                .show()
        }
    }

    private fun executeAuthorizedStop() {
        getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("stream_should_run", false)
            .apply()

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
            putExtra(ScreenCaptureService.EXTRA_FORCE_STOP_AUTHORIZED, true)
        }
        startService(stopIntent)
        Toast.makeText(this, "Screen stream stopped.", Toast.LENGTH_SHORT).show()
        updateStreamUiState()
    }

    private fun toggleUninstallProtection() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComp = ComponentName(this, SibaqDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(adminComp)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Prevents students/users from uninstalling SIBAQ Lab Screen Monitor without Admin Permission."
                )
            }
            startActivity(intent)
        } else {
            requireAdminPinIfSet {
                dpm.removeActiveAdmin(adminComp)
                Toast.makeText(this, "Uninstall protection deactivated", Toast.LENGTH_SHORT).show()
                updatePermissionsUiState()
            }
        }
    }

    private fun showSetAdminPinDialog() {
        requireAdminPinIfSet {
            val input = EditText(this).apply {
                hint = "Enter 4-digit Admin PIN"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this)
                .setTitle("Set AppLock Admin PIN")
                .setMessage("Enter secret Admin PIN to protect settings & stop button:")
                .setView(input)
                .setPositiveButton("Save PIN") { _, _ ->
                    val pin = input.text.toString().trim()
                    if (pin.length >= 4) {
                        saveAdminPin(pin)
                        Toast.makeText(this, "Admin PIN saved! AppLock active.", Toast.LENGTH_SHORT).show()
                        updatePermissionsUiState()
                    } else {
                        Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Remove PIN") { _, _ ->
                    saveAdminPin("")
                    Toast.makeText(this, "AppLock PIN removed", Toast.LENGTH_SHORT).show()
                    updatePermissionsUiState()
                }
                .show()
        }
    }

    private fun requireAdminPinIfSet(action: () -> Unit) {
        if (adminPin.isBlank()) {
            action()
            return
        }
        val input = EditText(this).apply {
            hint = "Admin PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Admin PIN Required")
            .setMessage("Enter secret Admin PIN to perform this action:")
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                if (input.text.toString().trim() == adminPin) {
                    action()
                } else {
                    Toast.makeText(this, "Incorrect Admin PIN!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runInAppUpdater() {
        val targetIp = masterServerIp.trim()
        val isStreaming = captureService?.isStreaming == true
        appUpdater.checkForUpdates(targetIp, isStreaming, object : AppUpdater.UpdateListener {
            override fun onCheckingUpdate(targetUrl: String) {
                Toast.makeText(this@MainActivity, "Checking for updates at $targetUrl...", Toast.LENGTH_SHORT).show()
            }

            override fun onUpdateAvailable(version: String, releaseNotes: String, downloadUrl: String) {
                Toast.makeText(this@MainActivity, "Downloading new version $version...", Toast.LENGTH_LONG).show()
                appUpdater.downloadAndInstallApk(downloadUrl, this)
            }

            override fun onNoUpdateAvailable() {
                Toast.makeText(this@MainActivity, "Your software is up to date!", Toast.LENGTH_SHORT).show()
            }

            override fun onDownloadProgress(progressPercent: Int) {
                if (progressPercent % 20 == 0) {
                    Toast.makeText(this@MainActivity, "Downloading update: $progressPercent%", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDownloadComplete(apkFile: File) {
                Toast.makeText(this@MainActivity, "Download complete! Opening package installer...", Toast.LENGTH_LONG).show()
            }

            override fun onError(message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun toggleScreenStreaming() {
        val service = captureService
        if (service != null && service.isStreaming) {
            promptStopStreaming()
        } else {
            startScreenStreaming()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            isRequestingCapture = false
            if (resultCode == RESULT_OK && data != null) {
                // Persistent mode: screen streaming runs continuously until explicitly stopped!
                getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("stream_should_run", true)
                    .apply()

                val startIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                    putExtra(ScreenCaptureService.EXTRA_CUSTOM_NAME, customDeviceName)
                    putExtra(ScreenCaptureService.EXTRA_MASTER_IP, masterServerIp)
                    putExtra(ScreenCaptureService.EXTRA_SCALE_FACTOR, selectedScaleFactor)
                    putExtra(ScreenCaptureService.EXTRA_FPS, selectedFps)
                    putExtra(ScreenCaptureService.EXTRA_QUALITY, selectedQuality)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
                Toast.makeText(this, "Screen streaming starting...", Toast.LENGTH_SHORT).show()
                mainHandler.postDelayed({ updateStreamUiState() }, 1000)
            } else {
                Toast.makeText(this, "Screen capture permission was not granted", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_CODE_QR_SCAN && resultCode == RESULT_OK && data != null) {
            val scannedText = data.getStringExtra("SCAN_RESULT")
            if (!scannedText.isNullOrBlank()) {
                processQrCodePayload(scannedText)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun updateStreamUiState() {
        val service = captureService
        val ipAddress = getLocalIpAddress()
        val url = "http://$ipAddress:8080"
        urlText.text = url
        hubUrlText.text = "Central Lab Hub: $url"

        val shouldRun = isStreamShouldRun()
        if (::persistentStatusText.isInitialized) {
            if (shouldRun) {
                persistentStatusText.text = "⚡ Persistent Mode ACTIVE: Stream will auto-resume even if device shuts down or reboots."
                persistentStatusText.setTextColor(Color.parseColor("#4ADE80"))
            } else {
                persistentStatusText.text = "Continuous Mode: Click Start to keep screening active until explicitly stopped."
                persistentStatusText.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        if (service != null && service.isStreaming) {
            statusText.text = "Status: LIVE STREAMING (~30 FPS)"
            statusText.setTextColor(Color.parseColor("#22C55E"))
            toggleStreamButton.text = "Stop Screen Sharing"
            toggleStreamButton.background = createRoundedDrawable("#DC2626")

            val clients = service.getConnectedClientCount()
            val fps = service.getCurrentFps()
            val devLabel = service.customDeviceName.ifBlank { "Unassigned Name" }
            liveStatsText.text = "Device Name: $devLabel | Viewers: $clients | Speed: $fps FPS"
        } else {
            statusText.text = "Status: STOPPED"
            statusText.setTextColor(Color.parseColor("#EF4444"))
            toggleStreamButton.text = "Start Screen Sharing"
            toggleStreamButton.background = createRoundedDrawable("#0284C7")
            liveStatsText.text = "Connect your Admin PC browser to view live stream."
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val candidateIps = mutableListOf<Pair<String, String>>()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host != "127.0.0.1" && host != "0.0.0.0") {
                            candidateIps.add(Pair(intf.name.lowercase(), host))
                        }
                    }
                }
            }
            return candidateIps.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("tiwlan") }?.second
                ?: candidateIps.firstOrNull { it.first.startsWith("eth") }?.second
                ?: candidateIps.firstOrNull {
                    it.first.startsWith("rmnet") || it.first.startsWith("ccmni") ||
                    it.first.startsWith("pdp") || it.first.startsWith("clat") ||
                    it.first.startsWith("cellular") || it.first.startsWith("radio")
                }?.second
                ?: candidateIps.firstOrNull()?.second
                ?: "127.0.0.1"
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Lab Hub URL", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied Central Lab Hub Link!", Toast.LENGTH_SHORT).show()
    }

    private fun updateEnrollmentStatus() {
        val manager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isOwner = manager.isDeviceOwnerApp(packageName)
        appScanStatusText.text = if (isOwner) {
            "Managed device verified. Device owner active."
        } else {
            "Not enrolled as device owner (optional for remote app scan)."
        }
    }

    private fun scanInstalledApps() {
        appScanStatusText.text = "Scanning installed package names..."
        submitButton.isEnabled = false
        executor.execute {
            try {
                val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstalledPackages(0)
                }
                val installedNames = installedPackages.map { it.packageName }.toHashSet()

                val matches = SCREENING_TARGETS
                    .filter { it.packageName in installedNames }
                    .map { ScreeningMatch(it.packageName, it.appName) }
                val report = ScanReport(Instant.now().toString(), matches)

                runOnUiThread {
                    latestReport = report
                    appScanStatusText.text = "Scan complete. ${matches.size} match(es) found."
                    resultsText.text = if (matches.isEmpty()) {
                        "No configured remote desktop apps found."
                    } else {
                        matches.joinToString(separator = "\n") { "${it.appName} (${it.packageName})" }
                    }
                    submitButton.isEnabled = true
                }
            } catch (error: Exception) {
                runOnUiThread {
                    appScanStatusText.text = "Scan failed: ${error.message ?: error.javaClass.simpleName}"
                    submitButton.isEnabled = false
                }
            }
        }
    }

    private fun submitLatestReport() {
        val report = latestReport ?: return
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            appScanStatusText.text = "Enter Supabase credentials."
            return
        }
        val targetSupabaseUrl = supabaseUrlSetting.ifBlank { BuildConfig.SUPABASE_URL }
        val targetAnonKey = supabaseAnonKeySetting.ifBlank { BuildConfig.SUPABASE_ANON_KEY }

        if (targetSupabaseUrl.isBlank() || targetAnonKey.isBlank()) {
            appScanStatusText.text = "Configure Supabase URL and Anon Key in app settings or local.properties."
            return
        }

        submitButton.isEnabled = false
        appScanStatusText.text = "Signing in and submitting report..."
        passwordInput.text.clear()

        executor.execute {
            try {
                val token = signIn(email, password, targetSupabaseUrl, targetAnonKey)
                submitReport(token, report, targetSupabaseUrl, targetAnonKey)
                runOnUiThread {
                    appScanStatusText.text = "Report submitted successfully."
                    submitButton.isEnabled = true
                }
            } catch (error: Exception) {
                runOnUiThread {
                    appScanStatusText.text = "Report submission failed: ${error.message ?: error.javaClass.simpleName}"
                    submitButton.isEnabled = true
                }
            }
        }
    }

    private fun signIn(email: String, password: String, baseUrlStr: String, anonKeyStr: String): String {
        val response = request(
            baseUrlStr = baseUrlStr,
            anonKeyStr = anonKeyStr,
            path = "/auth/v1/token?grant_type=password",
            method = "POST",
            body = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()
        )
        return JSONObject(response).getString("access_token")
    }

    private fun submitReport(accessToken: String, report: ScanReport, baseUrlStr: String, anonKeyStr: String) {
        val matches = JSONArray()
        report.matches.forEach { match ->
            matches.put(
                JSONObject()
                    .put("package_name", match.packageName)
                    .put("app_name", match.appName)
            )
        }

        val preferences = getSharedPreferences("screening", MODE_PRIVATE)
        val installationId = preferences.getString("installation_id", null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("installation_id", it).apply()
            }
        val deviceLabel = if (customDeviceName.isNotBlank()) customDeviceName else "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val payload = JSONObject()
            .put("installation_id", installationId)
            .put("device_label", deviceLabel)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("scanned_at", report.scannedAt)
            .put("matches", matches)

        request(
            baseUrlStr = baseUrlStr,
            anonKeyStr = anonKeyStr,
            path = "/rest/v1/android_device_scan_reports",
            method = "POST",
            body = payload.toString(),
            accessToken = accessToken,
            prefer = "return=minimal"
        )
    }

    private fun request(
        baseUrlStr: String,
        anonKeyStr: String,
        path: String,
        method: String,
        body: String,
        accessToken: String? = null,
        prefer: String? = null
    ): String {
        val baseUrl = baseUrlStr.trimEnd('/')
        val requestUrl = URL("$baseUrl$path")
        val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("apikey", anonKeyStr)
            setRequestProperty("Authorization", "Bearer ${accessToken ?: anonKeyStr}")
            setRequestProperty("Content-Type", "application/json")
            if (prefer != null) setRequestProperty("Prefer", prefer)
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val message = try {
                    JSONObject(response).optString("message", response)
                } catch (_: Exception) {
                    response
                }
                throw IllegalStateException("HTTP ${connection.responseCode}: $message")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    // Helper UI builders
    private fun createCardLayout(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = 16.dp()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                setStroke(1.dp(), Color.parseColor("#334155"))
                cornerRadius = 12.dp().toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun createButton(text: String, hexColor: String, onClick: () -> Unit = {}): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedDrawable(hexColor)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(4.dp(), 4.dp(), 4.dp(), 4.dp())
            layoutParams = params
            setOnClickListener { onClick() }
        }

    private fun createEditText(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                setStroke(1.dp(), Color.parseColor("#334155"))
                cornerRadius = 6.dp().toFloat()
            }
            val p = 10.dp()
            setPadding(p, p, p, p)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 6.dp(), 0, 6.dp())
            layoutParams = params
        }

    private fun createRoundedDrawable(hexColor: String): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = 8.dp().toFloat()
        }

    private fun textView(text: String, size: Float, bold: Boolean = false, hexColor: String = "#F8FAFC"): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(hexColor))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            val vp = 4.dp()
            setPadding(0, vp, 0, vp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun addSpacer(parent: LinearLayout, dpHeight: Int) {
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpHeight.dp()
            )
        }
        parent.addView(spacer)
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1001
        private const val REQUEST_CODE_QR_SCAN = 1002

        private val SCREENING_TARGETS = listOf(
            ScreeningTarget("com.anydesk.anydeskandroid", "AnyDesk"),
            ScreeningTarget("com.teamviewer.quicksupport.market", "TeamViewer QuickSupport"),
            ScreeningTarget("com.teamviewer.host.market", "TeamViewer Host"),
            ScreeningTarget("com.teamviewer.teamviewer.market.mobile", "TeamViewer Remote Control"),
            ScreeningTarget("com.carriez.flutter_hbb", "RustDesk"),
            ScreeningTarget("com.sand.airdroid", "AirDroid"),
            ScreeningTarget("com.sand.airmirror", "AirMirror"),
            ScreeningTarget("com.splashtop.sos", "Splashtop SOS"),
            ScreeningTarget("com.koushikdutta.vysor", "Vysor")
        )
    }
}
