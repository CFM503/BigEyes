package com.bigeyes.app.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import com.bigeyes.app.R
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WebViewDownloadHelper {

    private const val TAG = "WebViewDownloadHelper"

    data class ParsedDataUri(
        val mimeType: String,
        val data: ByteArray,
        val suggestedExtension: String
    )

    fun parseDataUri(dataUri: String): ParsedDataUri? {
        if (!dataUri.startsWith("data:", ignoreCase = true)) return null

        try {
            val commaIndex = dataUri.indexOf(',')
            if (commaIndex == -1) return null

            val metadata = dataUri.substring(5, commaIndex) // strip "data:"
            val dataPart = dataUri.substring(commaIndex + 1)

            val isBase64 = metadata.contains(";base64", ignoreCase = true)
            val mimeType = if (metadata.isNotEmpty()) {
                val cleanMeta = if (isBase64) metadata.replace(";base64", "", ignoreCase = true) else metadata
                cleanMeta.split(';').firstOrNull()?.trim()?.ifEmpty { "application/octet-stream" } ?: "application/octet-stream"
            } else {
                "text/plain;charset=US-ASCII"
            }

            val bytes: ByteArray = if (isBase64) {
                decodeBase64(dataPart)
            } else {
                URLDecoder.decode(dataPart, "UTF-8").toByteArray(Charsets.UTF_8)
            }

            val ext = guessExtensionFromMimeType(mimeType)
            return ParsedDataUri(mimeType, bytes, ext)
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Error parsing data URI: ${e.message}", e)
            } catch (_: Throwable) {}
            return null
        }
    }

    private fun decodeBase64(data: String): ByteArray {
        val cleanData = data.trim()
        try {
            return java.util.Base64.getMimeDecoder().decode(cleanData)
        } catch (_: Throwable) {}

        try {
            return java.util.Base64.getDecoder().decode(cleanData)
        } catch (_: Throwable) {}

        try {
            val res = android.util.Base64.decode(cleanData, android.util.Base64.DEFAULT)
            if (res != null) return res
        } catch (_: Throwable) {}

        return ByteArray(0)
    }

    fun guessExtensionFromMimeType(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "application/json", "text/json" -> "json"
            "text/plain" -> "txt"
            "text/html" -> "html"
            "application/javascript", "text/javascript" -> "js"
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            else -> "bin"
        }
    }

    fun sanitizeFilename(filename: String?, defaultExt: String = "json"): String {
        var name = filename?.trim()?.replace("[/\\\\?%*:|\"<>]".toRegex(), "_")
        if (name.isNullOrBlank()) {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            name = "bigeyes_export_${sdf.format(Date())}.$defaultExt"
        }
        if (!name.contains('.')) {
            name = "$name.$defaultExt"
        }
        return name
    }

    fun saveBytesToPublicDownloads(
        context: Context,
        bytes: ByteArray,
        filename: String,
        mimeType: String = "application/octet-stream"
    ): Uri? {
        val cleanFilename = sanitizeFilename(filename, guessExtensionFromMimeType(mimeType))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanFilename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BigEyes")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                        os.flush()
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    Log.i(TAG, "Saved file via MediaStore: $cleanFilename to $uri")
                    uri
                } else {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "BigEyes"
                )
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                val destFile = File(downloadDir, cleanFilename)
                FileOutputStream(destFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
                Log.i(TAG, "Saved file to legacy downloads: ${destFile.absolutePath}")
                Uri.fromFile(destFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bytes to downloads: ${e.message}", e)
            null
        }
    }

    fun downloadHttpUrl(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val cleanFilename = sanitizeFilename(filename)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType ?: "application/octet-stream")
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setDescription("BigEyes 文件下载: $cleanFilename")
                setTitle(cleanFilename)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "BigEyes/$cleanFilename"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setRequiresCharging(false)
                    setRequiresDeviceIdle(false)
                }
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            dm?.enqueue(request)
            Toast.makeText(
                context,
                context.getString(R.string.download_started, cleanFilename),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue DownloadManager request: ${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.download_failed, e.message ?: "下载请求失败"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun injectBlobExtractor(
        webView: WebView,
        blobUrl: String,
        suggestedFilename: String?,
        mimeType: String?
    ) {
        val safeBlobUrl = blobUrl.replace("'", "\\'")
        val safeFilename = (suggestedFilename ?: "vodplus_export.json").replace("'", "\\'")
        val safeMime = (mimeType ?: "application/json").replace("'", "\\'")

        val jsCode = """
            (function() {
                try {
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', '$safeBlobUrl', true);
                    xhr.responseType = 'blob';
                    xhr.onload = function() {
                        if (this.status === 200 || this.status === 0) {
                            var blob = this.response;
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (window.BigEyesBlobBridge && window.BigEyesBlobBridge.onBlobData) {
                                    window.BigEyesBlobBridge.onBlobData(reader.result, '$safeFilename', '$safeMime');
                                }
                            };
                            reader.onerror = function(err) {
                                if (window.BigEyesBlobBridge && window.BigEyesBlobBridge.onBlobError) {
                                    window.BigEyesBlobBridge.onBlobError('Blob read error');
                                }
                            };
                            reader.readAsDataURL(blob);
                        } else {
                            if (window.BigEyesBlobBridge && window.BigEyesBlobBridge.onBlobError) {
                                window.BigEyesBlobBridge.onBlobError('HTTP ' + this.status);
                            }
                        }
                    };
                    xhr.onerror = function() {
                        if (window.BigEyesBlobBridge && window.BigEyesBlobBridge.onBlobError) {
                            window.BigEyesBlobBridge.onBlobError('XHR network error');
                        }
                    };
                    xhr.send();
                } catch(e) {
                    if (window.BigEyesBlobBridge && window.BigEyesBlobBridge.onBlobError) {
                        window.BigEyesBlobBridge.onBlobError('Exception: ' + e.message);
                    }
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }
}
