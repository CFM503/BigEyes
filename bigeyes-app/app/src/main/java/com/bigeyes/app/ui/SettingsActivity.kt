package com.bigeyes.app.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bigeyes.app.R
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.dlna.DlnaDeviceManager
import com.bigeyes.app.model.VideoCandidate
import com.bigeyes.app.service.CastingForegroundService
import com.bigeyes.app.updater.UpdateManager
import com.bigeyes.app.utils.NetworkUtils
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvAppVersion: TextView
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnOpenGithub: Button

    private lateinit var tvLocalServerInfo: TextView
    private lateinit var tvVlcM3u8Url: TextView
    private lateinit var btnCopyVlcUrl: Button
    private lateinit var btnCopyBaseUrl: Button

    private lateinit var tvCandidateDetails: TextView
    private lateinit var btnCopyOrigUrl: Button
    private lateinit var btnCopyAllCandidateInfo: Button

    private lateinit var tvDlnaDeviceList: TextView
    private lateinit var btnRescanDlna: Button

    private lateinit var tvCacheInfo: TextView
    private lateinit var btnClearCache: Button
    private lateinit var btnIgnoreBattery: Button
    private lateinit var tvDebugLogs: TextView
    private lateinit var btnClearLogs: Button

    private var currentVlcUrl: String = ""
    private var currentBaseUrl: String = ""
    private var latestCandidate: VideoCandidate? = null
    private var fallbackDlnaManager: DlnaDeviceManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadVersionInfo()
        loadServerAndVlcInfo()
        loadLatestCandidateInfo()
        loadDlnaDeviceList()
        displayLogs()
    }

    private fun initViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvAppVersion = findViewById(R.id.tv_app_version)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        btnOpenGithub = findViewById(R.id.btn_open_github)

        tvLocalServerInfo = findViewById(R.id.tv_local_server_info)
        tvVlcM3u8Url = findViewById(R.id.tv_vlc_m3u8_url)
        btnCopyVlcUrl = findViewById(R.id.btn_copy_vlc_url)
        btnCopyBaseUrl = findViewById(R.id.btn_copy_base_url)

        tvCandidateDetails = findViewById(R.id.tv_candidate_details)
        btnCopyOrigUrl = findViewById(R.id.btn_copy_orig_url)
        btnCopyAllCandidateInfo = findViewById(R.id.btn_copy_all_candidate_info)

        tvDlnaDeviceList = findViewById(R.id.tv_dlna_device_list)
        btnRescanDlna = findViewById(R.id.btn_rescan_dlna)

        tvCacheInfo = findViewById(R.id.tv_cache_info)
        btnClearCache = findViewById(R.id.btn_clear_cache)
        btnIgnoreBattery = findViewById(R.id.btn_ignore_battery)
        tvDebugLogs = findViewById(R.id.tv_debug_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)

        btnCheckUpdate.setOnClickListener {
            UpdateManager.checkUpdate(this, silent = false)
        }

        btnOpenGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/CFM503/BigEyes"))
            startActivity(intent)
        }

        btnCopyVlcUrl.setOnClickListener {
            if (currentVlcUrl.isNotEmpty() && !currentVlcUrl.contains("(请先")) {
                copyToClipboard("VLC 播放地址", currentVlcUrl)
            } else {
                Toast.makeText(this, "暂无可用的 VLC 播放地址，请先在浏览器中播放视频", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopyBaseUrl.setOnClickListener {
            if (currentBaseUrl.isNotEmpty()) {
                copyToClipboard("代理前缀", currentBaseUrl)
            }
        }

        btnCopyOrigUrl.setOnClickListener {
            latestCandidate?.url?.let {
                copyToClipboard("原始 M3U8 地址", it)
            } ?: Toast.makeText(this, "暂无嗅探候选", Toast.LENGTH_SHORT).show()
        }

        btnCopyAllCandidateInfo.setOnClickListener {
            val candidate = latestCandidate
            if (candidate != null) {
                val fullInfo = "Title: ${candidate.title}\n" +
                        "Stream URL: ${candidate.url}\n" +
                        "Referer: ${candidate.referer ?: "(None)"}\n" +
                        "User-Agent: ${candidate.userAgent ?: "(None)"}\n" +
                        "Cookie: ${candidate.cookie ?: "(None)"}"
                copyToClipboard("完整抓包参数", fullInfo)
            } else {
                Toast.makeText(this, "暂无嗅探候选", Toast.LENGTH_SHORT).show()
            }
        }

        btnRescanDlna.setOnClickListener {
            performDlnaScan()
        }

        btnIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        btnClearCache.setOnClickListener {
            clearStreamCache()
        }

        btnClearLogs.setOnClickListener {
            CandidateManager.clear()
            loadLatestCandidateInfo()
            displayLogs()
        }
    }

    private fun loadVersionInfo() {
        val ver = UpdateManager.getCurrentVersionName(this)
        tvAppVersion.text = "v$ver"
    }

    private fun loadServerAndVlcInfo() {
        val ip = NetworkUtils.getLocalIpAddress(this)
        currentBaseUrl = "http://$ip:8765"
        tvLocalServerInfo.text = "本机局域网 IP: $ip\n内嵌代理端口: 8765\n代理根地址: $currentBaseUrl\n线程池模式: 4~16 弹性线程池 (NanoHTTPD Pooled Runner)"

        val activeSession = CastingForegroundService.instance?.streamManager?.getActiveSession()
        if (activeSession != null) {
            currentVlcUrl = "$currentBaseUrl/stream/${activeSession.streamId}/index.m3u8"
            tvVlcM3u8Url.text = "当前活跃 StreamID: ${activeSession.streamId}\nVLC 播放地址:\n$currentVlcUrl"
        } else {
            val candidate = CandidateManager.getCandidates().firstOrNull()
            if (candidate != null) {
                tvVlcM3u8Url.text = "最新嗅探视频: ${candidate.displayTitle}\n(点击主界面投屏按钮或直接在 VLC 中访问代理地址)"
            } else {
                tvVlcM3u8Url.text = "VLC 播放地址: (请先在浏览器中嗅探并播放视频)"
            }
        }

        updateCacheDisplay()
    }

    private fun loadLatestCandidateInfo() {
        val candidates = CandidateManager.getCandidates()
        latestCandidate = candidates.firstOrNull()

        if (latestCandidate == null) {
            tvCandidateDetails.text = "暂无嗅探候选。在内置浏览器中打开影视站播放视频后，此处将自动展示抓包参数。"
            return
        }

        val c = latestCandidate!!
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("【视频标题】: ${c.displayTitle}\n")
        sb.append("【嗅探时间】: ${sdf.format(Date(c.timestamp))}\n\n")
        sb.append("【原始 M3U8 URL】:\n${c.url}\n\n")
        sb.append("【Referer】: ${c.referer ?: "(无)"}\n")
        sb.append("【User-Agent】: ${c.userAgent ?: "(无)"}\n")
        sb.append("【Cookie】: ${c.cookie ?: "(无)"}")
        tvCandidateDetails.text = sb.toString()
    }

    private fun loadDlnaDeviceList() {
        val service = CastingForegroundService.instance
        val dlnaManager = service?.dlnaManager ?: fallbackDlnaManager ?: DlnaDeviceManager(this).also { fallbackDlnaManager = it }
        val devices = dlnaManager.getDevices()

        if (devices.isEmpty()) {
            tvDlnaDeviceList.text = "暂未发现 DLNA 设备。\n请确保手机与电视/Kodi 在同一局域网 WiFi 下，然后点击上方【立即扫描】。"
        } else {
            val sb = StringBuilder()
            sb.append("发现 ${devices.size} 台局域网 DLNA 设备:\n\n")
            for ((idx, dev) in devices.withIndex()) {
                sb.append("---------------- [设备 #${idx + 1}] ----------------\n")
                sb.append("名称: ${dev.name}\n")
                sb.append("IP: ${dev.ip}\n")
                sb.append("XML 描述: ${dev.locationUrl}\n")
                sb.append("AVTransport 控制: ${dev.avTransportControlUrl ?: "(未发现)"}\n")
                sb.append("RenderingControl: ${dev.renderingControlUrl ?: "(无)"}\n")
                sb.append("当前选中: ${if (dev.selected) "是 (默认投屏目标)" else "否"}\n\n")
            }
            tvDlnaDeviceList.text = sb.toString().trimEnd()
        }
    }

    private fun performDlnaScan() {
        val service = CastingForegroundService.instance
        val dlnaManager = service?.dlnaManager ?: fallbackDlnaManager ?: DlnaDeviceManager(this).also { fallbackDlnaManager = it }

        tvDlnaDeviceList.text = "正在发送 SSDP UDP 组播 M-SEARCH 探测局域网 DLNA / Kodi 设备..."
        Toast.makeText(this, "正在扫描局域网设备...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val devices = dlnaManager.scanOnce()
                if (devices.isEmpty()) {
                    tvDlnaDeviceList.text = "扫描完成，未发现 DLNA 设备。\n请确认电视/Kodi 设备已开机、开启了 UPnP/DLNA 渲染器服务，并与手机处于同一 WiFi 路由器。"
                    Toast.makeText(this@SettingsActivity, "未扫描到 DLNA 设备", Toast.LENGTH_SHORT).show()
                } else {
                    loadDlnaDeviceList()
                    Toast.makeText(this@SettingsActivity, "发现 ${devices.size} 台设备", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                tvDlnaDeviceList.text = "扫描出错: ${e.message}"
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this, "已复制 $label 到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun updateCacheDisplay() {
        val cacheDir = File(cacheDir, "bigeyes_stream_cache")
        val sizeBytes = if (cacheDir.exists()) {
            cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else 0L

        val sizeMb = sizeBytes / (1024 * 1024)
        tvCacheInfo.text = "缓存占用: $sizeMb MB / 300 MB"
    }

    private fun clearStreamCache() {
        CastingForegroundService.instance?.streamManager?.cache?.clear()
            ?: run {
                val cacheDir = File(cacheDir, "bigeyes_stream_cache")
                cacheDir.listFiles()?.forEach { it.delete() }
            }
        updateCacheDisplay()
        Toast.makeText(this, "分片缓存已清空", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                }
            } else {
                Toast.makeText(this, "已处于电池优化白名单中，锁屏保活已就绪", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "当前系统版本无需配置电池优化", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayLogs() {
        val logs = CandidateManager.getSniffLogs()
        if (logs.isEmpty()) {
            tvDebugLogs.text = "暂无抓包记录。"
            return
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        for ((idx, log) in logs.withIndex()) {
            sb.append("---------------- [抓包记录 #${idx + 1} | ${sdf.format(Date(log.timestamp))}] ----------------\n")
            sb.append("URL: ${log.url}\n\n")
            sb.append("Request Headers:\n")
            if (log.headers.isEmpty()) {
                sb.append("  (无自定义 Header)\n")
            } else {
                for ((k, v) in log.headers) {
                    sb.append("  $k: $v\n")
                }
            }
            sb.append("\n")
        }
        tvDebugLogs.text = sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        fallbackDlnaManager?.release()
    }
}
