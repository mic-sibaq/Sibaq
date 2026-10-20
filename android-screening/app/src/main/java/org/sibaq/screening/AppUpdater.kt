package org.sibaq.screening

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AppUpdater(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface UpdateListener {
        fun onCheckingUpdate(targetUrl: String)
        fun onUpdateAvailable(version: String, releaseNotes: String, downloadUrl: String)
        fun onNoUpdateAvailable()
        fun onDownloadProgress(progressPercent: Int)
        fun onDownloadComplete(apkFile: File)
        fun onError(message: String)
    }

    fun checkForUpdates(serverIp: String, isScreenSharingActive: Boolean, listener: UpdateListener) {
        val targetIp = serverIp.trim().ifBlank { "127.0.0.1" }
        if (targetIp == "127.0.0.1" && !isScreenSharingActive) {
            listener.onError("Please tap 'Start Screen Sharing' or enter your Master Admin PC IP to check for updates.")
            return
        }

        val targetUrl = "http://$targetIp:8080/api/update"
        listener.onCheckingUpdate(targetUrl)

        executor.execute {
            try {
                val url = URL(targetUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }

                if (connection.responseCode in 200..299) {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    val latestVersionCode = json.optInt("versionCode", 1)
                    val version = json.optString("versionName", "1.1")
                    val notes = json.optString("releaseNotes", "New performance and security fixes")
                    var rawDownloadUrl = json.optString("downloadUrl", "/api/download/apk")

                    if (!rawDownloadUrl.startsWith("http://") && !rawDownloadUrl.startsWith("https://")) {
                        rawDownloadUrl = "http://$targetIp:8080${if (rawDownloadUrl.startsWith("/")) "" else "/"}$rawDownloadUrl"
                    }

                    val currentVersionCode = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L)).longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                        }
                    } catch (_: Exception) { 1 }

                    mainHandler.post {
                        if (latestVersionCode > currentVersionCode) {
                            listener.onUpdateAvailable(version, notes, rawDownloadUrl)
                        } else {
                            listener.onNoUpdateAvailable()
                        }
                    }
                } else {
                    mainHandler.post { listener.onError("Update Server returned HTTP ${connection.responseCode}") }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    val msg = if (targetIp == "127.0.0.1") {
                        "Local update server offline. Tap 'Start Screen Sharing' or enter Master Admin IP."
                    } else {
                        "Unreachable Update Server at $targetIp:8080"
                    }
                    listener.onError(msg)
                }
            }
        }
    }

    fun downloadAndInstallApk(downloadUrl: String, listener: UpdateListener) {
        executor.execute {
            try {
                val url = URL(downloadUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                }

                val totalLength = connection.contentLength
                val apkFile = File(context.getExternalFilesDir(null), "update.apk")
                if (apkFile.exists()) apkFile.delete()

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalLength > 0) {
                                val percent = ((downloaded * 100) / totalLength).toInt()
                                mainHandler.post { listener.onDownloadProgress(percent) }
                            }
                        }
                    }
                }

                mainHandler.post {
                    listener.onDownloadComplete(apkFile)
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                mainHandler.post { listener.onError("Download failed: ${e.message ?: "Network error"}") }
            }
        }
    }

    fun installApk(apkFile: File) {
        try {
            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
