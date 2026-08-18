package com.bigeyes.app

import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.browser.SnifferWebViewClient
import com.bigeyes.app.discovery.NsdDiscoveryManager
import com.bigeyes.app.model.DlnaDevice
import com.bigeyes.app.model.VideoCandidate
import com.bigeyes.app.network.ServerApiClient
import com.bigeyes.app.ui.CandidateDialog
import com.bigeyes.app.ui.DeviceSelectDialog
import com.bigeyes.app.ui.PlaybackControlBar
import com.bigeyes.app.ui.SettingsActivity
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

    private val apiClient = ServerApiClient()
    private lateinit var discoveryManager: NsdDiscoveryManager

    private var currentCandidates: List<VideoCandidate> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupDiscovery()
        setupListeners()
        setupBackNavigation()

        // Default landing page
        webView.loadUrl("https://v.qq.com")
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

        playbackControlBar = PlaybackControlBar(containerControl, apiClient, lifecycleScope)
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

        webView.webViewClient = SnifferWebViewClient(
            onPageTitleChanged = { title ->
                if (!title.isNullOrBlank()) {
                    // Update address bar if needed
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
        }
    }

    private fun setupDiscovery() {
        discoveryManager = NsdDiscoveryManager(this).apply {
            onServerFound = { host, port ->
                apiClient.updateServerAddress(host, port)
            }
            onServerLost = {
                // Keep last known address
            }
            startDiscovery()
        }
    }

    private fun setupListeners() {
        // Candidate observer
        CandidateManager.addListener { candidates ->
            currentCandidates = candidates
            updateCastBadge(candidates.size)
        }

        // Navigation
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

        // Cast button click
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
            btnCast.text = "投屏"
        }
    }

    private fun handleCastButtonClick() {
        if (currentCandidates.isEmpty()) {
            Toast.makeText(this, R.string.no_candidates, Toast.LENGTH_SHORT).show()
            return
        }

        if (apiClient.baseUrl == null) {
            Toast.makeText(this, R.string.pc_not_found, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        if (currentCandidates.size == 1) {
            // Single candidate -> cast directly
            proceedToCast(currentCandidates.first())
        } else {
            // Multiple candidates -> show dialog
            CandidateDialog(this, currentCandidates) { selectedCandidate ->
                proceedToCast(selectedCandidate)
            }.show()
        }
    }

    private fun proceedToCast(candidate: VideoCandidate) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在连接 PC 服务与电视...", Toast.LENGTH_SHORT).show()
            
            // Check available DLNA devices from PC
            val devicesResult = apiClient.getDevices()
            val devices = devicesResult.getOrDefault(emptyList())

            if (devices.size > 1) {
                // Multiple TVs found -> let user select
                DeviceSelectDialog(this@MainActivity, devices) { selectedDevice ->
                    lifecycleScope.launch {
                        apiClient.selectDevice(selectedDevice.id)
                        executeCast(candidate, selectedDevice.name)
                    }
                }.show()
            } else {
                // 0 or 1 device -> auto cast
                val deviceName = devices.firstOrNull()?.name
                executeCast(candidate, deviceName)
            }
        }
    }

    private suspend fun executeCast(candidate: VideoCandidate, deviceName: String?) {
        val result = apiClient.cast(candidate)
        result.onSuccess { json ->
            val dev = json.optString("device", deviceName ?: "电视")
            Toast.makeText(this@MainActivity, "投屏已发起: $dev", Toast.LENGTH_LONG).show()
            playbackControlBar.show(candidate.displayTitle, dev)
        }.onFailure { err ->
            Toast.makeText(this@MainActivity, "投屏失败: ${err.message}", Toast.LENGTH_LONG).show()
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
        discoveryManager.release()
        webView.destroy()
    }
}
