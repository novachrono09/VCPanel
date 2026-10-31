package com.squad.tacticalvoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {

    private const val GITHUB_REPO_API = "https://api.github.com/repos/novachrono09/VCPanel/releases/latest"

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val notes: String,
        val apkDownloadUrl: String,
        val apkSize: Long
    )

    sealed class UpdateResult {
        data class Available(val updateInfo: UpdateInfo) : UpdateResult()
        data class UpToDate(val latestVersion: String) : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    fun isVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
        val rParts = remoteVersion.removePrefix("v").removePrefix("V").trim()
            .split(".").map { it.toIntOrNull() ?: 0 }
        val cParts = currentVersion.removePrefix("v").removePrefix("V").trim()
            .split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = if (i < rParts.size) rParts[i] else 0
            val c = if (i < cParts.size) cParts[i] else 0
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_REPO_API)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "VOX-TacticalVoice-App")

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext UpdateResult.Error("HTTP $responseCode from GitHub releases")
            }

            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)

            val tagName = root.optString("tag_name", "")
            val title = root.optString("name", "VOX Update")
            val notes = root.optString("body", "")
            val cleanRemoteVersion = tagName.removePrefix("v").removePrefix("V").trim()

            val assets = root.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isNullOrBlank()) {
                return@withContext UpdateResult.Error("No APK asset found in latest release $tagName")
            }

            val info = UpdateInfo(
                tagName = tagName,
                versionName = cleanRemoteVersion,
                title = title,
                notes = notes,
                apkDownloadUrl = apkUrl,
                apkSize = apkSize
            )

            if (isVersionNewer(cleanRemoteVersion, currentVersion)) {
                UpdateResult.Available(info)
            } else {
                UpdateResult.UpToDate(cleanRemoteVersion)
            }
        } catch (e: Exception) {
            UpdateResult.Error(e.message ?: "Failed to check for updates")
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (progressPercent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        if (!targetDir.exists()) targetDir.mkdirs()
        val apkFile = File(targetDir, "vox_update.apk")
        if (apkFile.exists()) apkFile.delete()

        var currentUrl = downloadUrl
        var connection: HttpURLConnection? = null
        var redirects = 0

        while (redirects < 5) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "VOX-TacticalVoice-App")
            conn.connect()

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == 307 ||
                status == 308
            ) {
                currentUrl = conn.getHeaderField("Location")
                conn.disconnect()
                redirects++
            } else {
                connection = conn
                break
            }
        }

        val conn = connection ?: throw IllegalStateException("Could not establish download connection")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Download HTTP status: ${conn.responseCode}")
        }

        val totalLength = conn.contentLengthLong
        var bytesReadTotal = 0L

        conn.inputStream.use { input: InputStream ->
            FileOutputStream(apkFile).use { output: FileOutputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var lastReportedPercent = -1

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesReadTotal += bytesRead

                    val percent = if (totalLength > 0) {
                        ((bytesReadTotal * 100) / totalLength).toInt().coerceIn(0, 100)
                    } else {
                        -1
                    }

                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        withContext(Dispatchers.Main) {
                            onProgress(percent, bytesReadTotal, totalLength)
                        }
                    }
                }
                output.flush()
            }
        }

        apkFile
    }

    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "Update file missing", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Enable 'Install unknown apps' permission to update VOX", Toast.LENGTH_LONG).show()
                val permissionIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permissionIntent)
                return
            }
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
