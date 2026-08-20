package com.bigeyes.app.dlna

import android.text.TextUtils
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

class DlnaController {

    companion object {
        private const val TAG = "DlnaController"
        private val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
        private const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private suspend fun sendSoapAction(
        controlUrl: String,
        serviceType: String,
        actionName: String,
        arguments: Map<String, String>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val argsXml = StringBuilder()
        for ((k, v) in arguments) {
            argsXml.append("<$k>").append(TextUtils.htmlEncode(v)).append("</$k>")
        }

        val soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "  <s:Body>\r\n" +
                "    <u:$actionName xmlns:u=\"$serviceType\">\r\n" +
                "      $argsXml\r\n" +
                "    </u:$actionName>\r\n" +
                "  </s:Body>\r\n" +
                "</s:Envelope>"

        val request = Request.Builder()
            .url(controlUrl)
            .header("SOAPAction", "\"$serviceType#$actionName\"")
            .post(soapBody.toRequestBody(XML_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Pair(true, body)
                } else {
                    Log.w(TAG, "SOAP action $actionName failed with HTTP ${resp.code}: $body")
                    Pair(false, body)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOAP action $actionName connection error: ${e.message}")
            Pair(false, e.message.orEmpty())
        }
    }

    suspend fun setAvTransportUri(
        controlUrl: String,
        uri: String,
        title: String = "BigEyes Video"
    ): Boolean {
        val escapedTitle = TextUtils.htmlEncode(title)
        val didlMetadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>$escapedTitle</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:application/vnd.apple.mpegurl:*\">$uri</res>" +
                "</item></DIDL-Lite>"

        val args = mapOf(
            "InstanceID" to "0",
            "CurrentURI" to uri,
            "CurrentURIMetaData" to didlMetadata
        )
        val (ok, _) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "SetAVTransportURI", args)
        return ok
    }

    suspend fun setNextAvTransportUri(
        controlUrl: String,
        uri: String,
        title: String = "BigEyes Video Next"
    ): Boolean {
        val escapedTitle = TextUtils.htmlEncode(title)
        val didlMetadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>$escapedTitle</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:application/vnd.apple.mpegurl:*\">$uri</res>" +
                "</item></DIDL-Lite>"

        val args = mapOf(
            "InstanceID" to "0",
            "NextURI" to uri,
            "NextURIMetaData" to didlMetadata
        )
        val (ok, _) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "SetNextAVTransportURI", args)
        return ok
    }

    suspend fun play(controlUrl: String, speed: String = "1"): Boolean {
        val args = mapOf(
            "InstanceID" to "0",
            "Speed" to speed
        )
        val (ok, _) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "Play", args)
        return ok
    }

    suspend fun pause(controlUrl: String): Boolean {
        val args = mapOf("InstanceID" to "0")
        val (ok, _) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "Pause", args)
        return ok
    }

    suspend fun stop(controlUrl: String): Boolean {
        val args = mapOf("InstanceID" to "0")
        val (ok, _) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "Stop", args)
        return ok
    }

    suspend fun next(controlUrl: String): Boolean {
        val args = mapOf("InstanceID" to "0")
        val (ok, _) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "Next", args)
        return ok
    }

    suspend fun previous(controlUrl: String): Boolean {
        val args = mapOf("InstanceID" to "0")
        val (ok, _) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "Previous", args)
        return ok
    }

    suspend fun seek(controlUrl: String, targetTime: String): Boolean {
        val timeStr = if (targetTime.contains(':')) targetTime else formatTime(targetTime)
        val args = mapOf(
            "InstanceID" to "0",
            "Unit" to "REL_TIME",
            "Target" to timeStr
        )
        val (ok, _) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "Seek", args)
        return ok
    }

    suspend fun getPositionInfo(controlUrl: String): Map<String, String> {
        val result = mutableMapOf(
            "rel_time" to "00:00:00",
            "track_duration" to "00:00:00",
            "track_uri" to ""
        )
        val (ok, xml) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "GetPositionInfo", mapOf("InstanceID" to "0"))
        if (ok && xml.isNotEmpty()) {
            try {
                val parser = Xml.newPullParser()
                parser.setInput(StringReader(xml))
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val name = parser.name
                        if (name.equals("RelTime", ignoreCase = true)) {
                            result["rel_time"] = parser.nextText().trim()
                        } else if (name.equals("TrackDuration", ignoreCase = true)) {
                            result["track_duration"] = parser.nextText().trim()
                        } else if (name.equals("TrackURI", ignoreCase = true)) {
                            result["track_uri"] = parser.nextText().trim()
                        }
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error parsing GetPositionInfo XML: ${e.message}")
            }
        }
        return result
    }

    suspend fun getTransportInfo(controlUrl: String): Map<String, String> {
        val result = mutableMapOf(
            "current_transport_state" to "STOPPED",
            "current_transport_status" to "OK"
        )
        val (ok, xml) = sendSoapAction(controlUrl, SERVICE_AV_TRANSPORT, "GetTransportInfo", mapOf("InstanceID" to "0"))
        if (ok && xml.isNotEmpty()) {
            try {
                val parser = Xml.newPullParser()
                parser.setInput(StringReader(xml))
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val name = parser.name
                        if (name.equals("CurrentTransportState", ignoreCase = true)) {
                            result["current_transport_state"] = parser.nextText().trim()
                        } else if (name.equals("CurrentTransportStatus", ignoreCase = true)) {
                            result["current_transport_status"] = parser.nextText().trim()
                        }
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error parsing GetTransportInfo XML: ${e.message}")
            }
        }
        return result
    }

    private fun formatTime(secondsStr: String): String {
        val totalSecs = secondsStr.toDoubleOrNull()?.toInt() ?: 0
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
