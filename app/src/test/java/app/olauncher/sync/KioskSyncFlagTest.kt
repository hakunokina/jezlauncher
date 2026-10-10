package app.olauncher.sync

import app.olauncher.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/** Kiosk sync is off unless the build asks for it (-Pjezlauncher.kioskSync=true), and off means nothing starts. */
class KioskSyncFlagTest {

    @Test
    fun defaultBuildLeavesKioskSyncOff() {
        val properties = File("../gradle.properties").readLines().map { it.trim() }
        assertTrue(properties.contains("jezlauncher.kioskSync=false"))
    }

    @Test
    fun withoutTheFlagAStoredPairingGivesNoEndpoint() {
        // The single gate every sync start goes through (KioskSyncPrefs.pairing): no endpoint, no work, stream or request
        assertNull(activeEndpoint(enabled = false, base = "http://100.64.0.1:8787", token = "abc"))
        assertNotNull(activeEndpoint(enabled = true, base = "http://100.64.0.1:8787", token = "abc"))
        assertNull(activeEndpoint(enabled = true, base = null, token = "abc"))
        assertNull(activeEndpoint(enabled = true, base = "http://192.168.1.2:8787", token = "abc"))
    }

    @Test
    fun defaultBuildHasNoScannerCameraOrCleartext() {
        assumeFalse("built with kiosk sync", BuildConfig.KIOSK_SYNC)
        assertFalse(QrScanner.AVAILABLE)
        // Camera permission (zxing) and the cleartext config come only from the kiosk source set's manifest
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("networkSecurityConfig"))
        assertFalse(manifest.contains("android.permission.CAMERA"))
        assertTrue(File("src/kiosk/AndroidManifest.xml").readText().contains("network_security_config_kiosk"))
        assertEquals(false, BuildConfig.KIOSK_SYNC)
    }
}
