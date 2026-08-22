package com.bigeyes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.bigeyes.app.browser.BlobDownloadBridge
import com.bigeyes.app.browser.BookmarkManager
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.browser.SnifferBridge
import com.bigeyes.app.browser.SnifferWebViewClient
import com.bigeyes.app.browser.VideoSnifferHelper
import com.bigeyes.app.browser.WebViewDownloadHelper
import com.bigeyes.app.dlna.DlnaDeviceManager
import com.bigeyes.app.model.VideoCandidate
import com.bigeyes.app.service.CastingForegroundService
import com.bigeyes.app.ui.BookmarkDialog
import com.bigeyes.app.ui.CandidateDialog
import com.bigeyes.app.ui.DeviceSelectDialog
import com.bigeyes.app.ui.SettingsActivity
import com.bigeyes.app.updater.UpdateManager
import com.bigeyes.app.utils.AppPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        init {
            try {
                AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
            } catch (_: Throwable) {}
        }
    }

    private lateinit var webViewContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnHome: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnCast: Button
    private lateinit var tvBadgeCount: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var containerControl: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var playbackControlBar: PlaybackControlBar

    private var isInlineVideoPlaying: Boolean = false

    @Volatile
    private var isRecreatingWebView: Boolean = false
    private var lastValidUrl: String = ""

    private var fallbackDlnaManager: DlnaDeviceManager? = null
    private var currentCandidates: List<VideoCandidate> = emptyList()

    // Fullscreen handling
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var insetsController: WindowInsetsControllerCompat? = null

    // File Chooser handling (<input type="file"> / 导入)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logMemoryUsage("onCreate_start")
        setContentView(R.layout.activity_main)

        insetsController = WindowCompat.getInsetsController(window, window.decorView)

        initViews()
        setupWindowInsets()
        setupWebViewInstance(webView)
        setupListeners()
        setupBackNavigation()
        requestNotificationPermission()

        // Asynchronous silent check for app updates
        checkForUpdatesSilently()

        // Default landing page: load user-defined or factory homepage (Tencent Video)
        val defaultUrl = AppPreferences.getHomepageUrl(this)
        lastValidUrl = defaultUrl
        etUrl.setText(defaultUrl)
        webView.loadUrl(defaultUrl)
        logMemoryUsage("onCreate_done")
    }

    private fun logMemoryUsage(stage: String) {
        try {
            val runtime = Runtime.getRuntime()
            val total = runtime.totalMemory() / (1024 * 1024)
            val free = runtime.freeMemory() / (1024 * 1024)
            val max = runtime.maxMemory() / (1024 * 1024)
            val used = total - free
            Log.i(TAG, "[MemoryDiagnostic] stage=$stage, used=${used}MB, total=${total}MB, max=${max}MB, free=${free}MB")
        } catch (_: Throwable) {}
    }

    private fun checkForUpdatesSilently() {
        lifecycleScope.launch {
            delay(2000L) // Wait 2s for UI initialization
            UpdateManager.checkUpdate(this@MainActivity, silent = true)
        }
    }

    private fun initViews() {
        webViewContainer = findViewById(R.id.web_view_container)
        webView = findViewById(R.id.web_view)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        etUrl = findViewById(R.id.et_url)
        btnHome = findViewById(R.id.btn_home)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_nav_forward)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnBookmark = findViewById(R.id.btn_bookmark)
        btnCast = findViewById(R.id.btn_cast)
        tvBadgeCount = findViewById(R.id.tv_badge_count)
        btnSettings = findViewById(R.id.btn_settings)
        progressBar = findViewById(R.id.progress_bar)
        containerControl = findViewById(R.id.container_playback_control)
        fullscreenContainer = findViewById(R.id.fullscreen_custom_content)

        playbackControlBar = PlaybackControlBar(containerControl, lifecycleScope)
    }

    private fun updateKeepScreenOn() {
        runOnUiThread {
            try {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val shouldKeepOn = (customView != null) || isInlineVideoPlaying
                if (shouldKeepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed updating keep screen on flag: ${e.message}")
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            if (customView == null) {
                val statusInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
                val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

                topBar.setPadding(
                    topBar.paddingLeft,
                    statusInsets.top,
                    topBar.paddingRight,
                    topBar.paddingBottom
                )
                bottomBar.setPadding(
                    bottomBar.paddingLeft,
                    bottomBar.paddingTop,
                    bottomBar.paddingRight,
                    navInsets.bottom
                )
            } else {
                topBar.setPadding(topBar.paddingLeft, 0, topBar.paddingRight, topBar.paddingBottom)
                bottomBar.setPadding(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, 0)
            }
            insets
        }
    }

    private fun startCastingService() {
        try {
            val intent = Intent(this, CastingForegroundService::class.java).apply {
                action = CastingForegroundService.ACTION_START_CAST
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed starting casting service: ${e.message}")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebViewInstance(targetWebView: WebView) {
        logMemoryUsage("setupWebViewInstance")
        // Enable persistent cookies and third-party authentication cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(targetWebView, true)

        val settings = targetWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false) // Route popups cleanly

        // Prevent unwanted whole-page pinch-zoom from breaking SPA layout
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Ensure modern Mobile Chrome User-Agent
        val defaultUa = settings.userAgentString
        if (defaultUa.isNullOrBlank() || !defaultUa.contains("Chrome/")) {
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }

        // Register Blob Download JavascriptInterface bridge
        val blobBridge = BlobDownloadBridge(this)
        targetWebView.addJavascriptInterface(blobBridge, BlobDownloadBridge.JAVASCRIPT_NAME)

        // Register Real-time Video Sniffer JavascriptInterface bridge
        val snifferBridge = SnifferBridge(
            getCurrentUserAgent = { targetWebView.settings.userAgentString },
            getCurrentCookie = { url -> CookieManager.getInstance().getCookie(url) },
            onPlaybackStateListener = { isPlaying ->
                isInlineVideoPlaying = isPlaying
                updateKeepScreenOn()
            }
        )
        targetWebView.addJavascriptInterface(snifferBridge, SnifferBridge.JAVASCRIPT_NAME)

        // Download Listener for Blob, Data URI, and HTTP/HTTPS files
        targetWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            Log.d(TAG, "Download requested: $url, mime: $mimetype")
            when {
                url.startsWith("blob:", ignoreCase = true) -> {
                    WebViewDownloadHelper.injectBlobExtractor(
                        webView = targetWebView,
                        blobUrl = url,
                        suggestedFilename = "vodplus_export.json",
                        mimeType = mimetype
                    )
                }
                url.startsWith("data:", ignoreCase = true) -> {
                    val parsed = WebViewDownloadHelper.parseDataUri(url)
                    if (parsed != null) {
                        val cleanName = WebViewDownloadHelper.sanitizeFilename(
                            null,
                            parsed.suggestedExtension
                        )
                        val uri = WebViewDownloadHelper.saveBytesToPublicDownloads(
                            this@MainActivity,
                            parsed.data,
                            cleanName,
                            parsed.mimeType
                        )
                        if (uri != null) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.download_completed, cleanName),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.download_failed, "保存文件失败"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                else -> {
                    WebViewDownloadHelper.downloadHttpUrl(
                        context = this@MainActivity,
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimetype
                    )
                }
            }
        }

        targetWebView.webViewClient = SnifferWebViewClient(
            onPageTitleChanged = { title ->
                title?.let { etUrl.setHint(it) }
            },
            onPageLoadingChanged = { isLoading ->
                progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                if (isLoading) {
                    isInlineVideoPlaying = false
                    updateKeepScreenOn()
                    logMemoryUsage("page_load_start")
                } else {
                    val currentUrl = targetWebView.url
                    if (!currentUrl.isNullOrBlank() && !currentUrl.startsWith("about:") && !currentUrl.startsWith("javascript:")) {
                        lastValidUrl = currentUrl
                        etUrl.setText(currentUrl)
                    }
                    updateBookmarkIconState(currentUrl)
                    logMemoryUsage("page_load_finished")
                }
            },
            onPageUrlChanged = { url ->
                if (!url.isNullOrBlank() && !url.startsWith("about:") && !url.startsWith("javascript:")) {
                    lastValidUrl = url
                }
            },
            onRendererGoneCallback = { didCrash, failedUrl ->
                Log.e(TAG, "[WebViewRenderer] Triggering safe WebView recreation: didCrash=$didCrash, failedUrl=$failedUrl")
                recreateWebView("RendererGone(didCrash=$didCrash)", didCrash, failedUrl)
            }
        )

        targetWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                try {
                    if (view == null) {
                        callback?.onCustomViewHidden()
                        return
                    }

                    if (customView != null) {
                        onHideCustomView()
                    }

                    customView = view
                    customViewCallback = callback

                    // Safely detach view from any existing parent before adding
                    (view.parent as? ViewGroup)?.removeView(view)

                    // Hide normal browser UI but keep WebView INVISIBLE to preserve hardware Surface
                    topBar.visibility = View.GONE
                    bottomBar.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    targetWebView.visibility = View.INVISIBLE
                    containerControl.visibility = View.GONE

                    // Attach custom view to fullscreen container
                    fullscreenContainer.removeAllViews()
                    fullscreenContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    fullscreenContainer.visibility = View.VISIBLE

                    // Immersive full screen: hide status bar and navigation bar
                    insetsController?.let { controller ->
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }

                    // Keep screen awake during fullscreen video playback
                    updateKeepScreenOn()

                    Log.d(TAG, "Entered fullscreen custom view")
                } catch (e: Throwable) {
                    Log.e(TAG, "Error in onShowCustomView: ${e.message}", e)
                    try {
                        callback?.onCustomViewHidden()
                    } catch (_: Throwable) {}
                    customView = null
                    customViewCallback = null
                    topBar.visibility = View.VISIBLE
                    bottomBar.visibility = View.VISIBLE
                    targetWebView.visibility = View.VISIBLE
                    fullscreenContainer.visibility = View.GONE
                }
            }

            override fun onHideCustomView() {
                try {
                    if (customView == null) return

                    // Detach custom view
                    (customView?.parent as? ViewGroup)?.removeView(customView)
                    fullscreenContainer.removeAllViews()
                    fullscreenContainer.visibility = View.GONE
                    customView = null

                    // Restore browser UI
                    topBar.visibility = View.VISIBLE
                    bottomBar.visibility = View.VISIBLE
                    targetWebView.visibility = View.VISIBLE
                    if (CastingForegroundService.instance?.currentStatus?.hasActiveStream == true) {
                        containerControl.visibility = View.VISIBLE
                    }

                    // Restore system bars
                    insetsController?.show(WindowInsetsCompat.Type.systemBars())

                    // Update screen keep-awake state
                    updateKeepScreenOn()

                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    Log.d(TAG, "Exited fullscreen custom view")
                } catch (e: Throwable) {
                    Log.e(TAG, "Error in onHideCustomView: ${e.message}", e)
                    customView = null
                    customViewCallback = null
                }
            }

            override fun onShowFileChooser(
                wv: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                try {
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, getString(R.string.file_chooser_title))
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed launching file chooser: ${e.message}")
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebConsole", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("提示")
                    .setMessage(message ?: "")
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("提示")
                    .setMessage(message ?: "")
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue)
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(message ?: "输入")
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        // Seamless touch transfer: clear URL bar focus when tapping webpage
        targetWebView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (etUrl.hasFocus()) {
                    etUrl.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(etUrl.windowToken, 0)
                }
            }
            false // Return false so MotionEvent is delivered seamlessly to DOM
        }
    }

    private fun recreateWebView(reason: String, didCrash: Boolean, failedUrl: String?) {
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                Log.w(TAG, "recreateWebView skipped because activity is finishing or destroyed")
                return@runOnUiThread
            }
            if (isRecreatingWebView) {
                Log.w(TAG, "recreateWebView ignored, recreation already in progress")
                return@runOnUiThread
            }
            isRecreatingWebView = true
            logMemoryUsage("before_recreateWebView")

            val targetUrl = failedUrl?.takeIf { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("javascript:") }
                ?: lastValidUrl.takeIf { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("javascript:") }
                ?: etUrl.text.toString().trim().takeIf { it.isNotBlank() }
                ?: AppPreferences.getHomepageUrl(this@MainActivity)

            Log.w(TAG, "[WebViewRenderer] Recreating WebView due to: $reason (targetUrl=$targetUrl, didCrash=$didCrash)")

            // 1. Cleanly tear down the old destroyed/crashed WebView instance
            try {
                webViewContainer.removeView(webView)
                webView.stopLoading()
                webView.removeJavascriptInterface(BlobDownloadBridge.JAVASCRIPT_NAME)
                webView.removeJavascriptInterface(SnifferBridge.JAVASCRIPT_NAME)
                webView.clearHistory()
                webView.destroy()
            } catch (e: Throwable) {
                Log.w(TAG, "Error cleaning old WebView during recreation: ${e.message}")
            }

            // 2. Create and attach fresh WebView instance
            val newWebView = WebView(this@MainActivity).apply {
                id = R.id.web_view
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            webViewContainer.addView(newWebView)
            this.webView = newWebView

            // 3. Setup configurations & clients on the new instance
            setupWebViewInstance(newWebView)

            // 4. Restore navigation
            newWebView.loadUrl(targetUrl)

            isRecreatingWebView = false
            logMemoryUsage("after_recreateWebView")
            Toast.makeText(this@MainActivity, "网页渲染引擎已恢复", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        CandidateManager.addListener { candidates ->
            currentCandidates = candidates
            updateCastBadge(candidates.size)
        }

        btnHome.setOnClickListener {
            val homeUrl = AppPreferences.getHomepageUrl(this)
            lastValidUrl = homeUrl
            etUrl.setText(homeUrl)
            webView.loadUrl(homeUrl)
            updateBookmarkIconState(homeUrl)
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        btnRefresh.setOnClickListener {
            webView.reload()
        }

        btnBookmark.setOnClickListener {
            BookmarkDialog.show(
                activity = this,
                currentUrl = webView.url ?: lastValidUrl,
                currentTitle = webView.title
            ) { targetUrl ->
                lastValidUrl = targetUrl
                etUrl.setText(targetUrl)
                webView.loadUrl(targetUrl)
                updateBookmarkIconState(targetUrl)
            }
        }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                var input = etUrl.text.toString().trim()
                if (!input.startsWith("http://") && !input.startsWith("https://")) {
                    input = "https://$input"
                }
                lastValidUrl = input
                webView.loadUrl(input)
                etUrl.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etUrl.windowToken, 0)
                true
            } else {
                false
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnCast.setOnClickListener {
            handleCastButtonClick()
        }

        playbackControlBar.onNextEpisodeListener = {
            triggerNextEpisodeAndCast()
        }
    }

    private fun updateBookmarkIconState(url: String? = webView.url) {
        runOnUiThread {
            try {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val isBookmarked = BookmarkManager.isBookmarked(this, url)
                if (isBookmarked) {
                    btnBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                    ImageViewCompat.setImageTintList(
                        btnBookmark,
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_primary))
                    )
                } else {
                    btnBookmark.setImageResource(R.drawable.ic_bookmark)
                    ImageViewCompat.setImageTintList(
                        btnBookmark,
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error updating bookmark icon state: ${e.message}")
            }
        }
    }

    private fun updateCastBadge(count: Int) {
        runOnUiThread {
            try {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (count > 0) {
                    tvBadgeCount.visibility = View.VISIBLE
                    tvBadgeCount.text = count.toString()
                    btnCast.text = "投屏 ($count)"
                } else {
                    tvBadgeCount.visibility = View.GONE
                    tvBadgeCount.text = "投屏"
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error updating cast badge: ${e.message}")
            }
        }
    }

    private fun handleCastButtonClick() {
        if (currentCandidates.isNotEmpty()) {
            showCandidatesOrCast(currentCandidates)
            return
        }

        // Active video scan in webpage DOM on demand
        Toast.makeText(this, "正在主动嗅探网页视频...", Toast.LENGTH_SHORT).show()
        VideoSnifferHelper.scanVideoInPage(webView) { scannedUrls ->
            if (scannedUrls.isNotEmpty()) {
                val pageTitle = webView.title ?: "在线视频"
                val userAgent = webView.settings.userAgentString
                val ref = webView.url
                for (url in scannedUrls) {
                    val candidate = VideoCandidate(
                        url = url,
                        referer = ref,
                        userAgent = userAgent,
                        cookie = CookieManager.getInstance().getCookie(url),
                        title = pageTitle,
                        timestamp = System.currentTimeMillis()
                    )
                    CandidateManager.addCandidate(candidate)
                }
                showCandidatesOrCast(CandidateManager.getCandidates())
            } else {
                Toast.makeText(this, R.string.no_candidates, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCandidatesOrCast(candidates: List<VideoCandidate>) {
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.no_candidates, Toast.LENGTH_SHORT).show()
            return
        }
        if (candidates.size == 1) {
            pickDeviceAndCast(candidates.first())
        } else {
            CandidateDialog(
                context = this,
                candidates = candidates,
                onClearRequested = {
                    Toast.makeText(this, "已清空候选，正在重新探测当前播放视频...", Toast.LENGTH_SHORT).show()
                    VideoSnifferHelper.scanVideoInPage(webView) { scannedUrls ->
                        if (scannedUrls.isNotEmpty()) {
                            val pageTitle = webView.title ?: "在线视频"
                            val userAgent = webView.settings.userAgentString
                            val ref = webView.url
                            for (url in scannedUrls) {
                                val candidate = VideoCandidate(
                                    url = url,
                                    referer = ref,
                                    userAgent = userAgent,
                                    cookie = CookieManager.getInstance().getCookie(url),
                                    title = pageTitle,
                                    timestamp = System.currentTimeMillis()
                                )
                                CandidateManager.addCandidate(candidate)
                            }
                        }
                    }
                },
                onCandidateSelected = { candidate ->
                    pickDeviceAndCast(candidate)
                }
            ).show()
        }
    }

    private fun pickDeviceAndCast(candidate: VideoCandidate) {
        DeviceSelectDialog(
            context = this,
            lifecycleScope = lifecycleScope,
            onDeviceSelected = { device ->
                startCasting(candidate, device.location, device.friendlyName)
            }
        ).show()
    }

    private fun startCasting(candidate: VideoCandidate, deviceUrl: String, deviceName: String) {
        playbackControlBar.showLoading(candidate.title, deviceName)

        val service = CastingForegroundService.instance
        if (service != null) {
            service.startCasting(candidate, deviceUrl, deviceName)
        } else {
            val intent = Intent(this, CastingForegroundService::class.java).apply {
                action = CastingForegroundService.ACTION_START_CAST
                putExtra(CastingForegroundService.EXTRA_VIDEO_URL, candidate.url)
                putExtra(CastingForegroundService.EXTRA_VIDEO_TITLE, candidate.title)
                putExtra(CastingForegroundService.EXTRA_DEVICE_LOCATION, deviceUrl)
                putExtra(CastingForegroundService.EXTRA_DEVICE_NAME, deviceName)
                putExtra(CastingForegroundService.EXTRA_REFERER, candidate.referer)
                putExtra(CastingForegroundService.EXTRA_USER_AGENT, candidate.userAgent)
                putExtra(CastingForegroundService.EXTRA_COOKIE, candidate.cookie)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun triggerNextEpisodeAndCast() {
        val nextEpisodeScript = """
            (function() {
                var buttons = document.querySelectorAll('button, a, span, div');
                for (var i = 0; i < buttons.length; i++) {
                    var el = buttons[i];
                    var text = (el.innerText || el.textContent || '').trim();
                    if (text === '下一集' || text === '下一话' || text === 'Next' || text === 'Next Episode') {
                        el.click();
                        return true;
                    }
                }
                var currentActive = document.querySelector('.active, .current, [class*="active"], [class*="current"]');
                if (currentActive) {
                    var next = currentActive.nextElementSibling;
                    if (next) {
                        var target = next.querySelector('a, button') || next;
                        target.click();
                        return true;
                    }
                }
                return false;
            })();
        """.trimIndent()

        webView.evaluateJavascript(nextEpisodeScript) { result ->
            val clicked = result?.toBoolean() ?: false
            if (clicked) {
                Toast.makeText(this, "正在切换下一集并嗅探...", Toast.LENGTH_SHORT).show()
                CandidateManager.clear()
                lifecycleScope.launch {
                    delay(3000L)
                    val candidates = CandidateManager.getCandidates()
                    if (candidates.isNotEmpty()) {
                        val currentStatus = CastingForegroundService.instance?.currentStatus
                        if (currentStatus != null && currentStatus.hasActiveStream) {
                            startCasting(candidates.first(), currentStatus.deviceLocation, currentStatus.deviceName)
                        } else {
                            showCandidatesOrCast(candidates)
                        }
                    }
                }
            } else {
                Toast.makeText(this, "未找到下一集按钮，请在网页中手动点击", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    val chromeClient = webView.webChromeClient
                    chromeClient?.onHideCustomView()
                    return
                }

                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }

                finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        logMemoryUsage("onResume")
        try {
            webView.onResume()
            CookieManager.getInstance().flush()
        } catch (e: Throwable) {
            Log.w(TAG, "Error in onResume: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        logMemoryUsage("onPause")
        try {
            webView.onPause()
            CookieManager.getInstance().flush()
        } catch (e: Throwable) {
            Log.w(TAG, "Error in onPause: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        logMemoryUsage("onStop")
        try {
            CookieManager.getInstance().flush()
        } catch (e: Throwable) {
            Log.w(TAG, "Error in onStop: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logMemoryUsage("onDestroy_start")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        fallbackDlnaManager?.release()
        try {
            CookieManager.getInstance().flush()
            webView.removeJavascriptInterface(BlobDownloadBridge.JAVASCRIPT_NAME)
            webView.removeJavascriptInterface(SnifferBridge.JAVASCRIPT_NAME)
            webView.stopLoading()
            webView.clearHistory()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "Error during webView destruction: ${e.message}")
        }
        logMemoryUsage("onDestroy_done")
    }
}
