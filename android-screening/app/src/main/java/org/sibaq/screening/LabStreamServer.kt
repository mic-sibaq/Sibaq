package org.sibaq.screening

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LabStreamServer(
    private val context: Context,
    private val port: Int = 8080,
    private val frameProvider: () -> ByteArray?
) {
    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val activeClients = AtomicInteger(0)
    
    // Telemetry providers
    @Volatile var currentFps: Int = 0
    @Volatile var frameWidth: Int = 1080
    @Volatile var frameHeight: Int = 1920
    private val startTimeMs = System.currentTimeMillis()

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                while (isRunning && !serverSocket!!.isClosed) {
                    val clientSocket = serverSocket!!.accept()
                    executor.execute { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    e.printStackTrace()
                }
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    fun getConnectedClientCount(): Int = activeClients.get()

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

            // Read remaining HTTP headers
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNull_or_empty_trimmed()) break
            }

            val output = BufferedOutputStream(socket.getOutputStream())
            when {
                path == "/stream" || path.startsWith("/stream?") -> {
                    serveMjpegStream(output, socket)
                }
                path == "/api/status" -> {
                    serveJsonStatus(output)
                }
                path == "/api/frame" -> {
                    serveSingleFrame(output)
                }
                else -> {
                    serveDashboardHtml(output)
                }
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            activeClients.decrementAndGet()
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun String?.isNull_or_empty_trimmed(): Boolean {
        return this == null || this.trim().isEmpty()
    }

    private fun serveDashboardHtml(output: OutputStream) {
        val html = buildDashboardHtml()
        val bytes = html.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun serveJsonStatus(output: OutputStream) {
        val status = JSONObject().apply {
            put("deviceLabel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
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
        val bytes = status.toString().toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun serveSingleFrame(output: OutputStream) {
        val frame = frameProvider()
        if (frame == null) {
            val headers = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.flush()
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
        socket.soTimeout = 0 // Keep connection alive for stream
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
            }
            try {
                Thread.sleep(30) // ~33 FPS max
            } catch (_: InterruptedException) {
                break
            }
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
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>SIBAQ Lab Admin - $deviceLabel</title>
                <style>
                    :root {
                        --bg: #0f172a;
                        --card: #1e293b;
                        --accent: #38bdf8;
                        --accent-hover: #0284c7;
                        --text: #f8fafc;
                        --subtext: #94a3b8;
                        --border: #334155;
                        --success: #22c55e;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
                    body { background-color: var(--bg); color: var(--text); display: flex; flex-direction: column; min-height: 100vh; }
                    
                    header {
                        background: var(--card);
                        border-bottom: 1px solid var(--border);
                        padding: 16px 24px;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    .brand { display: flex; align-items: center; gap: 12px; }
                    .brand h1 { font-size: 1.25rem; font-weight: 700; color: var(--text); }
                    .badge {
                        background: rgba(34, 197, 94, 0.15);
                        color: var(--success);
                        border: 1px solid rgba(34, 197, 94, 0.3);
                        padding: 4px 10px;
                        border-radius: 9999px;
                        font-size: 0.8rem;
                        font-weight: 600;
                        display: flex;
                        align-items: center;
                        gap: 6px;
                    }
                    .dot { width: 8px; height: 8px; background: var(--success); border-radius: 50%; animation: pulse 2s infinite; }
                    @keyframes pulse { 0% { opacity: 1; } 50% { opacity: 0.4; } 100% { opacity: 1; } }

                    main {
                        display: grid;
                        grid-template-columns: 1fr 340px;
                        gap: 24px;
                        padding: 24px;
                        flex: 1;
                        max-width: 1600px;
                        margin: 0 auto;
                        width: 100%;
                    }
                    @media (max-width: 1024px) { main { grid-template-columns: 1fr; } }

                    .screen-container {
                        background: #000;
                        border: 1px solid var(--border);
                        border-radius: 12px;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        position: relative;
                        overflow: hidden;
                        min-height: 500px;
                    }
                    .screen-container img {
                        max-width: 100%;
                        max-height: 80vh;
                        object-fit: contain;
                    }

                    .controls-overlay {
                        position: absolute;
                        bottom: 16px;
                        left: 50%;
                        transform: translateX(-50%);
                        background: rgba(15, 23, 42, 0.85);
                        backdrop-filter: blur(8px);
                        border: 1px solid var(--border);
                        padding: 8px 16px;
                        border-radius: 9999px;
                        display: flex;
                        gap: 12px;
                    }
                    .btn {
                        background: var(--card);
                        border: 1px solid var(--border);
                        color: var(--text);
                        padding: 6px 14px;
                        border-radius: 6px;
                        cursor: pointer;
                        font-size: 0.85rem;
                        transition: all 0.2s;
                    }
                    .btn:hover { background: var(--accent); color: #000; font-weight: 600; }

                    .sidebar { display: flex; flex-direction: column; gap: 20px; }
                    .card {
                        background: var(--card);
                        border: 1px solid var(--border);
                        border-radius: 12px;
                        padding: 20px;
                    }
                    .card h2 { font-size: 1rem; margin-bottom: 16px; color: var(--accent); font-weight: 600; }

                    .info-grid { display: flex; flex-direction: column; gap: 12px; }
                    .info-row { display: flex; justify-content: space-between; font-size: 0.9rem; }
                    .info-label { color: var(--subtext); }
                    .info-val { font-weight: 600; color: var(--text); }

                    .lab-grid-tip {
                        background: rgba(56, 189, 248, 0.1);
                        border: 1px dashed var(--accent);
                        padding: 16px;
                        border-radius: 8px;
                        font-size: 0.85rem;
                        line-height: 1.4;
                        color: var(--subtext);
                    }
                    .lab-grid-tip strong { color: var(--accent); }
                </style>
            </head>
            <body>
                <header>
                    <div class="brand">
                        <h1>SIBAQ Computer Lab Admin</h1>
                        <div class="badge"><span class="dot"></span> Live Stream Active</div>
                    </div>
                    <div>
                        <span style="font-size: 0.85rem; color: var(--subtext);">Target Device: <strong>$deviceLabel</strong></span>
                    </div>
                </header>

                <main>
                    <div class="screen-container">
                        <img src="/stream" id="screenView" alt="Live Screen Stream" />
                        <div class="controls-overlay">
                            <button class="btn" onclick="toggleFullscreen()">Fullscreen</button>
                            <button class="btn" onclick="reloadStream()">Refresh Stream</button>
                        </div>
                    </div>

                    <div class="sidebar">
                        <div class="card">
                            <h2>Device Telemetry</h2>
                            <div class="info-grid">
                                <div class="info-row"><span class="info-label">Device Name</span><span class="info-val" id="valDevice">$deviceLabel</span></div>
                                <div class="info-row"><span class="info-label">Android Version</span><span class="info-val" id="valAndroid">--</span></div>
                                <div class="info-row"><span class="info-label">Battery Level</span><span class="info-val" id="valBattery">--</span></div>
                                <div class="info-row"><span class="info-label">Stream Resolution</span><span class="info-val" id="valResolution">--</span></div>
                                <div class="info-row"><span class="info-label">Stream Rate (FPS)</span><span class="info-val" id="valFps">--</span></div>
                                <div class="info-row"><span class="info-label">Active Clients</span><span class="info-val" id="valClients">--</span></div>
                                <div class="info-row"><span class="info-label">Session Uptime</span><span class="info-val" id="valUptime">--</span></div>
                            </div>
                        </div>

                        <div class="card">
                            <h2>Computer Lab Admin Hub</h2>
                            <div class="lab-grid-tip">
                                <strong>Multi-Device Lab Monitoring:</strong><br>
                                To monitor multiple Android devices simultaneously on your admin PC, open multiple browser tabs or embed this device URL in your Lab Management grid console:<br>
                                <code style="display:block; margin-top:8px; background:#000; padding:6px; border-radius:4px; color:#38bdf8;">http://[DEVICE_IP]:8080/stream</code>
                            </div>
                        </div>
                    </div>
                </main>

                <script>
                    function reloadStream() {
                        const img = document.getElementById('screenView');
                        img.src = '/stream?t=' + new Date().getTime();
                    }

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
                            document.getElementById('valDevice').innerText = data.deviceLabel || '$deviceLabel';
                            document.getElementById('valAndroid').innerText = 'Android ' + (data.androidVersion || 'N/A');
                            document.getElementById('valBattery').innerText = (data.batteryLevel >= 0 ? data.batteryLevel + '%' : 'N/A') + (data.isCharging ? ' ⚡ (Charging)' : '');
                            document.getElementById('valResolution').innerText = data.width + ' x ' + data.height;
                            document.getElementById('valFps').innerText = data.fps + ' FPS';
                            document.getElementById('valClients').innerText = data.activeClients + ' viewer(s)';
                            
                            const secs = data.uptimeSeconds || 0;
                            const m = Math.floor(secs / 60);
                            const s = secs % 60;
                            document.getElementById('valUptime').innerText = m + 'm ' + s + 's';
                        } catch (e) {
                            console.error('Failed fetching status', e);
                        }
                    }

                    setInterval(updateTelemetry, 2000);
                    updateTelemetry();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
