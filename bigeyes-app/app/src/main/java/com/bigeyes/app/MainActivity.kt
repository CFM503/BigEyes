package com.bigeyes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.browser.SnifferWebViewClient
import com.bigeyes.app.dlna.DlnaDeviceManager
import com.bigeyes.app.model.VideoCandidate
import com.bigeyes.app.service.CastingForegroundService
import com.bigeyes.app.ui.CandidateDialog
import com.bigeyes.app.ui.DeviceSelectDialog
import com.bigeyes.app.ui.PlaybackControlBar
import com.bigeyes.app.ui.SettingsActivity
import com.bigeyes.app.updater.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnCast: Button
    private lateinit var tvBadgeCount: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var containerControl: View
    private lateinit var playbackControlBar: PlaybackControlBar

    private var fallbackDlnaManager: DlnaDeviceManager? = null
    private var currentCandidates: List<VideoCandidate> = emptyList()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupListeners()
        setupBackNavigation()
        requestNotificationPermission()

        // Ensure Foreground Service is started to host NanoHTTPD & DLNA
        startCastingService()

        // Asynchronous silent check for app updates
        checkForUpdatesSilently()

        // Default landing page
        webView.loadUrl("https://v.qq.com")
    }

    private fun checkForUpdatesSilently() {
        lifecycleScope.launch {
            delay(2000L) // Wait 2s for UI initialization
            UpdateManager.checkUpdate(this@MainActivity, silent = true)
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.web_view)
        etUrl = findViewById(R.id.et_url)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnCast = findViewById(R.id.btn_cast)
        tvBadgeCount = findViewById(R.id.tv_badge_count)
        btnSettings = findViewById(R.id.btn_settings)
        progressBar = findViewById(R.id.progress_bar)
        containerControl = findViewById(R.id.container_playback_control)

        playbackControlBar = PlaybackControlBar(containerControl, lifecycleScope)
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false) // Blocks popup ads

        webView.webViewClient = SnifferWebViewClient()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
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

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                var input = etUrl.text.toString().trim()
                if (!input.startsWith("http://") && !input.startsWith("https://")) {
                    input = "https://$input"
                }
                webView.loadUrl(input)
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
        if (currentCandidates.isEmpty()) {
            Toast.makeText(this, R.string.no_candidates, Toast.LENGTH_SHORT).show()
            return
        }

        if (currentCandidates.size == 1) {
            pickDeviceAndCast(currentCandidates.first())
        } else {
            CandidateDialog(this, currentCandidates) { selectedCandidate ->
                pickDeviceAndCast(selectedCandidate)
            }.show()
        }
    }

    private fun pickDeviceAndCast(candidate: VideoCandidate) {
        val dlnaManager = CastingForegroundService.instance?.dlnaManager
            ?: fallbackDlnaManager ?: DlnaDeviceManager(this).also { fallbackDlnaManager = it }

        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在扫描局域网电视设备...", Toast.LENGTH_SHORT).show()
            val devices = dlnaManager.scanOnce()

            if (devices.size > 1) {
                DeviceSelectDialog(this@MainActivity, devices) { selectedDevice ->
                    executeCast(candidate, selectedDevice.id)
                }.show()
            } else {
                val soleId = devices.firstOrNull()?.id
                executeCast(candidate, soleId)
            }
        }
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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        fallbackDlnaManager?.release()
        webView.destroy()
    }
}
