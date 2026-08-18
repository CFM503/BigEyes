package com.bigeyes.app.updater

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bigeyes.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/CFM503/BigEyes/releases/latest"
    private const val GITHUB_REPO_RELEASES_URL = "https://github.com/CFM503/BigEyes/releases"

    data class UpdateInfo(
        val versionName: String,
        val title: String,
        val changelog: String,
        val downloadUrl: String?,
        val releaseHtmlUrl: String,
        val publishedAt: String
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "2.0.4"
        } catch (_: Exception) {
            "2.0.4"
        }
    }

    suspend fun fetchLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "BigEyes-App")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to query GitHub release: HTTP ${response.code}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)

                val tagName = json.optString("tag_name", "").trim()
                val versionName = tagName.removePrefix("v").trim()
                val title = json.optString("name", "BigEyes $tagName")
                val body = json.optString("body", "暂无更新日志")
                val htmlUrl = json.optString("html_url", GITHUB_REPO_RELEASES_URL)
                val publishedAtRaw = json.optString("published_at", "")

                var downloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                // If no APK asset found in release, fallback to fast tag artifact
                if (downloadUrl.isNullOrBlank() && tagName.isNotEmpty()) {
                    downloadUrl = "https://github.com/CFM503/BigEyes/releases/download/$tagName/BigEyes-$tagName.apk"
                }

                val formattedDate = try {
                    if (publishedAtRaw.isNotEmpty()) {
                        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        val date = inputFormat.parse(publishedAtRaw)
                        val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        date?.let { outputFormat.format(it) } ?: publishedAtRaw
                    } else ""
                } catch (_: Exception) {
                    publishedAtRaw
                }

                UpdateInfo(
                    versionName = versionName,
                    title = title,
                    changelog = body,
                    downloadUrl = downloadUrl,
                    releaseHtmlUrl = htmlUrl,
                    publishedAt = formattedDate
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking update: ${e.message}")
            null
        }
    }

    /**
     * Compare semantic versions, e.g. "2.0.2" vs "2.0.1"
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        try {
            val remoteParts = remoteVersion.split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (_: Exception) {
            return remoteVersion != currentVersion
        }
    }

    fun checkUpdate(activity: AppCompatActivity, silent: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        val currentVersion = getCurrentVersionName(activity)
        if (!silent) {
            Toast.makeText(activity, "正在检查 GitHub 最新版本...", Toast.LENGTH_SHORT).show()
        }

        activity.lifecycleScope.launch {
            val release = fetchLatestRelease()
            if (release == null) {
                if (!silent) {
                    Toast.makeText(activity, "检查更新失败，请检查网络连接或稍后再试", Toast.LENGTH_SHORT).show()
                }
                onComplete?.invoke(false)
                return@launch
            }

            val hasNewer = isNewerVersion(release.versionName, currentVersion)
            if (hasNewer) {
                showUpdateDialog(activity, release)
                onComplete?.invoke(true)
            } else {
                if (!silent) {
                    Toast.makeText(activity, "当前已是最新版本 (v$currentVersion)", Toast.LENGTH_LONG).show()
                }
                onComplete?.invoke(false)
            }
        }
    }

    fun showUpdateDialog(activity: AppCompatActivity, info: UpdateInfo) {
        val currentVersion = getCurrentVersionName(activity)
        val message = "当前版本: v$currentVersion\n" +
                "最新版本: v${info.versionName} (${info.publishedAt})\n\n" +
                "【更新日志】:\n${info.changelog.trim()}"

        MaterialAlertDialogBuilder(activity)
            .setTitle("🎉 发现新版本: v${info.versionName}")
            .setMessage(message)
            .setPositiveButton("立即下载更新") { _, _ ->
                if (!info.downloadUrl.isNullOrBlank()) {
                    downloadAndInstall(activity, info)
                } else {
                    openBrowser(activity, info.releaseHtmlUrl)
                }
            }
            .setNegativeButton("稍后再说", null)
            .setNeutralButton("网页查看") { _, _ ->
                openBrowser(activity, info.releaseHtmlUrl)
            }
            .show()
    }

    @SuppressLint("SetTextI18n")
    fun downloadAndInstall(activity: AppCompatActivity, info: UpdateInfo) {
        val downloadUrl = info.downloadUrl ?: return openBrowser(activity, info.releaseHtmlUrl)

        // Inflate progress dialog view
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.pb_download)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tv_download_progress)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_download_status)

        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("正在下载 BigEyes v${info.versionName}")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消下载", null)
            .create()

        var isCancelled = false
        progressDialog.setOnShowListener {
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                isCancelled = true
                progressDialog.dismiss()
                Toast.makeText(activity, "下载已取消", Toast.LENGTH_SHORT).show()
            }
        }
        progressDialog.show()

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val destFile = File(
                activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir,
                "BigEyes_v${info.versionName}.apk"
            )

            try {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "BigEyes-App")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Empty body")
                    val totalBytes = body.contentLength()
                    var bytesDownloaded = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            var lastUpdatePercent = -1

                            while (input.read(buffer).also { read = it } != -1) {
                                if (isCancelled) {
                                    destFile.delete()
                                    return@launch
                                }
                                output.write(buffer, 0, read)
                                bytesDownloaded += read

                                if (totalBytes > 0) {
                                    val percent = ((bytesDownloaded * 100) / totalBytes).toInt()
                                    if (percent != lastUpdatePercent) {
                                        lastUpdatePercent = percent
                                        val downloadedMb = bytesDownloaded / (1024 * 1024.0)
                                        val totalMb = totalBytes / (1024 * 1024.0)

                                        withContext(Dispatchers.Main) {
                                            progressBar.isIndeterminate = false
                                            progressBar.progress = percent
                                            tvProgress.text = "$percent%"
                                            tvStatus.text = String.format(Locale.getDefault(), "%.1f MB / %.1f MB", downloadedMb, totalMb)
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        progressBar.isIndeterminate = true
                                        val downloadedMb = bytesDownloaded / (1024 * 1024.0)
                                        tvStatus.text = String.format(Locale.getDefault(), "已下载 %.1f MB", downloadedMb)
                                    }
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    installApk(activity, destFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("下载失败")
                        .setMessage("下载更新包失败 (${e.message})，是否前往 GitHub Release 网页手动下载？")
                        .setPositiveButton("前往网页") { _, _ ->
                            openBrowser(activity, info.releaseHtmlUrl)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(activity, "安装包不存在", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Android 8.0+ unknown sources permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(activity, "请允许 BigEyes 安装应用权限以完成更新", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}", e)
            Toast.makeText(activity, "无法调起系统安装器: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}
