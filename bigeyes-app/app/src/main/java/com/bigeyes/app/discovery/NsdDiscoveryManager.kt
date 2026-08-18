package com.bigeyes.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class NsdDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "NsdDiscoveryManager"
        private const val SERVICE_TYPE = "_bigeyes._tcp."
        private const val PREFS_NAME = "bigeyes_discovery"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_MANUAL_HOST = "manual_host"
        private const val KEY_MANUAL_PORT = "manual_port"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    private val mainHandler = Handler(Looper.getMainLooper())

    var onServerFound: ((host: String, port: Int) -> Unit)? = null
    var onServerLost: (() -> Unit)? = null

    init {
        acquireMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            multicastLock = wifiManager.createMulticastLock("BigEyesMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    fun getLastKnownServer(): Pair<String, Int>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Manual config takes precedence if present
        val manualHost = prefs.getString(KEY_MANUAL_HOST, null)
        val manualPort = prefs.getInt(KEY_MANUAL_PORT, 0)
        if (!manualHost.isNullOrBlank() && manualPort > 0) {
            return Pair(manualHost, manualPort)
        }
        val host = prefs.getString(KEY_LAST_HOST, null)
        val port = prefs.getInt(KEY_LAST_PORT, 0)
        return if (!host.isNullOrBlank() && port > 0) Pair(host, port) else null
    }

    fun saveLastKnownServer(host: String, port: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_HOST, host)
            .putInt(KEY_LAST_PORT, port)
            .apply()
    }

    fun setManualServer(host: String?, port: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MANUAL_HOST, host)
            .putInt(KEY_MANUAL_PORT, port)
            .apply()
    }

    fun getManualServer(): Pair<String?, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(prefs.getString(KEY_MANUAL_HOST, null), prefs.getInt(KEY_MANUAL_PORT, 8765))
    }

    fun startDiscovery() {
        if (isDiscovering) return

        // 1. Immediately emit last known server if available for fast connection
        getLastKnownServer()?.let { (host, port) ->
            onServerFound?.invoke(host, port)
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started: $regType")
                isDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName} (${service.serviceType})")
                if (service.serviceType.contains("bigeyes") || service.serviceName.contains("BigEyes")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                mainHandler.post { onServerLost?.invoke() }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: Error code $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: Error code $errorCode")
                isDiscovering = false
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service discovery: ${e.message}")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed: Error code $errorCode")
            }

            override fun onServiceResolved(service: NsdServiceInfo) {
                val host = service.host.hostAddress ?: return
                val port = service.port
                Log.i(TAG, "Successfully resolved BigEyes PC Server at $host:$port")
                saveLastKnownServer(host, port)
                mainHandler.post {
                    onServerFound?.invoke(host, port)
                }
            }
        })
    }

    fun stopDiscovery() {
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery: ${e.message}")
            }
            discoveryListener = null
            isDiscovering = false
        }
    }

    fun release() {
        stopDiscovery()
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        }
    }
}
