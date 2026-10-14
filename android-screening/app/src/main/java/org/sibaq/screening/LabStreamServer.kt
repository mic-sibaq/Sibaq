package org.sibaq.screening

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredDevice(
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

    private val frameLock = Object()

    @Volatile var customDeviceName: String = ""
    @Volatile var currentFps: Int = 0
    @Volatile var frameWidth: Int = 1080
    @Volatile var frameHeight: Int = 1920
    private val startTimeMs = System.currentTimeMillis()

    // Registry of other devices discovered on the LAN
    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()

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
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        udpSocket = null
    }

    fun getConnectedClientCount(): Int = activeClients.get()

    fun notifyNewFrameAvailable() {
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
    }

    fun getFormattedDeviceLabel(): String {
        val baseModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return if (customDeviceName.isNotBlank()) {
            "$customDeviceName's Device ($baseModel)"
        } else {
            baseModel
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

            val path = parts[1]

            // Drain HTTP headers
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmptyTrimmed()) break
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
                path == "/api/frame" -> {
                    serveSingleFrame(output)
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
            put("customName", customDeviceName)
            put("formattedLabel", getFormattedDeviceLabel())
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("batteryLevel", getBatteryLevel())
            put("isCharging", isDeviceCharging())
            put("activeClients", activeClients.get())
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
            .filter { now - it.lastSeenMs < 10_000 }
            .forEach { dev ->
                list.put(JSONObject().apply {
                    put("ip", dev.ip)
                    put("port", dev.port)
                    put("customName", dev.customName)
                    put("label", if (dev.customName.isNotBlank()) "${dev.customName}'s Device (${dev.model})" else dev.model)
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
                        val devPort = json.optInt("port", 8080)
                        val devName = json.optString("customName", "")
                        val devModel = json.optString("model", "Android")
                        val devBattery = json.optInt("battery", 0)
                        val devFps = json.optInt("fps", 0)

                        discoveredDevices[senderIp] = DiscoveredDevice(
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
                <title>SIBAQ Admin - $label</title>
                <style>
                    :root { --bg: #0f172a; --card: #1e293b; --accent: #38bdf8; --text: #f8fafc; --subtext: #94a3b8; --border: #334155; --success: #22c55e; }
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
                    body { background: var(--bg); color: var(--text); display: flex; flex-direction: column; min-height: 100vh; }
                    header { background: var(--card); border-bottom: 1px solid var(--border); padding: 16px 24px; display: flex; justify-content: space-between; align-items: center; }
                    .brand { display: flex; align-items: center; gap: 12px; }
                    .brand h1 { font-size: 1.25rem; font-weight: 700; color: var(--text); }
                    .badge { background: rgba(34, 197, 94, 0.15); color: var(--success); border: 1px solid rgba(34, 197, 94, 0.3); padding: 4px 10px; border-radius: 9999px; font-size: 0.8rem; font-weight: 600; display: flex; align-items: center; gap: 6px; }
                    .dot { width: 8px; height: 8px; background: var(--success); border-radius: 50%; }
                    main { display: grid; grid-template-columns: 1fr 340px; gap: 24px; padding: 24px; flex: 1; max-width: 1600px; margin: 0 auto; width: 100%; }
                    @media (max-width: 1024px) { main { grid-template-columns: 1fr; } }
                    .screen-container { background: #000; border: 1px solid var(--border); border-radius: 12px; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; min-height: 500px; }
                    .screen-container img { max-width: 100%; max-height: 80vh; object-fit: contain; }
                    .controls-overlay { position: absolute; bottom: 16px; left: 50%; transform: translateX(-50%); background: rgba(15, 23, 42, 0.85); backdrop-filter: blur(8px); border: 1px solid var(--border); padding: 8px 16px; border-radius: 9999px; display: flex; gap: 12px; }
                    .btn { background: var(--card); border: 1px solid var(--border); color: var(--text); padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 0.85rem; text-decoration: none; display: inline-block; }
                    .btn:hover { background: var(--accent); color: #000; font-weight: 600; }
                    .sidebar { display: flex; flex-direction: column; gap: 20px; }
                    .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 20px; }
                    .card h2 { font-size: 1rem; margin-bottom: 16px; color: var(--accent); font-weight: 600; }
                    .info-grid { display: flex; flex-direction: column; gap: 12px; }
                    .info-row { display: flex; justify-content: space-between; font-size: 0.9rem; }
                    .info-label { color: var(--subtext); }
                    .info-val { font-weight: 600; color: var(--text); }
                </style>
            </head>
            <body>
                <header>
                    <div class="brand">
                        <h1>SIBAQ Computer Lab Admin</h1>
                        <div class="badge"><span class="dot"></span> 30 FPS Stream Active</div>
                    </div>
                    <div>
                        <a href="/" class="btn">Central Lab Hub Grid</a>
                    </div>
                </header>

                <main>
                    <div class="screen-container">
                        <img src="/stream" id="screenView" alt="Live Screen Stream" />
                        <div class="controls-overlay">
                            <button class="btn" onclick="toggleFullscreen()">Fullscreen</button>
                            <a href="/" class="btn">All Lab Devices Grid</a>
                        </div>
                    </div>

                    <div class="sidebar">
                        <div class="card">
                            <h2>Device Telemetry</h2>
                            <div class="info-grid">
                                <div class="info-row"><span class="info-label">Device Name</span><span class="info-val" id="valDevice">$label</span></div>
                                <div class="info-row"><span class="info-label">Android Version</span><span class="info-val" id="valAndroid">--</span></div>
                                <div class="info-row"><span class="info-label">Battery Level</span><span class="info-val" id="valBattery">--</span></div>
                                <div class="info-row"><span class="info-label">Stream Speed</span><span class="info-val" id="valFps">--</span></div>
                                <div class="info-row"><span class="info-label">Active Viewers</span><span class="info-val" id="valClients">--</span></div>
                                <div class="info-row"><span class="info-label">Uptime</span><span class="info-val" id="valUptime">--</span></div>
                            </div>
                        </div>
                    </div>
                </main>

                <script>
                    function toggleFullscreen() {
                        const img = document.getElementById('screenView');
                        if (!document.fullscreenElement) {
                            if (img.requestFullscreen) img.requestFullscreen();
                        } else {
                            if (document.exitFullscreen) document.exitFullscreen();
                        }
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
                            const secs = data.uptimeSeconds || 0;
                            document.getElementById('valUptime').innerText = Math.floor(secs / 60) + 'm ' + (secs % 60) + 's';
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
                    :root { --bg: #0f172a; --card: #1e293b; --accent: #38bdf8; --text: #f8fafc; --subtext: #94a3b8; --border: #334155; --success: #22c55e; }
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
                    body { background: var(--bg); color: var(--text); min-height: 100vh; display: flex; flex-direction: column; }
                    header { background: var(--card); border-bottom: 1px solid var(--border); padding: 16px 24px; display: flex; justify-content: space-between; align-items: center; }
                    header h1 { font-size: 1.25rem; font-weight: 700; color: var(--accent); }
                    main { padding: 24px; flex: 1; max-width: 1800px; margin: 0 auto; width: 100%; }
                    .hub-header { margin-bottom: 24px; display: flex; justify-content: space-between; align-items: center; }
                    .hub-title { font-size: 1.5rem; font-weight: 700; }
                    
                    .grid-container { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 24px; }
                    .device-card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; overflow: hidden; display: flex; flex-direction: column; transition: transform 0.2s; }
                    .device-card:hover { transform: translateY(-4px); border-color: var(--accent); }
                    
                    .preview-box { background: #000; height: 240px; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; }
                    .preview-box img { max-width: 100%; max-height: 100%; object-fit: contain; }
                    
                    .card-body { padding: 16px; display: flex; flex-direction: column; gap: 8px; }
                    .device-name { font-size: 1.1rem; font-weight: 700; color: var(--text); }
                    .device-meta { font-size: 0.85rem; color: var(--subtext); display: flex; justify-content: space-between; }
                    
                    .btn { background: var(--accent); color: #000; border: none; padding: 8px 16px; border-radius: 6px; font-weight: 600; cursor: pointer; text-decoration: none; text-align: center; margin-top: 8px; }
                    .btn:hover { background: #0284c7; color: #fff; }
                </style>
            </head>
            <body>
                <header>
                    <h1>SIBAQ Central Computer Lab Hub</h1>
                    <div><span id="deviceCount" style="color:var(--subtext); font-size:0.9rem;">Discovering lab devices...</span></div>
                </header>

                <main>
                    <div class="hub-header">
                        <div class="hub-title">All Active Devices on One Screen</div>
                        <input type="text" id="searchBox" placeholder="Filter by Name or IP..." onkeyup="filterGrid()" style="background:var(--card); border:1px solid var(--border); color:#fff; padding:8px 16px; border-radius:6px; width:260px;">
                    </div>

                    <div class="grid-container" id="devicesGrid">
                        <!-- Dynamic Device Cards Rendered Here -->
                    </div>
                </main>

                <script>
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
                                            <a href="${'$'}{pageUrl}" class="btn">View Fullscreen Stream</a>
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

                    setInterval(fetchDevices, 4000);
                    fetchDevices();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        const val UDP_PORT = 8802
    }
}
