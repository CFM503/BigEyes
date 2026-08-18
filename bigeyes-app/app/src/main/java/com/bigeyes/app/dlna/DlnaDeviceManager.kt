package com.bigeyes.app.dlna

import android.content.Context
import android.util.Log
import com.bigeyes.app.model.DlnaDevice
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class DlnaDeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "DlnaDeviceManager"
        private const val PREFS_NAME = "bigeyes_dlna"
        private const val KEY_LAST_DEVICE_ID = "last_device_id"
    }

    private val scanner = SsdpScanner()
    val controller = DlnaController()
    private val devices = ConcurrentHashMap<String, DlnaDevice>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    private var selectedDeviceId: String? = null

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedDeviceId = prefs.getString(KEY_LAST_DEVICE_ID, null)
    }

    suspend fun scanOnce(): List<DlnaDevice> {
        val found = scanner.scan()
        val now = System.currentTimeMillis()
        for (dev in found) {
            dev.lastSeen = now
            if (!devices.containsKey(dev.id)) {
                Log.i(TAG, "Discovered DLNA TV: ${dev.name} (${dev.ip})")
            }
            dev.selected = (dev.id == selectedDeviceId)
            devices[dev.id] = dev
        }

        // Clean stale devices (> 120s)
        val stale = devices.filter { now - it.value.lastSeen > 120_000L }.keys
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
