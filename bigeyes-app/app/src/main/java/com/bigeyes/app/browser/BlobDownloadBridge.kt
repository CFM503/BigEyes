package com.bigeyes.app.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.bigeyes.app.R

class BlobDownloadBridge(
    private val context: Context,
    private val onDownloadCompleted: ((filename: String, success: Boolean) -> Unit)? = null
) {

    companion object {
        const val JAVASCRIPT_NAME = "BigEyesBlobBridge"
        private const val TAG = "BlobDownloadBridge"
    }

    private val mainHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    @JavascriptInterface
    fun onBlobData(base64DataUrl: String, filename: String, mimeType: String) {
        Log.i(TAG, "Received blob data for: $filename ($mimeType), data length: ${base64DataUrl.length}")

        val parsed = WebViewDownloadHelper.parseDataUri(base64DataUrl)
        if (parsed != null) {
            val finalMime = if (mimeType.isNotBlank()) mimeType else parsed.mimeType
            val cleanName = WebViewDownloadHelper.sanitizeFilename(filename, parsed.suggestedExtension)
            val uri = WebViewDownloadHelper.saveBytesToPublicDownloads(context, parsed.data, cleanName, finalMime)

            val action = Runnable {
                if (uri != null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.download_completed, cleanName),
                        Toast.LENGTH_LONG
                    ).show()
                    onDownloadCompleted?.invoke(cleanName, true)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.download_failed, "保存文件失败"),
                        Toast.LENGTH_LONG
                    ).show()
                    onDownloadCompleted?.invoke(cleanName, false)
                }
            }
            mainHandler?.post(action) ?: action.run()
        } else {
            val action = Runnable {
                Toast.makeText(
                    context,
                    context.getString(R.string.download_failed, "无法解析导出数据"),
                    Toast.LENGTH_LONG
                ).show()
                onDownloadCompleted?.invoke(filename, false)
            }
            mainHandler?.post(action) ?: action.run()
        }
    }

    @JavascriptInterface
    fun onBlobError(error: String) {
        Log.e(TAG, "Blob extraction error from JS: $error")
        val action = Runnable {
            Toast.makeText(
                context,
                context.getString(R.string.download_failed, error),
                Toast.LENGTH_LONG
            ).show()
        }
        mainHandler?.post(action) ?: action.run()
    }
}
