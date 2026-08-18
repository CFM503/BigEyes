package com.bigeyes.app.dlna

import android.util.Log
import android.util.Xml
import com.bigeyes.app.model.DlnaDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.*
import java.util.Collections
import java.util.concurrent.TimeUnit

class SsdpScanner {

    companion object {
        private const val TAG = "SsdpScanner"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun buildMSearchPacket(st: String): ByteArray {
        val msg = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $st\r\n\r\n"
        return msg.toByteArray(Charsets.UTF_8)
    }

    suspend fun scan(timeoutMs: Long = 2500L): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val locations = Collections.synchronizedSet(mutableSetOf<String>())
        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket()
            socket.soTimeout = 400
            val group = InetAddress.getByName(SSDP_ADDR)

            for (st in SEARCH_TARGETS) {
                val data = buildMSearchPacket(st)
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                try {
                    socket.send(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "SSDP send error for $st: ${e.message}")
                }
            }

            val startTime = System.currentTimeMillis()
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val recvPacket = DatagramPacket(buf, buf.size)
                    socket.receive(recvPacket)
                    val response = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                    extractLocation(response)?.let { locations.add(it) }
                } catch (_: SocketTimeoutException) {
                    // loop until overall timeout
                } catch (e: Exception) {
                    Log.d(TAG, "SSDP recv finished or interrupted: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP socket error: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }

        val devices = mutableListOf<DlnaDevice>()
        for (loc in locations) {
            fetchDeviceInfo(loc)?.let { dev ->
                if (!dev.avTransportControlUrl.isNullOrBlank()) {
                    devices.add(dev)
                }
            }
        }

        return@withContext devices
    }

    private fun extractLocation(response: String): String? {
        for (line in response.lines()) {
            if (line.uppercase().startsWith("LOCATION:")) {
                return line.substring(line.indexOf(':') + 1).trim()
            }
        }
        return null
    }

    private fun fetchDeviceInfo(locationUrl: String): DlnaDevice? {
        return try {
            val request = Request.Builder().url(locationUrl).build()
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val xml = resp.body?.string() ?: return null
                    parseDeviceXml(xml, locationUrl)
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed fetching device info from $locationUrl: ${e.message}")
            null
        }
    }

    private fun parseDeviceXml(xmlText: String, locationUrl: String): DlnaDevice? {
        return try {
            val parsedUrl = URI(locationUrl)
            val ip = parsedUrl.host ?: ""

            var friendlyName = "Unknown TV"
            var udn = ""
            var avControlUrl: String? = null
            var renderingControlUrl: String? = null

            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xmlText))

            var eventType = parser.eventType
            var inService = false
            var currentServiceType = ""
            var currentControlUrl = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.lowercase() ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tag == "service") {
                            inService = true
                            currentServiceType = ""
                            currentControlUrl = ""
                        } else if (tag == "friendlyname" && friendlyName == "Unknown TV") {
                            friendlyName = parser.nextText().trim()
                        } else if (tag == "udn" && udn.isEmpty()) {
                            udn = parser.nextText().trim()
                        } else if (inService && tag == "servicetype") {
                            currentServiceType = parser.nextText().trim()
                        } else if (inService && tag == "controlurl") {
                            currentControlUrl = parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag == "service") {
                            inService = false
                            if (currentServiceType.contains("AVTransport") && currentControlUrl.isNotEmpty()) {
                                avControlUrl = resolveUrl(locationUrl, currentControlUrl)
                            } else if (currentServiceType.contains("RenderingControl") && currentControlUrl.isNotEmpty()) {
                                renderingControlUrl = resolveUrl(locationUrl, currentControlUrl)
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (udn.isEmpty()) udn = "uuid-$ip"

            DlnaDevice(
                id = udn,
                name = friendlyName,
                ip = ip,
                locationUrl = locationUrl,
                avTransportControlUrl = avControlUrl,
                renderingControlUrl = renderingControlUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing XML from $locationUrl: ${e.message}")
            null
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            relative
        }
    }
}
