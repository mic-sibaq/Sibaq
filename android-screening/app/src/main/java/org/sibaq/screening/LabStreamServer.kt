package org.sibaq.screening

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredDevice(
    val deviceId: String,
    val ip: String,
    val port: Int,
    val customName: String,
    val model: String,
    val battery: Int,
    val fps: Int,
    val lastSeenMs: Long
)

class LabStreamServer(
    private val context: Context,
    private val port: Int = 8080,
    private val frameProvider: () -> ByteArray?
) {
    @Volatile private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val activeClients = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private val frameLock = Object()

    private val myDeviceId: String by lazy {
        val prefs = context.getSharedPreferences("screening", Context.MODE_PRIVATE)
        prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_id", it).apply()
        }
    }

    @Volatile var masterServerIp: String = ""
    @Volatile var customDeviceName: String = ""
    @Volatile var supabaseUrlInput: String = ""
    @Volatile var supabaseAnonKeyInput: String = ""
    @Volatile var supabaseCloudStatus: String = "Initializing..."
    @Volatile var currentFps: Int = 0
    @Volatile var frameWidth: Int = 1080
    @Volatile var frameHeight: Int = 1920
    private val startTimeMs = System.currentTimeMillis()

    // Registry of other devices discovered on the LAN or registered cross-network
    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()

    init {
        mainHandler.post {
            try {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.US
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        // Start TCP HTTP Server
        executor.execute {
            try {
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                while (isRunning && !serverSocket!!.isClosed) {
                    val clientSocket = serverSocket!!.accept()
                    executor.execute { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (isRunning) e.printStackTrace()
            } finally {
                stop()
            }
        }

        // Start UDP Auto-Discovery Beacon & Listener
        startUdpBeaconAndListener()

        // Start Master Server Cross-Network Heartbeat Client
        startMasterHeartbeatClient()

        // Start Supabase Cloud Auto-Sync
        startSupabaseCloudHeartbeat()

        // Initial Subnet IP Sweep
        scanSubnetIpRange()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        serverSocket = null
        udpSocket = null
        tts = null
    }

    fun getConnectedClientCount(): Int = activeClients.get()

    fun notifyNewFrameAvailable() {
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
    }

    fun getFormattedDeviceLabel(): String {
        val baseModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val shortId = myDeviceId.takeLast(4).uppercase()
        return if (customDeviceName.isNotBlank()) {
            "$customDeviceName ($baseModel #$shortId)"
        } else {
            "$baseModel #$shortId"
        }
    }

    companion object {
        const val DEFAULT_SUPABASE_URL = "https://mtbppoyzcyarsiicisoj.supabase.co"
        const val DEFAULT_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im10YnBwb3l6Y3lhcnNpaWNpc29qIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTE1NDA0NTksImV4cCI6MjEwNzExNjQ1OX0.n7D4ZS2OP4A7QXtg0jtBAE7CT2A7r8lVtgLTxRlxXlM"
        const val UDP_PORT = 8802
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
            // 1. Wi-Fi interface (wlan, tiwlan, p2p)
            val wifi = candidateIps.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("tiwlan") }
            if (wifi != null) return wifi.second

            // 2. Ethernet (eth)
            val eth = candidateIps.firstOrNull { it.first.startsWith("eth") }
            if (eth != null) return eth.second

            // 3. Mobile Cellular (rmnet, ccmni, pdp, clat, usb, radio)
            val mobile = candidateIps.firstOrNull {
                it.first.startsWith("rmnet") || it.first.startsWith("ccmni") ||
                it.first.startsWith("pdp") || it.first.startsWith("clat") ||
                it.first.startsWith("cellular") || it.first.startsWith("radio")
            }
            if (mobile != null) return mobile.second

            // 4. Any other non-loopback IPv4
            if (candidateIps.isNotEmpty()) return candidateIps.first().second
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun startSupabaseCloudHeartbeat() {
        executor.execute {
            while (isRunning) {
                val prefs = context.getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
                val storedUrl = prefs.getString("supabase_url", "") ?: ""
                val storedKey = prefs.getString("supabase_anon_key", "") ?: ""

                val targetUrl = supabaseUrlInput.ifBlank {
                    storedUrl.ifBlank {
                        BuildConfig.SUPABASE_URL.ifBlank { DEFAULT_SUPABASE_URL }
                    }
                }.trim().trimEnd('/')

                val targetKey = supabaseAnonKeyInput.ifBlank {
                    storedKey.ifBlank {
                        BuildConfig.SUPABASE_ANON_KEY.ifBlank { DEFAULT_SUPABASE_ANON_KEY }
                    }
                }.trim()

                if (targetUrl.isNotBlank() && targetKey.isNotBlank() && targetUrl.startsWith("https://")) {
                    try {
                        val currentIp = getLocalIpAddress()
                        val nowIso = Instant.now().toString()

                        val payload = JSONObject().apply {
                            put("device_id", myDeviceId)
                            put("custom_name", customDeviceName)
                            put("device_label", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                            put("ip_address", currentIp)
                            put("port", port)
                            put("battery_level", getBatteryLevel())
                            put("fps", currentFps)
                            put("is_online", true)
                            put("last_seen_at", nowIso)
                        }.toString()

                        val url = URL("$targetUrl/rest/v1/active_devices")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 5000
                            readTimeout = 5000
                            doOutput = true
                            setRequestProperty("apikey", targetKey)
                            setRequestProperty("Authorization", "Bearer $targetKey")
                            setRequestProperty("Content-Type", "application/json")
                            setRequestProperty("Prefer", "resolution=merge-duplicates")
                            setRequestProperty("Connection", "close")
                        }
                        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                        val code = conn.responseCode
                        conn.disconnect()

                        supabaseCloudStatus = when (code) {
                            in 200..299 -> "☁️ Supabase Cloud: Connected & Syncing"
                            401 -> "⚠️ Supabase Cloud: 401 Unauthorized (Check Anon Key)"
                            404 -> "⚠️ Supabase Cloud: 404 Table 'active_devices' not found (Run SQL setup)"
                            else -> "⚠️ Supabase Cloud: HTTP $code"
                        }
                    } catch (e: Exception) {
                        supabaseCloudStatus = "⚠️ Supabase Cloud Error: ${e.message ?: "Connection failed"}"
                    }
                } else {
                    supabaseCloudStatus = "☁️ Supabase Cloud: Enter Supabase URL & Anon Key below"
                }
                try { Thread.sleep(5000) } catch (_: Exception) { break }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 10_000
        activeClients.incrementAndGet()
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            // Read HTTP headers and body content-length if POST
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmptyTrimmed()) break
                if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line!!.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            var postBody = ""
            if (method == "POST" && contentLength > 0) {
                val charBuffer = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = reader.read(charBuffer, readTotal, contentLength - readTotal)
                    if (read <= 0) break
                    readTotal += read
                }
                postBody = String(charBuffer, 0, readTotal)
            }

            val output = BufferedOutputStream(socket.getOutputStream())
            when {
                path == "/stream" || path.startsWith("/stream?") -> {
                    serveMjpegStream(output, socket)
                }
                path == "/single" || path == "/device" -> {
                    serveDashboardHtml(output)
                }
                path == "/api/status" -> {
                    serveJsonStatus(output)
                }
                path == "/api/devices" -> {
                    serveDiscoveredDevicesJson(output)
                }
                path == "/api/scan" -> {
                    executor.execute { scanSubnetIpRange() }
                    sendHttpTextResponse(output, "application/json", "{\"status\":\"Subnet scan started\"}")
                }
                path == "/api/frame" -> {
                    serveSingleFrame(output)
                }
                path == "/api/action" -> {
                    handleRemoteAction(postBody, output)
                }
                path == "/api/register" -> {
                    handleRemoteDeviceRegister(postBody, output)
                }
                path == "/api/update" -> {
                    serveUpdateInfoJson(output)
                }
                path == "/api/download/apk" -> {
                    serveApkDownloadStream(output)
                }
                else -> {
                    // Default home page (GET /) shows All Devices Grid on one single page!
                    serveHubHtml(output)
                }
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            activeClients.decrementAndGet()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun String?.isNullOrEmptyTrimmed(): Boolean {
        return this == null || this.trim().isEmpty()
    }

    private fun scanSubnetIpRange() {
        try {
            val prefixes = HashSet<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostIp = addr.hostAddress ?: continue
                        val lastDotIndex = hostIp.lastIndexOf('.')
                        if (lastDotIndex > 0) {
                            prefixes.add(hostIp.substring(0, lastDotIndex + 1))
                        }
                    }
                }
            }

            val scanExecutor = Executors.newFixedThreadPool(25)
            for (prefix in prefixes) {
                for (i in 1..254) {
                    val targetIp = "$prefix$i"
                    scanExecutor.execute {
                        try {
                            val url = URL("http://$targetIp:8080/api/status")
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 350
                                readTimeout = 350
                            }
                            if (conn.responseCode in 200..299) {
                                val text = conn.inputStream.bufferedReader().use { it.readText() }
                                val json = JSONObject(text)
                                val devId = json.optString("deviceId", targetIp)
                                val devName = json.optString("customName", "")
                                val devModel = json.optString("model", "Android")
                                val devBattery = json.optInt("batteryLevel", 0)
                                val devFps = json.optInt("fps", 0)

                                discoveredDevices[devId] = DiscoveredDevice(
                                    deviceId = devId,
                                    ip = targetIp,
                                    port = 8080,
                                    customName = devName,
                                    model = devModel,
                                    battery = devBattery,
                                    fps = devFps,
                                    lastSeenMs = System.currentTimeMillis()
                                )
                            }
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }
                }
            }
            scanExecutor.shutdown()
        } catch (_: Exception) {}
    }

    private fun serveUpdateInfoJson(output: OutputStream) {
        val currentVersionCode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L)).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        } catch (_: Exception) { 1 }

        val json = JSONObject().apply {
            put("versionCode", currentVersionCode + 1)
            put("versionName", "1.1")
            put("releaseNotes", "In-app mobile OTA auto-updater, multi-network central hub, and out-loud speech alerts")
            put("downloadUrl", "/api/download/apk")
        }
        sendHttpTextResponse(output, "application/json", json.toString())
    }

    private fun serveApkDownloadStream(output: OutputStream) {
        val updateApk = File(context.getExternalFilesDir(null), "update.apk")
        val apkFile = if (updateApk.exists()) {
            updateApk
        } else {
            val sourceDir = context.applicationInfo.sourceDir
            File(sourceDir)
        }

        if (!apkFile.exists()) {
            sendHttpTextResponse(output, "text/plain", "APK file not found on server", 404)
            return
        }

        try {
            val fileLength = apkFile.length()
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.android.package-archive\r\n" +
                    "Content-Length: $fileLength\r\n" +
                    "Content-Disposition: attachment; filename=\"sibaq_update.apk\"\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(headers.toByteArray(Charsets.UTF_8))

            FileInputStream(apkFile).use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
            output.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleRemoteAction(body: String, output: OutputStream) {
        val result = JSONObject()
        try {
            val json = JSONObject(body)
            val action = json.optString("action", "")

            when (action) {
                "exit", "home" -> {
                    var ok = LabAccessibilityService.instance?.exitCurrentAppAndGoHome() ?: false
                    if (!ok) {
                        try {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            }
                            context.startActivity(homeIntent)
                            ok = true
                        } catch (_: Exception) {}
                    }
                    result.put("success", ok)
                    result.put("message", if (ok) "Exited current app and returned to Home" else "Failed to exit app")
                }
                "back" -> {
                    val ok = LabAccessibilityService.instance?.pressBackKey() ?: false
                    result.put("success", ok)
                    result.put("message", if (ok) "Pressed Back" else "Enable Remote Control Actions (Accessibility) on device")
                }
                "click" -> {
                    val x = json.optDouble("x", 0.0).toFloat()
                    val y = json.optDouble("y", 0.0).toFloat()
                    val ok = LabAccessibilityService.instance?.performRemoteClick(x, y) ?: false
                    result.put("success", ok)
                    result.put("message", if (ok) "Clicked ($x, $y)" else "Enable Remote Control Actions (Accessibility) on device")
                }
                "lock" -> {
                    var ok = false
                    // Fallback 1: Try Accessibility GLOBAL_ACTION_LOCK_SCREEN on Android 9+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ok = LabAccessibilityService.instance?.lockScreen() ?: false
                    }
                    // Fallback 2: Try Device Admin lockNow()
                    if (!ok) {
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        if (dpm != null) {
                            try {
                                dpm.lockNow()
                                ok = true
                            } catch (_: Exception) {}
                        }
                    }
                    result.put("success", ok)
                    result.put("message", if (ok) "Screen locked successfully" else "Enable Remote Control Actions or Device Admin on phone for Lock")
                }
                "toast", "speak" -> {
                    val message = json.optString("message", "Attention: Message from Admin")

                    // Play Notification Alert Chime
                    try {
                        val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        val ringtone = RingtoneManager.getRingtone(context.applicationContext, alertUri)
                        ringtone?.play()
                    } catch (_: Exception) {}

                    // Display Toast and Read Alert Message Out-Loud via TextToSpeech Engine
                    mainHandler.post {
                        Toast.makeText(context, "Admin Alert: $message", Toast.LENGTH_LONG).show()
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "admin_alert_${System.currentTimeMillis()}")
                            } else {
                                @Suppress("DEPRECATION")
                                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null)
                            }
                        } catch (_: Exception) {}
                    }
                    result.put("success", true)
                    result.put("message", "Alert chime played and message read out loud on device speaker!")
                }
                else -> {
                    result.put("success", false)
                    result.put("message", "Unknown action: $action")
                }
            }
        } catch (e: Exception) {
            result.put("success", false)
            result.put("error", e.message ?: "Invalid JSON payload")
        }
        sendHttpTextResponse(output, "application/json", result.toString())
    }

    private fun handleRemoteDeviceRegister(body: String, output: OutputStream) {
        val response = JSONObject()
        try {
            val json = JSONObject(body)
            val devId = json.optString("deviceId", UUID.randomUUID().toString())
            val devIp = json.optString("ip", "")
            val devPort = json.optInt("port", 8080)
            val devName = json.optString("customName", "")
            val devModel = json.optString("model", "Android")
            val devBattery = json.optInt("battery", 0)
            val devFps = json.optInt("fps", 0)

            if (devIp.isNotBlank()) {
                discoveredDevices[devId] = DiscoveredDevice(
                    deviceId = devId,
                    ip = devIp,
                    port = devPort,
                    customName = devName,
                    model = devModel,
                    battery = devBattery,
                    fps = devFps,
                    lastSeenMs = System.currentTimeMillis()
                )
                response.put("success", true)
            } else {
                response.put("success", false)
            }
        } catch (_: Exception) {
            response.put("success", false)
        }
        sendHttpTextResponse(output, "application/json", response.toString())
    }

    private fun startMasterHeartbeatClient() {
        executor.execute {
            while (isRunning) {
                val rawTarget = masterServerIp.trim()
                if (rawTarget.isNotBlank() && rawTarget != "127.0.0.1") {
                    try {
                        val payload = JSONObject().apply {
                            put("deviceId", myDeviceId)
                            put("ip", rawTarget)
                            put("port", port)
                            put("customName", customDeviceName)
                            put("model", Build.MODEL)
                            put("battery", getBatteryLevel())
                            put("fps", currentFps)
                        }.toString()

                        val fullUrlStr = when {
                            rawTarget.startsWith("http://") || rawTarget.startsWith("https://") -> "$rawTarget/api/register"
                            rawTarget.contains(".ngrok") || rawTarget.contains(".app") -> "https://$rawTarget/api/register"
                            else -> "http://$rawTarget:8080/api/register"
                        }

                        val url = URL(fullUrlStr)
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 3000
                            readTimeout = 3000
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                        conn.responseCode
                        conn.disconnect()
                    } catch (_: Exception) {}
                }
                try { Thread.sleep(4000) } catch (_: Exception) { break }
            }
        }
    }

    private fun serveDashboardHtml(output: OutputStream) {
        val html = buildDashboardHtml()
        sendHttpTextResponse(output, "text/html; charset=utf-8", html)
    }

    private fun serveHubHtml(output: OutputStream) {
        val html = buildHubHtml()
        sendHttpTextResponse(output, "text/html; charset=utf-8", html)
    }

    private fun serveJsonStatus(output: OutputStream) {
        val status = JSONObject().apply {
            put("deviceId", myDeviceId)
            put("customName", customDeviceName)
            put("formattedLabel", getFormattedDeviceLabel())
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("batteryLevel", getBatteryLevel())
            put("isCharging", isDeviceCharging())
            put("activeClients", activeClients.get())
            put("supabaseCloudStatus", supabaseCloudStatus)
            put("fps", currentFps)
            put("width", frameWidth)
            put("height", frameHeight)
            put("uptimeSeconds", (System.currentTimeMillis() - startTimeMs) / 1000)
        }
        sendHttpTextResponse(output, "application/json", status.toString())
    }

    private fun serveDiscoveredDevicesJson(output: OutputStream) {
        val now = System.currentTimeMillis()
        val list = JSONArray()

        // Include self as first device
        list.put(JSONObject().apply {
            put("deviceId", myDeviceId)
            put("ip", "127.0.0.1")
            put("port", port)
            put("customName", customDeviceName)
            put("label", getFormattedDeviceLabel())
            put("model", Build.MODEL)
            put("battery", getBatteryLevel())
            put("fps", currentFps)
            put("isSelf", true)
        })

        discoveredDevices.values
            .filter { now - it.lastSeenMs < 12_000 && it.deviceId != myDeviceId }
            .forEach { dev ->
                val baseModel = dev.model
                val shortId = dev.deviceId.takeLast(4).uppercase()
                val devLabel = if (dev.customName.isNotBlank()) {
                    "${dev.customName} ($baseModel #$shortId)"
                } else {
                    "$baseModel #$shortId"
                }

                list.put(JSONObject().apply {
                    put("deviceId", dev.deviceId)
                    put("ip", dev.ip)
                    put("port", dev.port)
                    put("customName", dev.customName)
                    put("label", devLabel)
                    put("model", dev.model)
                    put("battery", dev.battery)
                    put("fps", dev.fps)
                    put("isSelf", false)
                })
            }

        sendHttpTextResponse(output, "application/json", list.toString())
    }

    private fun serveSingleFrame(output: OutputStream) {
        val frame = frameProvider()
        if (frame == null) {
            sendHttpTextResponse(output, "text/plain", "No frame available", 503)
            return
        }
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${frame.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(frame)
        output.flush()
    }

    private fun serveMjpegStream(output: OutputStream, socket: Socket) {
        socket.soTimeout = 0
        val boundary = "jpgboundary"
        val initHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(initHeaders.toByteArray(Charsets.UTF_8))
        output.flush()

        var lastFrameData: ByteArray? = null
        while (isRunning && !socket.isClosed) {
            val frame = frameProvider()
            if (frame != null && frame !== lastFrameData) {
                lastFrameData = frame
                val frameHeader = "--$boundary\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n"
                output.write(frameHeader.toByteArray(Charsets.UTF_8))
                output.write(frame)
                output.write("\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
            } else {
                var interrupted = false
                synchronized(frameLock) {
                    try {
                        frameLock.wait(100)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (interrupted) break
            }
        }
    }

    private fun sendHttpTextResponse(output: OutputStream, mimeType: String, content: String, statusCode: Int = 200) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val statusLine = if (statusCode == 200) "HTTP/1.1 200 OK" else "HTTP/1.1 $statusCode Error"
        val headers = "$statusLine\r\n" +
                "Content-Type: $mimeType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun startUdpBeaconAndListener() {
        executor.execute {
            try {
                udpSocket = DatagramSocket(UDP_PORT).apply { broadcast = true }
                val buffer = ByteArray(1024)

                // Sub-thread for sending beacons
                executor.execute {
                    while (isRunning) {
                        try {
                            val beaconJson = JSONObject().apply {
                                put("deviceId", myDeviceId)
                                put("port", port)
                                put("customName", customDeviceName)
                                put("model", Build.MODEL)
                                put("battery", getBatteryLevel())
                                put("fps", currentFps)
                            }.toString()

                            val bytes = beaconJson.toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(
                                bytes, bytes.size,
                                InetAddress.getByName("255.255.255.255"), UDP_PORT
                            )
                            udpSocket?.send(packet)
                        } catch (_: Exception) {}

                        try { Thread.sleep(2000) } catch (_: Exception) { break }
                    }
                }

                // Main UDP listener loop
                while (isRunning && udpSocket != null && !udpSocket!!.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket!!.receive(packet)

                    val senderIp = packet.address.hostAddress ?: continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(text)
                        val devId = json.optString("deviceId", senderIp)
                        val devPort = json.optInt("port", 8080)
                        val devName = json.optString("customName", "")
                        val devModel = json.optString("model", "Android")
                        val devBattery = json.optInt("battery", 0)
                        val devFps = json.optInt("fps", 0)

                        discoveredDevices[devId] = DiscoveredDevice(
                            deviceId = devId,
                            ip = senderIp,
                            port = devPort,
                            customName = devName,
                            model = devModel,
                            battery = devBattery,
                            fps = devFps,
                            lastSeenMs = System.currentTimeMillis()
                        )
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun isDeviceCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.isCharging ?: false
    }

    private fun buildDashboardHtml(): String {
        val label = getFormattedDeviceLabel()
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>SIBAQ Remote Control - $label</title>
                <style>
                    :root { --bg: #0f172a; --card: #1e293b; --accent: #38bdf8; --text: #f8fafc; --subtext: #94a3b8; --border: #334155; --danger: #ef4444; --success: #22c55e; }
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
                    body { background: var(--bg); color: var(--text); display: flex; flex-direction: column; min-height: 100vh; }
                    header { background: var(--card); border-bottom: 1px solid var(--border); padding: 16px 24px; display: flex; justify-content: space-between; align-items: center; }
                    .brand { display: flex; align-items: center; gap: 12px; }
                    .brand h1 { font-size: 1.25rem; font-weight: 700; color: var(--text); }
                    .badge { background: rgba(34, 197, 94, 0.15); color: var(--success); border: 1px solid rgba(34, 197, 94, 0.3); padding: 4px 10px; border-radius: 9999px; font-size: 0.8rem; font-weight: 600; display: flex; align-items: center; gap: 6px; }
                    .dot { width: 8px; height: 8px; background: var(--success); border-radius: 50%; }
                    main { display: grid; grid-template-columns: 1fr 340px; gap: 24px; padding: 24px; flex: 1; max-width: 1600px; margin: 0 auto; width: 100%; }
                    @media (max-width: 1024px) { main { grid-template-columns: 1fr; } }
                    .screen-container { background: #000; border: 1px solid var(--border); border-radius: 12px; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; min-height: 500px; cursor: crosshair; }
                    .screen-container img { max-width: 100%; max-height: 80vh; object-fit: contain; }
                    .controls-overlay { position: absolute; bottom: 16px; left: 50%; transform: translateX(-50%); background: rgba(15, 23, 42, 0.9); backdrop-filter: blur(8px); border: 1px solid var(--border); padding: 8px 16px; border-radius: 9999px; display: flex; gap: 10px; }
                    .btn { background: var(--card); border: 1px solid var(--border); color: var(--text); padding: 8px 14px; border-radius: 6px; cursor: pointer; font-size: 0.85rem; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; }
                    .btn:hover { background: var(--accent); color: #000; font-weight: 600; }
                    .btn-danger { background: rgba(239, 68, 68, 0.2); border-color: var(--danger); color: var(--danger); }
                    .btn-danger:hover { background: var(--danger); color: #fff; }
                    .sidebar { display: flex; flex-direction: column; gap: 20px; }
                    .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 20px; }
                    .card h2 { font-size: 1rem; margin-bottom: 16px; color: var(--accent); font-weight: 600; }
                    .info-grid { display: flex; flex-direction: column; gap: 12px; }
                    .info-row { display: flex; justify-content: space-between; font-size: 0.9rem; }
                    .info-label { color: var(--subtext); }
                    .info-val { font-weight: 600; color: var(--text); }
                    .toast-banner { display:none; position:fixed; bottom:24px; right:24px; z-index:9999; background:rgba(15, 23, 42, 0.95); border:1px solid #22c55e; padding:12px 20px; border-radius:8px; color:#fff; font-size:0.9rem; font-weight:600; box-shadow:0 10px 25px rgba(0,0,0,0.5); }
                </style>
            </head>
            <body>
                <header>
                    <div class="brand">
                        <h1>SIBAQ Computer Lab Remote Actions</h1>
                        <div class="badge"><span class="dot"></span> Live Stream & Remote Control</div>
                    </div>
                    <div>
                        <a href="/" class="btn">Central Lab Hub Grid</a>
                    </div>
                </header>

                <main>
                    <div class="screen-container">
                        <img src="/stream" id="screenView" alt="Live Screen Stream" onclick="sendTouchClick(event)" />
                        <div class="controls-overlay">
                            <button class="btn btn-danger" onclick="sendAction('exit')" title="Exit current application and go to home screen">🏠 Exit App (Home)</button>
                            <button class="btn" onclick="sendAction('back')">⬅️ Back</button>
                            <button class="btn" onclick="sendAction('lock')">🔒 Lock Device</button>
                            <button class="btn" onclick="sendToastPrompt()">🔊 Speak & Play Alert</button>
                            <a href="/" class="btn">All Lab Devices Grid</a>
                        </div>
                    </div>

                    <div class="sidebar">
                        <div class="card">
                            <h2>Remote Control Panel</h2>
                            <p style="font-size:0.85rem; color:var(--subtext); line-height:1.4; margin-bottom:12px;">
                                <strong>Interactive Touch Control:</strong> Click anywhere on the video screen on the left to perform a remote tap/touch gesture on the Android device.
                            </p>
                            <button class="btn btn-danger" onclick="sendAction('exit')" style="width:100%; justify-content:center; padding:12px; font-weight:bold; margin-bottom:8px;">
                                🏠 Exit Current Application Now
                            </button>
                            <button class="btn" onclick="sendToastPrompt()" style="width:100%; justify-content:center; padding:12px; font-weight:bold; background:#10b981; color:#000;">
                                🔊 Speak Alert Out Loud on Device
                            </button>
                        </div>

                        <div class="card">
                            <h2>Device Telemetry</h2>
                            <div class="info-grid">
                                <div class="info-row"><span class="info-label">Device Name</span><span class="info-val" id="valDevice">$label</span></div>
                                <div class="info-row"><span class="info-label">Android Version</span><span class="info-val" id="valAndroid">--</span></div>
                                <div class="info-row"><span class="info-label">Battery Level</span><span class="info-val" id="valBattery">--</span></div>
                                <div class="info-row"><span class="info-label">Stream Speed</span><span class="info-val" id="valFps">--</span></div>
                                <div class="info-row"><span class="info-label">Active Viewers</span><span class="info-val" id="valClients">--</span></div>
                            </div>
                        </div>
                    </div>
                </main>

                <div id="webToast" class="toast-banner"></div>

                <script>
                    function showWebToast(msg, isError) {
                        const toast = document.getElementById('webToast');
                        toast.innerText = msg;
                        toast.style.borderColor = isError ? '#ef4444' : '#22c55e';
                        toast.style.display = 'block';
                        setTimeout(() => { toast.style.display = 'none'; }, 3000);
                    }

                    async function sendAction(actionName, extraParams = {}) {
                        try {
                            const body = Object.assign({ action: actionName }, extraParams);
                            const res = await fetch('/api/action', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(body)
                            });
                            const data = await res.json();
                            showWebToast(data.message || 'Action executed', !data.success);
                        } catch (e) {
                            showWebToast('Action error: ' + e, true);
                        }
                    }

                    function sendToastPrompt() {
                        const msg = prompt('Enter alert text for the phone speaker to READ OUT LOUD:', 'Attention: Please close this app now.');
                        if (msg) {
                            sendAction('speak', { message: msg });
                        }
                    }

                    function sendTouchClick(event) {
                        const img = document.getElementById('screenView');
                        const rect = img.getBoundingClientRect();
                        const clickX = event.clientX - rect.left;
                        const clickY = event.clientY - rect.top;
                        
                        // Calculate percentage coordinates
                        const normX = (clickX / rect.width) * $frameWidth;
                        val normY = (clickY / rect.height) * $frameHeight;
                        
                        fetch('/api/action', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ action: 'click', x: normX, y: normY })
                        });
                    }

                    async function updateTelemetry() {
                        try {
                            const res = await fetch('/api/status');
                            const data = await res.json();
                            document.getElementById('valDevice').innerText = data.formattedLabel || '$label';
                            document.getElementById('valAndroid').innerText = 'Android ' + (data.androidVersion || 'N/A');
                            document.getElementById('valBattery').innerText = (data.batteryLevel >= 0 ? data.batteryLevel + '%' : 'N/A') + (data.isCharging ? ' ⚡' : '');
                            document.getElementById('valFps').innerText = data.fps + ' FPS';
                            document.getElementById('valClients').innerText = data.activeClients + ' viewer(s)';
                        } catch (e) {}
                    }
                    setInterval(updateTelemetry, 1500);
                    updateTelemetry();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildHubHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>SIBAQ Central Lab Hub - All Devices</title>
                <style>
                    :root { --bg: #0f172a; --card: #1e293b; --accent: #38bdf8; --text: #f8fafc; --subtext: #94a3b8; --border: #334155; --danger: #ef4444; --success: #22c55e; }
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
                    body { background: var(--bg); color: var(--text); min-height: 100vh; display: flex; flex-direction: column; }
                    header { background: var(--card); border-bottom: 1px solid var(--border); padding: 16px 24px; display: flex; justify-content: space-between; align-items: center; }
                    header h1 { font-size: 1.25rem; font-weight: 700; color: var(--accent); }
                    main { padding: 24px; flex: 1; max-width: 1800px; margin: 0 auto; width: 100%; }
                    .hub-header { margin-bottom: 24px; display: flex; justify-content: space-between; align-items: center; gap: 12px; }
                    .hub-title { font-size: 1.5rem; font-weight: 700; }
                    
                    .grid-container { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 24px; }
                    .device-card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; overflow: hidden; display: flex; flex-direction: column; transition: transform 0.2s; }
                    .device-card:hover { transform: translateY(-4px); border-color: var(--accent); }
                    
                    .preview-box { background: #000; height: 240px; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; }
                    .preview-box img { max-width: 100%; max-height: 100%; object-fit: contain; }
                    
                    .card-body { padding: 16px; display: flex; flex-direction: column; gap: 8px; }
                    .device-name { font-size: 1.1rem; font-weight: 700; color: var(--text); }
                    .device-meta { font-size: 0.85rem; color: var(--subtext); display: flex; justify-content: space-between; }
                    
                    .btn-row { display: flex; gap: 8px; margin-top: 8px; }
                    .btn { background: var(--accent); color: #000; border: none; padding: 8px 12px; border-radius: 6px; font-weight: 600; cursor: pointer; text-decoration: none; text-align: center; flex: 1; font-size: 0.85rem; }
                    .btn-danger { background: rgba(239, 68, 68, 0.2); border: 1px solid var(--danger); color: var(--danger); }
                    .btn-danger:hover { background: var(--danger); color: #fff; }
                    .btn-scan { background: #10b981; color: #000; padding: 8px 16px; border-radius: 6px; border: none; font-weight: 700; cursor: pointer; }
                    .btn-scan:hover { background: #059669; color: #fff; }
                    .btn:hover { background: #0284c7; color: #fff; }
                    .toast-banner { display:none; position:fixed; bottom:24px; right:24px; z-index:9999; background:rgba(15, 23, 42, 0.95); border:1px solid #22c55e; padding:12px 20px; border-radius:8px; color:#fff; font-size:0.9rem; font-weight:600; box-shadow:0 10px 25px rgba(0,0,0,0.5); }
                </style>
            </head>
            <body>
                <header>
                    <h1>SIBAQ Central Computer Lab Hub</h1>
                    <div><span id="deviceCount" style="color:var(--subtext); font-size:0.9rem;">Discovering lab devices...</span></div>
                </header>

                <main>
                    <div class="hub-header">
                        <div class="hub-title">All Active Devices Across Networks</div>
                        <div style="display:flex; gap:10px;">
                            <button class="btn-scan" onclick="triggerSubnetScan()">🔍 Sweep IP Subnet</button>
                            <input type="text" id="searchBox" placeholder="Filter by Name or IP..." onkeyup="filterGrid()" style="background:var(--card); border:1px solid var(--border); color:#fff; padding:8px 16px; border-radius:6px; width:220px;">
                        </div>
                    </div>

                    <div class="grid-container" id="devicesGrid">
                        <!-- Dynamic Device Cards Rendered Here -->
                    </div>
                </main>

                <div id="webToast" class="toast-banner"></div>

                <script>
                    function showWebToast(msg, isError) {
                        const toast = document.getElementById('webToast');
                        toast.innerText = msg;
                        toast.style.borderColor = isError ? '#ef4444' : '#22c55e';
                        toast.style.display = 'block';
                        setTimeout(() => { toast.style.display = 'none'; }, 3000);
                    }

                    async function triggerSubnetScan() {
                        showWebToast('Scanning local IP range (192.168.x.1 - 254)...', false);
                        try {
                            await fetch('/api/scan');
                            setTimeout(fetchDevices, 1500);
                        } catch (e) {}
                    }

                    async function sendRemoteAction(targetIp, targetPort, actionName) {
                        try {
                            const url = targetIp === '127.0.0.1' ? '/api/action' : 'http://' + targetIp + ':' + targetPort + '/api/action';
                            const res = await fetch(url, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ action: actionName })
                            });
                            const data = await res.json();
                            showWebToast(data.message || 'Action executed', !data.success);
                        } catch (e) {
                            showWebToast('Action error: ' + e, true);
                        }
                    }

                    async function fetchDevices() {
                        try {
                            const res = await fetch('/api/devices');
                            const devices = await res.json();
                            
                            document.getElementById('deviceCount').innerText = devices.length + ' Active Device(s) Located';
                            const grid = document.getElementById('devicesGrid');
                            
                            grid.innerHTML = devices.map(dev => {
                                const streamUrl = dev.isSelf ? '/stream' : 'http://' + dev.ip + ':' + dev.port + '/stream';
                                const pageUrl = dev.isSelf ? '/device' : 'http://' + dev.ip + ':' + dev.port + '/device';
                                return `
                                    <div class="device-card" data-name="${'$'}{dev.label.toLowerCase()} ${'$'}{dev.ip}">
                                        <div class="preview-box">
                                            <img src="${'$'}{streamUrl}" alt="${'$'}{dev.label}" />
                                        </div>
                                        <div class="card-body">
                                            <div class="device-name">${'$'}{dev.label}</div>
                                            <div class="device-meta">
                                                <span>IP: ${'$'}{dev.ip}</span>
                                                <span>Battery: ${'$'}{dev.battery}% 🔋</span>
                                            </div>
                                            <div class="device-meta">
                                                <span>Speed: ${'$'}{dev.fps} FPS</span>
                                                <span style="color:var(--success)">● LIVE</span>
                                            </div>
                                            <div class="btn-row">
                                                <a href="${'$'}{pageUrl}" class="btn">View & Control</a>
                                                <button class="btn btn-danger" onclick="sendRemoteAction('${'$'}{dev.ip}', ${'$'}{dev.port}, 'exit')">🏠 Exit App</button>
                                            </div>
                                        </div>
                                    </div>
                                `;
                            }).join('');
                        } catch (e) {}
                    }

                    function filterGrid() {
                        const query = document.getElementById('searchBox').value.toLowerCase();
                        const cards = document.querySelectorAll('.device-card');
                        cards.forEach(card => {
                            const text = card.getAttribute('data-name') || '';
                            card.style.display = text.includes(query) ? 'flex' : 'none';
                        });
                    }

                    async function initialLoad() {
                        try {
                            fetch('/api/scan'); // Trigger background subnet sweep on page open
                        } catch (e) {}
                        fetchDevices();
                    }

                    setInterval(fetchDevices, 4000);
                    initialLoad();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}

