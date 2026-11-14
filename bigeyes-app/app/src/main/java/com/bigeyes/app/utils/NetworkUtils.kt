package com.bigeyes.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    private val EXCLUDED_IFACE_PREFIXES = listOf(
        "dummy", "tun", "utun", "tap", "p2p", "vpn", "docker", "veth", "virbr", "rmnet", "clat", "ppp", "lo"
    )

    fun isVirtualOrCellInterface(name: String): Boolean {
        val lower = name.lowercase()
        return EXCLUDED_IFACE_PREFIXES.any { lower.contains(it) }
    }

    fun getLocalIpAddress(context: Context? = null): String {
        // 1. Prefer active WiFi/Ethernet LinkProperties IPv4 via ConnectivityManager
        if (context != null) {
            try {
                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNet = cm?.activeNetwork
                if (cm != null && activeNet != null) {
                    val caps = cm.getNetworkCapabilities(activeNet)
                    if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        val linkProps = cm.getLinkProperties(activeNet)
                        val ipv4 = linkProps?.linkAddresses
                            ?.map { it.address }
                            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                            ?.hostAddress
                        if (!ipv4.isNullOrBlank() && !ipv4.startsWith("127.")) {
                            return ipv4
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // 2. Fallback to WifiManager for legacy devices
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
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

        // 3. Iterate NetworkInterfaces, preferring wlan/eth private LAN IPv4 addresses
        try {
            var fallbackHost: String? = null
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (isVirtualOrCellInterface(name)) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.isBlank() || host.startsWith("127.")) continue
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                            return host
                        }
                        if (addr.isSiteLocalAddress && fallbackHost == null) {
                            fallbackHost = host
                        } else if (fallbackHost == null) {
                            fallbackHost = host
                        }
                    }
                }
            }
            if (fallbackHost != null) return fallbackHost
        } catch (_: Exception) {
        }

        return "127.0.0.1"
    }
}
