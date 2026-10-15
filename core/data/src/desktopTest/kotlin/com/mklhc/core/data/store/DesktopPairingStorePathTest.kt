package com.mklhc.core.data.store

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [desktopConfigDirectory] and [desktopPairingStorePath]: where the desktop build keeps its pairing file. */
class DesktopPairingStorePathTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun windowsUsesTheRoamingAppDataDirectory() {
        val dir = desktopConfigDirectory("Windows 11", "C:\\Users\\kid", env("APPDATA" to "C:\\Users\\kid\\AppData\\Roaming"))
        assertEquals("C:\\Users\\kid\\AppData\\Roaming", dir)
    }

    @Test
    fun windowsWithoutAppDataFallsBackToTheHomeAppDataFolder() {
        val dir = desktopConfigDirectory("Windows 10", "C:\\Users\\kid", env())
        assertEquals(File("C:\\Users\\kid", "AppData/Roaming").path, dir)
    }

    @Test
    fun macUsesApplicationSupport() {
        val dir = desktopConfigDirectory("Mac OS X", "/Users/kid", env())
        assertEquals(File("/Users/kid", "Library/Application Support").path, dir)
    }

    @Test
    fun linuxHonoursXdgConfigHome() {
        val dir = desktopConfigDirectory("Linux", "/home/kid", env("XDG_CONFIG_HOME" to "/data/cfg"))
        assertEquals("/data/cfg", dir)
    }

    @Test
    fun linuxFallsBackToDotConfigWhenXdgIsMissingOrBlank() {
        assertEquals(File("/home/kid", ".config").path, desktopConfigDirectory("Linux", "/home/kid", env()))
        assertEquals(File("/home/kid", ".config").path, desktopConfigDirectory("Linux", "/home/kid", env("XDG_CONFIG_HOME" to "  ")))
    }

    @Test
    fun pairingFileLivesInAnAppFolderUnderTheConfigDirectory() {
        val path = desktopPairingStorePath("/base")
        assertEquals(File(File("/base", "MKLHC"), "pairing.preferences_pb").path, path)
        assertTrue(path.endsWith("pairing.preferences_pb"))
    }
}
