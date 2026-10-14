package org.sibaq.screening

import android.Manifest
import android.app.Activity
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
import android.text.Editable
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

    private lateinit var deviceNameInput: EditText
    private lateinit var autoStartCheckBox: CheckBox

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var hubUrlText: TextView
    private lateinit var liveStatsText: TextView
    private lateinit var toggleStreamButton: Button
    private lateinit var copyUrlButton: Button

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

    private var customDeviceName: String = ""
    private var selectedScaleFactor = 0.5f
    private var selectedFps = 30
    private var selectedQuality = 60

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ScreenCaptureService.LocalBinder
            captureService = binder?.getService()
            isServiceBound = true
            captureService?.updateCustomDeviceName(customDeviceName)
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
        loadSettings()
        renderScreen()
        checkNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, ScreenCaptureService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        mainHandler.post(statusRunnable)
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
    }

    private fun saveCustomDeviceName(name: String) {
        customDeviceName = name
        getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_device_name", name)
            .apply()
        captureService?.updateCustomDeviceName(name)
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
            "Real-time ~30 FPS screen sharing & multi-device computer lab management system.",
            14f, false, "#94A3B8"
        ))

        addSpacer(content, 14)

        // Custom Device Naming Card
        val nameCard = createCardLayout()
        nameCard.addView(textView("Device / Student Name", 16f, true, "#F8FAFC"))
        nameCard.addView(textView("Type a name or desk number to identify this device:", 13f, false, "#94A3B8"))

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
        val hubUrl = "http://$ipAddress:8080/hub"

        urlText = textView(adminUrl, 20f, true, "#38BDF8").apply {
            setPadding(0, 8.dp(), 0, 4.dp())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        streamCard.addView(urlText)

        hubUrlText = textView("Central Lab Grid: $hubUrl", 13f, true, "#22C55E").apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        streamCard.addView(hubUrlText)

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
        scaleValText = textView("Resolution Scale: 50% (Recommended for 30 FPS)", 13f, false, "#94A3B8")
        settingsCard.addView(scaleValText)
        scaleSeekBar = SeekBar(this).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress.coerceAtLeast(30)
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
        updateEnrollmentStatus()
    }

    private fun toggleScreenStreaming() {
        val service = captureService
        if (service != null && service.isStreaming) {
            // Stop streaming
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(stopIntent)
            Toast.makeText(this, "Screen stream stopped", Toast.LENGTH_SHORT).show()
            updateStreamUiState()
        } else {
            // Start projection prompt
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val startIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                    putExtra(ScreenCaptureService.EXTRA_CUSTOM_NAME, customDeviceName)
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
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun updateStreamUiState() {
        val service = captureService
        val ipAddress = getLocalIpAddress()
        val url = "http://$ipAddress:8080"
        val hubUrl = "http://$ipAddress:8080/hub"
        urlText.text = url
        hubUrlText.text = "Central Lab Grid: $hubUrl"

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
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
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
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            appScanStatusText.text = "Configure SIBAQ_SUPABASE_URL and SIBAQ_SUPABASE_ANON_KEY in local.properties."
            return
        }

        submitButton.isEnabled = false
        appScanStatusText.text = "Signing in and submitting report..."
        passwordInput.text.clear()

        executor.execute {
            try {
                val token = signIn(email, password)
                submitReport(token, report)
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

    private fun signIn(email: String, password: String): String {
        val response = request(
            path = "/auth/v1/token?grant_type=password",
            method = "POST",
            body = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()
        )
        return JSONObject(response).getString("access_token")
    }

    private fun submitReport(accessToken: String, report: ScanReport) {
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
            path = "/rest/v1/android_device_scan_reports",
            method = "POST",
            body = payload.toString(),
            accessToken = accessToken,
            prefer = "return=minimal"
        )
    }

    private fun request(
        path: String,
        method: String,
        body: String,
        accessToken: String? = null,
        prefer: String? = null
    ): String {
        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val requestUrl = URL("$baseUrl$path")
        val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${accessToken ?: BuildConfig.SUPABASE_ANON_KEY}")
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
            textSize = 14f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedDrawable(hexColor)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(6.dp(), 6.dp(), 6.dp(), 6.dp())
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
