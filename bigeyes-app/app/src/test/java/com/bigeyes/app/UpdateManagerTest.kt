package com.bigeyes.app

import com.bigeyes.app.updater.UpdateManager
import org.junit.Assert.*
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testVersionComparison() {
        // Standard newer patch/minor/major
        assertTrue(UpdateManager.isNewerVersion("2.0.3", "2.0.2"))
        assertTrue(UpdateManager.isNewerVersion("2.1.0", "2.0.2"))
        assertTrue(UpdateManager.isNewerVersion("3.0.0", "2.0.2"))
        assertTrue(UpdateManager.isNewerVersion("2.0.2.1", "2.0.2"))

        // With 'v' prefix
        assertTrue(UpdateManager.isNewerVersion("v2.0.3", "2.0.2"))
        assertTrue(UpdateManager.isNewerVersion("v2.1.0", "v2.0.2"))

        // Same version
        assertFalse(UpdateManager.isNewerVersion("2.0.2", "2.0.2"))
        assertFalse(UpdateManager.isNewerVersion("v2.0.2", "2.0.2"))

        // Older version
        assertFalse(UpdateManager.isNewerVersion("2.0.1", "2.0.2"))
        assertFalse(UpdateManager.isNewerVersion("1.9.9", "2.0.2"))
        assertFalse(UpdateManager.isNewerVersion("2.0.0", "2.0.2"))
    }
}
