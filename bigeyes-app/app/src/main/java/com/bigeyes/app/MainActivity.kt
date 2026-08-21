package com.bigeyes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bigeyes.app.browser.BlobDownloadBridge
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.browser.SnifferBridge
import com.bigeyes.app.browser.SnifferWebViewClient
import com.bigeyes.app.browser.VideoSnifferHelper
import com.bigeyes.app.browser.WebViewDownloadHelper
import com.bigeyes.app.dlna.DlnaDeviceManager
import com.bigeyes.app.model.VideoCandidate
import com.bigeyes.app.service.CastingForegroundService
import com.bigeyes.app.ui.CandidateDialog
import com.bigeyes.app.ui.DeviceSelectDialog
import com.bigeyes.app.ui.PlaybackControlBar
import com.bigeyes.app.ui.SettingsActivity
import com.bigeyes.app.updater.UpdateManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnCast: Button
    private lateinit var tvBadgeCount: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var topBar: View
    private lateinit var containerControl: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var playbackControlBar: PlaybackControlBar

    private var isInlineVideoPlaying: Boolean = false

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
        setContentView(R.layout.activity_main)

        insetsController = WindowCompat.getInsetsController(window, window.decorView)

        initViews()
        setupWindowInsets()
        setupWebView()
        setupListeners()
        setupBackNavigation()
        requestNotificationPermission()

        // Ensure Foreground Service is started to host NanoHTTPD & DLNA
        startCastingService()

        // Asynchronous silent check for app updates
        checkForUpdatesSilently()

        // Default landing page
        val defaultUrl = "https://vodplus.pages.dev"
        etUrl.setText(defaultUrl)
        webView.loadUrl(defaultUrl)
    }

    private fun checkForUpdatesSilently() {
        lifecycleScope.launch {
            delay(2000L) // Wait 2s for UI initialization
            UpdateManager.checkUpdate(this@MainActivity, silent = true)
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.web_view)
        topBar = findViewById(R.id.top_bar)
        etUrl = findViewById(R.id.et_url)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnRefresh = findViewById(R.id.btn_refresh)
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
            val shouldKeepOn = (customView != null) || isInlineVideoPlaying
            if (shouldKeepOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                containerControl.setPadding(
                    containerControl.paddingLeft,
                    containerControl.paddingTop,
                    containerControl.paddingRight,
                    navInsets.bottom
                )
            } else {
                topBar.setPadding(topBar.paddingLeft, 0, topBar.paddingRight, topBar.paddingBottom)
                containerControl.setPadding(containerControl.paddingLeft, containerControl.paddingTop, containerControl.paddingRight, 0)
            }
            insets
        }
    }

    private fun startCastingService() {
        val intent = Intent(this, CastingForegroundService::class.java).apply {
            action = CastingForegroundService.ACTION_START_CAST
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
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
    private fun setupWebView() {
        val settings = webView.settings
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
        settings.setSupportMultipleWindows(false) // Blocks popup ads

        // Prevent unwanted whole-page pinch-zoom from breaking SPA layout & touch coordinates
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Register Blob Download JavascriptInterface bridge
        val blobBridge = BlobDownloadBridge(this)
        webView.addJavascriptInterface(blobBridge, BlobDownloadBridge.JAVASCRIPT_NAME)

        // Register Real-time Video Sniffer JavascriptInterface bridge
        val snifferBridge = SnifferBridge(
            getCurrentUserAgent = { webView.settings.userAgentString },
            getCurrentCookie = { url -> CookieManager.getInstance().getCookie(url) },
            onPlaybackStateListener = { isPlaying ->
                isInlineVideoPlaying = isPlaying
                updateKeepScreenOn()
            }
        )
        webView.addJavascriptInterface(snifferBridge, SnifferBridge.JAVASCRIPT_NAME)

        // Download Listener for Blob, Data URI, and HTTP/HTTPS files
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            Log.d(TAG, "Download requested: $url, mime: $mimetype")
            when {
                url.startsWith("blob:", ignoreCase = true) -> {
                    WebViewDownloadHelper.injectBlobExtractor(
                        webView = webView,
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

        webView.webViewClient = SnifferWebViewClient(
            onPageTitleChanged = { title ->
                title?.let { etUrl.setHint(it) }
            },
            onPageLoadingChanged = { isLoading ->
                progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                if (isLoading) {
                    isInlineVideoPlaying = false
                    updateKeepScreenOn()
                }
            }
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null || view == null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback

                // Hide normal browser UI
                topBar.visibility = View.GONE
                progressBar.visibility = View.GONE
                webView.visibility = View.GONE
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

                // Switch orientation to landscape for optimal video viewing
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                // Keep screen awake during fullscreen video playback
                updateKeepScreenOn()

                Log.d(TAG, "Entered fullscreen custom view")
            }

            override fun onHideCustomView() {
                if (customView == null) return

                // Detach custom view
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                customView = null

                // Restore browser UI
                topBar.visibility = View.VISIBLE
                webView.visibility = View.VISIBLE
                if (CastingForegroundService.instance?.currentStatus?.hasActiveStream == true) {
                    containerControl.visibility = View.VISIBLE
                }

                // Restore system bars
                insetsController?.show(WindowInsetsCompat.Type.systemBars())

                // Restore portrait / unspecified orientation
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                // Update screen keep-awake state
                updateKeepScreenOn()

                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                Log.d(TAG, "Exited fullscreen custom view")
            }

            override fun onShowFileChooser(
                webView: WebView?,
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
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed launching file chooser: ${e.message}", e)
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(
                    "BigEyesWebConsole",
                    "${consoleMessage?.message()} -- [Line ${consoleMessage?.lineNumber()}] of ${consoleMessage?.sourceId()}"
                )
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
        webView.setOnTouchListener { _, event ->
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

    private fun setupListeners() {
        CandidateManager.addListener { candidates ->
            currentCandidates = candidates
            updateCastBadge(candidates.size)
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

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                var input = etUrl.text.toString().trim()
                if (!input.startsWith("http://") && !input.startsWith("https://")) {
                    input = "https://$input"
                }
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

    private fun updateCastBadge(count: Int) {
        if (count > 0) {
            tvBadgeCount.visibility = View.VISIBLE
            tvBadgeCount.text = count.toString()
            btnCast.text = "投屏 ($count)"
        } else {
            tvBadgeCount.visibility = View.GONE
            tvBadgeCount.text = "投屏"
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
                            Toast.makeText(this, "重新捕获到 ${scannedUrls.size} 个视频源", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "未探测到正在播放的视频流，请播放后重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onSelected = { selectedCandidate ->
                    pickDeviceAndCast(selectedCandidate)
                }
            ).show()
        }
    }

    private fun pickDeviceAndCast(candidate: VideoCandidate) {
        val dlnaManager = CastingForegroundService.instance?.dlnaManager
            ?: fallbackDlnaManager ?: DlnaDeviceManager(this).also { fallbackDlnaManager = it }

        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在扫描局域网电视设备...", Toast.LENGTH_SHORT).show()
            val devices = dlnaManager.scanOnce()

            if (devices.isEmpty()) {
                showManualDeviceDialog(candidate)
            } else if (devices.size > 1) {
                DeviceSelectDialog(
                    context = this@MainActivity,
                    devices = devices,
                    onManualAdd = { showManualDeviceDialog(candidate) }
                ) { selectedDevice ->
                    executeCast(candidate, selectedDevice.id)
                }.show()
            } else {
                val soleId = devices.firstOrNull()?.id
                executeCast(candidate, soleId)
            }
        }
    }

    private fun showManualDeviceDialog(candidate: VideoCandidate) {
        val input = EditText(this).apply {
            hint = "例如 192.168.68.236:1700"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("手动输入投屏设备 IP")
            .setMessage("未自动搜到设备（可能受路由器组播限制）。请输入电脑 Kodi 或电视的 IP 与端口进行直连：")
            .setView(input)
            .setPositiveButton("连接并投屏") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val dlnaManager = CastingForegroundService.instance?.dlnaManager
                        ?: fallbackDlnaManager ?: DlnaDeviceManager(this).also { fallbackDlnaManager = it }

                    Toast.makeText(this@MainActivity, "正在连接 $text ...", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        val dev = dlnaManager.addManualDevice(text)
                        if (dev != null) {
                            Toast.makeText(this@MainActivity, "已连接 ${dev.name}，正在开播...", Toast.LENGTH_SHORT).show()
                            executeCast(candidate, dev.id)
                        } else {
                            Toast.makeText(this@MainActivity, "连接失败：无法解析 $text 的 DLNA 协议", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeCast(candidate: VideoCandidate, targetDeviceId: String?) {
        val service = CastingForegroundService.instance
        if (service == null) {
            startCastingService()
            Toast.makeText(this, "正在初始化本地投屏服务，请重试", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在由手机本地代理推送至电视...", Toast.LENGTH_SHORT).show()

        service.castCandidate(candidate, targetDeviceId) { success, devName ->
            if (success) {
                val targetName = devName ?: "电视"
                Toast.makeText(this@MainActivity, "已成功投屏至 $targetName", Toast.LENGTH_LONG).show()
                playbackControlBar.show(candidate.displayTitle, targetName)
            } else {
                val err = devName ?: "未找到可用的 DLNA 电视设备"
                Toast.makeText(this@MainActivity, "投屏失败: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CastingForegroundService.instance?.onAutoNextEpisodeListener = {
            runOnUiThread {
                triggerNextEpisodeAndCast()
            }
        }
    }

    private var isAutoAdvancing = false

    private fun triggerNextEpisodeAndCast() {
        if (isAutoAdvancing) return
        isAutoAdvancing = true

        Toast.makeText(this, "正在为您切换下一集并投屏...", Toast.LENGTH_SHORT).show()
        val currentTargetId = CastingForegroundService.instance?.dlnaManager?.getSelectedDevice()?.id

        CandidateManager.clear()

        var candidateListener: ((List<VideoCandidate>) -> Unit)? = null
        candidateListener = { candidates ->
            if (candidates.isNotEmpty() && isAutoAdvancing) {
                isAutoAdvancing = false
                candidateListener?.let { CandidateManager.removeListener(it) }
                val newCandidate = candidates.first()
                runOnUiThread {
                    Toast.makeText(this, "已获取下一集: ${newCandidate.displayTitle}，正在推流...", Toast.LENGTH_SHORT).show()
                    executeCast(newCandidate, currentTargetId)
                }
            }
        }
        CandidateManager.addListener(candidateListener)

        VideoSnifferHelper.triggerNextEpisode(webView) { success ->
            if (!success) {
                Log.d(TAG, "No next episode element found via DOM, waiting for user or fallback scan...")
            }
            lifecycleScope.launch {
                delay(15000L)
                if (isAutoAdvancing) {
                    isAutoAdvancing = false
                    candidateListener?.let { CandidateManager.removeListener(it) }
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    // Step 1: Exit HTML5 Fullscreen custom view if active
                    webView.webChromeClient?.onHideCustomView()
                } else if (webView.canGoBack()) {
                    // Step 2: Navigate back in WebView history
                    webView.goBack()
                } else {
                    // Step 3: Exit App
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        fallbackDlnaManager?.release()
        webView.destroy()
    }
}
