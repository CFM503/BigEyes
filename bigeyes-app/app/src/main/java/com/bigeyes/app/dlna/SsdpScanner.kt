package com.bigeyes.app.dlna

import com.bigeyes.app.model.DlnaDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.*
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class SsdpScanner {

    companion object {
        private const val TAG = "SsdpScanner"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )

        fun parseDeviceXml(xmlText: String, locationUrl: String): DlnaDevice? {
            return try {
                val parsedUrl = URI(locationUrl)
                val ip = parsedUrl.host ?: ""

                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = false
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(InputSource(StringReader(xmlText)))
                doc.documentElement.normalize()

                fun getFirstTagText(parent: Element, tagName: String): String? {
                    val list = parent.getElementsByTagName(tagName)
                    if (list.length > 0) {
                        return list.item(0).textContent?.trim()
                    }
                    val children = parent.childNodes
                    for (i in 0 until children.length) {
                        val node = children.item(i)
                        if (node is Element && node.tagName.equals(tagName, ignoreCase = true)) {
                            return node.textContent?.trim()
                        }
                    }
                    return null
                }

                fun getRootTagText(tagName: String): String? {
                    val list = doc.getElementsByTagName(tagName)
                    if (list.length > 0) {
                        return list.item(0).textContent?.trim()
                    }
                    val all = doc.getElementsByTagName("*")
                    for (i in 0 until all.length) {
                        val node = all.item(i) as? Element ?: continue
                        if (node.tagName.equals(tagName, ignoreCase = true)) {
                            return node.textContent?.trim()
                        }
                    }
                    return null
                }

                val friendlyName = getRootTagText("friendlyName") ?: "Unknown TV"
                var udn = getRootTagText("UDN") ?: ""
                if (udn.isEmpty()) udn = "uuid-$ip"

                var avControlUrl: String? = null
                var renderingControlUrl: String? = null

                val serviceNodes = doc.getElementsByTagName("service")
                for (i in 0 until serviceNodes.length) {
                    val elem = serviceNodes.item(i) as? Element ?: continue
                    val serviceType = getFirstTagText(elem, "serviceType") ?: ""
                    val controlUrl = getFirstTagText(elem, "controlURL") ?: ""

                    if (serviceType.contains("AVTransport", ignoreCase = true) && controlUrl.isNotEmpty()) {
                        avControlUrl = resolveUrl(locationUrl, controlUrl)
                    } else if (serviceType.contains("RenderingControl", ignoreCase = true) && controlUrl.isNotEmpty()) {
                        renderingControlUrl = resolveUrl(locationUrl, controlUrl)
                    }
                }

                DlnaDevice(
                    id = udn,
                    name = friendlyName,
                    ip = ip,
                    locationUrl = locationUrl,
                    avTransportControlUrl = avControlUrl,
                    renderingControlUrl = renderingControlUrl
                )
            } catch (e: Exception) {
                System.err.println("Error parsing XML from $locationUrl: ${e.message}")
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
                    System.err.println("SSDP send error for $st: ${e.message}")
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
                    break
                }
            }
        } catch (e: Exception) {
            System.err.println("SSDP socket error: ${e.message}")
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
            null
        }
    }
}
