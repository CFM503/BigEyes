package com.bigeyes.app

import com.bigeyes.app.dlna.SsdpScanner
import org.junit.Assert.*
import org.junit.Test

class SsdpScannerTest {

    private val samsungTvXml = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
            <specVersion>
                <major>1</major>
                <minor>0</minor>
            </specVersion>
            <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>[TV] Samsung 7 Series (55)</friendlyName>
                <manufacturer>Samsung Electronics</manufacturer>
                <UDN>uuid:12345678-1234-1234-1234-123456789abc</UDN>
                <serviceList>
                    <service>
                        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                        <controlURL>/upnp/control/RenderingControl1</controlURL>
                        <eventSubURL>/upnp/event/RenderingControl1</eventSubURL>
                        <SCPDURL>/smp_2_</SCPDURL>
                    </service>
                    <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                        <controlURL>/upnp/control/AVTransport1</controlURL>
                        <eventSubURL>/upnp/event/AVTransport1</eventSubURL>
                        <SCPDURL>/smp_3_</SCPDURL>
                    </service>
                </serviceList>
            </device>
        </root>
    """.trimIndent()

    private val xiaomiTvXml = """<?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
            <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>客厅小米电视</friendlyName>
                <UDN>uuid:mi-tv-998877</UDN>
                <serviceList>
                    <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>http://192.168.1.105:49152/upnp/control/AVTransport</controlURL>
                    </service>
                </serviceList>
            </device>
        </root>
    """.trimIndent()

    private val minimalTvXml = """<?xml version="1.0"?>
        <root>
            <device>
                <friendlyName>Bedroom Smart Projector</friendlyName>
                <serviceList>
                    <service>
                        <serviceType>urn:schemas-upnp-org:service:avtransport:1</serviceType>
                        <controlURL>ctrl/avt</controlURL>
                    </service>
                </serviceList>
            </device>
        </root>
    """.trimIndent()

    @Test
    fun testSamsungTvXmlParsing() {
        val locationUrl = "http://192.168.1.88:7676/smp_4_"
        val device = SsdpScanner.parseDeviceXml(samsungTvXml, locationUrl)

        assertNotNull(device)
        assertEquals("[TV] Samsung 7 Series (55)", device?.name)
        assertEquals("192.168.1.88", device?.ip)
        assertEquals("uuid:12345678-1234-1234-1234-123456789abc", device?.id)
        assertEquals("http://192.168.1.88:7676/upnp/control/AVTransport1", device?.avTransportControlUrl)
        assertEquals("http://192.168.1.88:7676/upnp/control/RenderingControl1", device?.renderingControlUrl)
    }

    @Test
    fun testXiaomiTvXmlParsing() {
        val locationUrl = "http://192.168.1.105:49152/description.xml"
        val device = SsdpScanner.parseDeviceXml(xiaomiTvXml, locationUrl)

        assertNotNull(device)
        assertEquals("客厅小米电视", device?.name)
        assertEquals("192.168.1.105", device?.ip)
        assertEquals("uuid:mi-tv-998877", device?.id)
        // Absolute controlURL should be preserved as-is
        assertEquals("http://192.168.1.105:49152/upnp/control/AVTransport", device?.avTransportControlUrl)
        assertNull(device?.renderingControlUrl)
    }

    @Test
    fun testMinimalTvXmlParsing() {
        val locationUrl = "http://192.168.1.120:8080/desc.xml"
        val device = SsdpScanner.parseDeviceXml(minimalTvXml, locationUrl)

        assertNotNull(device)
        assertEquals("Bedroom Smart Projector", device?.name)
        assertEquals("192.168.1.120", device?.ip)
        assertEquals("uuid-192.168.1.120", device?.id) // Fallback UDN
        assertEquals("http://192.168.1.120:8080/ctrl/avt", device?.avTransportControlUrl)
    }

    @Test
    fun testKodiXmlParsing() {
        val kodiXml = """<?xml version="1.0" encoding="UTF-8"?>
            <root configId="9477" xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Kodi (HP)</friendlyName>
                <UDN>uuid:59928482-eb55-6226-d1a4-45484596f792</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/AVTransport/59928482-eb55-6226-d1a4-45484596f792/control.xml</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/RenderingControl/59928482-eb55-6226-d1a4-45484596f792/control.xml</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val locationUrl = "http://192.168.68.236:1700/"
        val device = SsdpScanner.parseDeviceXml(kodiXml, locationUrl)

        assertNotNull(device)
        assertEquals("Kodi (HP)", device?.name)
        assertEquals("192.168.68.236", device?.ip)
        assertEquals("uuid:59928482-eb55-6226-d1a4-45484596f792", device?.id)
        assertEquals("http://192.168.68.236:1700/AVTransport/59928482-eb55-6226-d1a4-45484596f792/control.xml", device?.avTransportControlUrl)
        assertEquals("http://192.168.68.236:1700/RenderingControl/59928482-eb55-6226-d1a4-45484596f792/control.xml", device?.renderingControlUrl)
    }
}
