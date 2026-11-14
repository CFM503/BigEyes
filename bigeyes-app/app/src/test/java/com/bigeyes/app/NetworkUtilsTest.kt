package com.bigeyes.app

import com.bigeyes.app.utils.NetworkUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun testVirtualAndCellularInterfacesAreExcluded() {
        assertTrue(NetworkUtils.isVirtualOrCellInterface("tun0"))
        assertTrue(NetworkUtils.isVirtualOrCellInterface("utun3"))
        assertTrue(NetworkUtils.isVirtualOrCellInterface("rmnet_data0"))
        assertTrue(NetworkUtils.isVirtualOrCellInterface("clat4"))
        assertTrue(NetworkUtils.isVirtualOrCellInterface("docker0"))
        assertTrue(NetworkUtils.isVirtualOrCellInterface("veth1234"))
        assertTrue(NetworkUtils.isVirtualOrCellInterface("p2p-wlan0-0"))
        assertTrue(NetworkUtils.isVirtualOrCellInterface("dummy0"))
        assertTrue(NetworkUtils.isVirtualOrCellInterface("lo"))
    }

    @Test
    fun testPhysicalLanInterfacesAreAllowed() {
        assertFalse(NetworkUtils.isVirtualOrCellInterface("wlan0"))
        assertFalse(NetworkUtils.isVirtualOrCellInterface("eth0"))
        assertFalse(NetworkUtils.isVirtualOrCellInterface("ap0"))
    }
}
