package com.bigeyes.app.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    fun getLocalIpAddress(context: Context? = null): String {
        // 1. Try WifiManager if context is available
        if (context != null) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    @Suppress("DEPRECATION")
                    val ipStr = Formatter.formatIpAddress(ipInt)
                    if (!ipStr.isNullOrBlank() && ipStr != "0.0.0.0" && !ipStr.startsWith("127.")) {
                        return ipStr
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. Iterate NetworkInterfaces
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.contains("dummy") || name.contains("tun") || name.contains("p2p")) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return "127.0.0.1"
    }
}
