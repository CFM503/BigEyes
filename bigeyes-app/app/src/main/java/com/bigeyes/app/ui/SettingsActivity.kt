package com.bigeyes.app.ui

import android.annotation.SuppressLint
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
import com.bigeyes.app.R
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.service.CastingForegroundService
import com.bigeyes.app.utils.NetworkUtils
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvLocalServerInfo: TextView
    private lateinit var tvCacheInfo: TextView
    private lateinit var btnClearCache: Button
    private lateinit var btnIgnoreBattery: Button
    private lateinit var tvDebugLogs: TextView
    private lateinit var btnClearLogs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadServerAndCacheInfo()
        displayLogs()
    }

    private fun initViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvLocalServerInfo = findViewById(R.id.tv_local_server_info)
        tvCacheInfo = findViewById(R.id.tv_cache_info)
        btnClearCache = findViewById(R.id.btn_clear_cache)
        btnIgnoreBattery = findViewById(R.id.btn_ignore_battery)
        tvDebugLogs = findViewById(R.id.tv_debug_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)

        btnIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        btnClearCache.setOnClickListener {
            clearStreamCache()
        }

        btnClearLogs.setOnClickListener {
            CandidateManager.clear()
            displayLogs()
        }
    }

    private fun loadServerAndCacheInfo() {
        val ip = NetworkUtils.getLocalIpAddress(this)
        tvLocalServerInfo.text = "本机局域网 IP: $ip\n内嵌代理端口: 8765\n预取并发: 2~3 (手机专属低功耗调优)\n代理模式: 纯手机独立运行 (无须PC)"

        updateCacheDisplay()
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
            tvDebugLogs.text = "暂无抓包记录。在网页中播放视频后，此处将展示原始嗅探 URL 及 Headers 上下文。"
            return
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        for ((idx, log) in logs.withIndex()) {
            sb.append("---------------- [记录 #${idx + 1} | ${sdf.format(Date(log.timestamp))}] ----------------\n")
            sb.append("URL: ${log.url}\n\n")
            sb.append("Request Headers:\n")
            if (log.headers.isEmpty()) {
                sb.append("  (None)\n")
            } else {
                for ((k, v) in log.headers) {
                    sb.append("  $k: $v\n")
                }
            }
            sb.append("\n\n")
        }
        tvDebugLogs.text = sb.toString()
    }
}
