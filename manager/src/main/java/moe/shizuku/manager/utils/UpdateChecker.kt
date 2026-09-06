package moe.shizuku.manager.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shizuku Next auto-update checker
 * Checks GitHub Releases for new versions and downloads/installs APK
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GITHUB_API = "https://api.github.com/repos/yijiacloud/shizuku-next/releases/latest"
    private const val PREF_NAME = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check_time"
    private const val KEY_AUTO_UPDATE = "auto_update_enabled"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())

    fun checkIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_UPDATE, true)) return
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        check(context)
    }

    fun check(context: Context) {
        Thread {
            try {
                Log.i(TAG, "Checking for updates...")
                val url = URL(GITHUB_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "ShizukuNext")
                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG, "GitHub API returned $code")
                    conn.disconnect()
                    return@Thread
                }
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                conn.disconnect()
                val json = JSONObject(sb.toString())
                val tagName = json.optString("tag_name", "")
                val releaseName = json.optString("name", tagName)
                val htmlUrl = json.optString("html_url", "")
                val body = json.optString("body", "")
                val latestVersion = tagName.removePrefix("v").trim()
                val currentVersion = getCurrentVersion(context)
                Log.i(TAG, "Current: $currentVersion, Latest: $latestVersion")
                if (isNewerVersion(latestVersion, currentVersion)) {
                    var apkUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }
                    handler.post {
                        showUpdateDialog(context, latestVersion, releaseName, body, htmlUrl, apkUrl)
                    }
                } else {
                    Log.i(TAG, "Already up to date")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
            }
        }.start()
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0"
        } catch (e: Exception) {
            "0.0"
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun showUpdateDialog(
        context: Context,
        version: String,
        name: String,
        changelog: String,
        htmlUrl: String,
        apkUrl: String?
    ) {
        if (context !is Activity || context.isFinishing) return
        val message = StringBuilder()
        message.append("发现新版本: v" + version + "\n\n")
        if (changelog.isNotBlank()) {
            val cleanLog = changelog
                .replace(Regex("#+\\s*"), "")
                .replace(Regex("\\*\\*"), "")
                .replace(Regex("---"), "")
                .trim()
            if (cleanLog.length > 500) {
                message.append(cleanLog.substring(0, 500))
                message.append("\n...")
            } else {
                message.append(cleanLog)
            }
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("发现新版本 v" + version)
            .setMessage(message.toString())
            .setNegativeButton("稍后再说") { _, _ -> }
        if (apkUrl != null) {
            builder.setPositiveButton("下载并安装") { _, _ ->
                downloadAndInstall(context, apkUrl, version)
            }
        } else if (htmlUrl.isNotBlank()) {
            builder.setPositiveButton("去查看") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl))
                context.startActivity(intent)
            }
        } else {
            builder.setPositiveButton("确定") { _, _ -> }
        }
        builder.show()
    }

    private fun downloadAndInstall(context: Context, apkUrl: String, version: String) {
        Thread {
            try {
                Log.i(TAG, "Downloading APK from " + apkUrl)
                val url = URL(apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "ShizukuNext")
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) {
                    Log.e(TAG, "Download failed: " + conn.responseCode)
                    handler.post {
                        Toast.makeText(context, "下载失败: HTTP " + conn.responseCode, Toast.LENGTH_SHORT).show()
                    }
                    conn.disconnect()
                    return@Thread
                }
                val totalSize = conn.contentLength
                val outputFile = File(context.getExternalFilesDir(null), "shizuku-next-v" + version + ".apk")
                val input = conn.inputStream
                val output = FileOutputStream(outputFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloaded = 0
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalSize > 0 && downloaded % (1024 * 1024) < 8192) {
                        val percent = downloaded * 100 / totalSize
                        Log.i(TAG, "Download progress: " + percent + "%")
                    }
                }
                output.flush()
                output.close()
                input.close()
                conn.disconnect()
                Log.i(TAG, "Download complete: " + outputFile.absolutePath)
                handler.post {
                    Toast.makeText(context, "下载完成，正在安装...", Toast.LENGTH_SHORT).show()
                    try {
                        val intent = Intent(Intent.ACTION_VIEW)
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", outputFile)
                        intent.setDataAndType(uri, "application/vnd.android.package-archive")
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback: try legacy approach
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(Uri.fromFile(outputFile), "application/vnd.android.package-archive")
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        try {
                            context.startActivity(intent)
                        } catch (e2: Exception) {
                            Toast.makeText(context, "安装失败: " + e2.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download/install failed", e)
                handler.post {
                    Toast.makeText(context, "下载失败: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}