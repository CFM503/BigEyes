package com.bigeyes.app.dlna

import android.content.Context
import android.util.Log
import com.bigeyes.app.model.DlnaDevice
import kotlinx.coroutines.*
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap

class DlnaDeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "DlnaDeviceManager"
        private const val PREFS_NAME = "bigeyes_dlna"
        private const val KEY_LAST_DEVICE_ID = "last_device_id"
        private const val KEY_MANUAL_URLS = "manual_device_urls"
    }

    private val scanner = SsdpScanner()
    val controller = DlnaController()
    private val devices = ConcurrentHashMap<String, DlnaDevice>()
    private val manualUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    private var selectedDeviceId: String? = null

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedDeviceId = prefs.getString(KEY_LAST_DEVICE_ID, null)
        val saved = prefs.getStringSet(KEY_MANUAL_URLS, emptySet()) ?: emptySet()
        manualUrls.addAll(saved)
        if (manualUrls.isNotEmpty()) {
            scope.launch {
                for (url in manualUrls) {
                    try {
                        val dev = scanner.probeLocation(url)
                        if (dev != null) {
                            dev.selected = (dev.id == selectedDeviceId)
                            dev.lastSeen = System.currentTimeMillis()
                            devices[dev.id] = dev
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    suspend fun addManualDevice(input: String): DlnaDevice? {
        val dev = scanner.probeLocation(input) ?: return null
        dev.lastSeen = System.currentTimeMillis()
        manualUrls.add(dev.locationUrl)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MANUAL_URLS, HashSet(manualUrls))
            .apply()

        selectDevice(dev.id)
        devices[dev.id] = dev
        Log.i(TAG, "Manually added DLNA device: ${dev.name} (${dev.ip}) -> ${dev.locationUrl}")
        return dev
    }

    suspend fun scanOnce(): List<DlnaDevice> {
        val found = scanner.scan(context)
        val now = System.currentTimeMillis()
        for (dev in found) {
            dev.lastSeen = now
            if (!devices.containsKey(dev.id)) {
                Log.i(TAG, "Discovered DLNA TV: ${dev.name} (${dev.ip})")
            }
            dev.selected = (dev.id == selectedDeviceId)
            devices[dev.id] = dev
        }

        // Re-probe saved manual devices
        for (url in HashSet(manualUrls)) {
            try {
                val dev = scanner.probeLocation(url)
                if (dev != null) {
                    dev.lastSeen = now
                    dev.selected = (dev.id == selectedDeviceId)
                    devices[dev.id] = dev
                }
            } catch (_: Exception) {
            }
        }

        // Clean stale scanned devices (> 120s), keep manual devices
        val stale = devices.filter { now - it.value.lastSeen > 120_000L && !manualUrls.contains(it.value.locationUrl) }.keys
        stale.forEach { devices.remove(it) }

        // Auto select if only 1 device
        if (devices.size == 1 && selectedDeviceId == null) {
            val sole = devices.values.first()
            selectDevice(sole.id)
        }

        return getDevices()
    }

    fun getDevices(): List<DlnaDevice> {
        return devices.values.map { it.copy(selected = (it.id == selectedDeviceId)) }
    }

    fun selectDevice(deviceId: String) {
        selectedDeviceId = deviceId
        devices.values.forEach { it.selected = (it.id == deviceId) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_ID, deviceId)
            .apply()
    }

    fun getSelectedDevice(): DlnaDevice? {
        selectedDeviceId?.let { id ->
            devices[id]?.let { return it }
        }
        if (devices.size == 1) {
            return devices.values.first()
        }
        return null
    }

    fun startPeriodicScan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            while (isActive) {
                try {
                    scanOnce()
                } catch (e: Exception) {
                    Log.d(TAG, "Background SSDP scan: ${e.message}")
                }
                delay(20_000L)
            }
        }
    }

    fun stopPeriodicScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun release() {
        stopPeriodicScan()
        scope.cancel()
    }
}
