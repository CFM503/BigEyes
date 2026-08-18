package com.bigeyes.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bigeyes.app.R
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.discovery.NsdDiscoveryManager
import com.bigeyes.app.network.ServerApiClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var discoveryManager: NsdDiscoveryManager
    private val apiClient = ServerApiClient()

    private lateinit var tvDiscoveryStatus: TextView
    private lateinit var etManualIp: TextInputEditText
    private lateinit var etManualPort: TextInputEditText
    private lateinit var btnSaveConfig: Button
    private lateinit var btnTestConn: Button
    private lateinit var tvDebugLogs: TextView
    private lateinit var btnClearLogs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        discoveryManager = NsdDiscoveryManager(this)

        initViews()
        loadConfig()
        displayLogs()
    }

    private fun initViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvDiscoveryStatus = findViewById(R.id.tv_discovery_status)
        etManualIp = findViewById(R.id.et_manual_ip)
        etManualPort = findViewById(R.id.et_manual_port)
        btnSaveConfig = findViewById(R.id.btn_save_config)
        btnTestConn = findViewById(R.id.btn_test_conn)
        tvDebugLogs = findViewById(R.id.tv_debug_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)

        btnSaveConfig.setOnClickListener {
            saveConfig()
        }

        btnTestConn.setOnClickListener {
            testConnection()
        }

        btnClearLogs.setOnClickListener {
            CandidateManager.clear()
            displayLogs()
        }
    }

    private fun loadConfig() {
        val (manualHost, manualPort) = discoveryManager.getManualServer()
        if (!manualHost.isNullOrBlank()) {
            etManualIp.setText(manualHost)
            etManualPort.setText(manualPort.toString())
            apiClient.updateServerAddress(manualHost, manualPort)
            tvDiscoveryStatus.text = "当前使用手动配置: $manualHost:$manualPort"
        } else {
            val lastServer = discoveryManager.getLastKnownServer()
            if (lastServer != null) {
                tvDiscoveryStatus.text = "mDNS 最近发现: ${lastServer.first}:${lastServer.second}"
                apiClient.updateServerAddress(lastServer.first, lastServer.second)
            } else {
                tvDiscoveryStatus.text = "mDNS 正在自动搜索中..."
            }
        }
    }

    private fun saveConfig() {
        val ip = etManualIp.text?.toString()?.trim()
        val portStr = etManualPort.text?.toString()?.trim()
        val port = portStr?.toIntOrNull() ?: 8765

        if (!ip.isNullOrBlank()) {
            discoveryManager.setManualServer(ip, port)
            apiClient.updateServerAddress(ip, port)
            tvDiscoveryStatus.text = "手动配置已保存: $ip:$port"
            Toast.makeText(this, "PC 服务地址已保存", Toast.LENGTH_SHORT).show()
        } else {
            discoveryManager.setManualServer(null, 8765)
            tvDiscoveryStatus.text = "已切换为 mDNS 自动发现"
            Toast.makeText(this, "已清除手动配置，恢复自动发现", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testConnection() {
        val ip = etManualIp.text?.toString()?.trim()
        val port = etManualPort.text?.toString()?.trim()?.toIntOrNull() ?: 8765

        if (!ip.isNullOrBlank()) {
            apiClient.updateServerAddress(ip, port)
        }

        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, "正在测试与 PC 服务的连通性...", Toast.LENGTH_SHORT).show()
            val result = apiClient.checkConnection()
            if (result.isSuccess && result.getOrDefault(false)) {
                Toast.makeText(this@SettingsActivity, "连接成功！PC 服务运行正常", Toast.LENGTH_LONG).show()
            } else {
                val err = result.exceptionOrNull()?.message ?: "未知错误"
                Toast.makeText(this@SettingsActivity, "连接失败: $err", Toast.LENGTH_LONG).show()
            }
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
