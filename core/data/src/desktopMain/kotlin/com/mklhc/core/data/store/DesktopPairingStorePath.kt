package com.mklhc.core.data.store

import java.io.File

/** Name of the application folder that holds the desktop build's files inside the user's config directory. */
private const val APP_FOLDER_NAME = "MKLHC"

/**
 * The per-user configuration directory of the desktop build's operating system.
 *
 * Business logic: the pairing file is the installation's identity and must outlive the app, so it
 * lives in the platform's conventional per-user config location rather than a temp or working
 * directory: `%APPDATA%` on Windows, `~/Library/Application Support` on macOS, and
 * `$XDG_CONFIG_HOME` (or `~/.config`) elsewhere. The inputs are parameters so the choice can be
 * tested for every platform from any host.
 *
 * @param osName the `os.name` system property.
 * @param userHome the user's home directory.
 * @param env reads an environment variable, null when unset.
 */
fun desktopConfigDirectory(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHome: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = { System.getenv(it) },
): String {
    val os = osName.lowercase()
    return when {
        os.startsWith("windows") -> env("APPDATA")?.takeIf { it.isNotBlank() } ?: File(userHome, "AppData/Roaming").path
        os.contains("mac") -> File(userHome, "Library/Application Support").path
        else -> env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(userHome, ".config").path
    }
}

/**
 * The path of the desktop build's pairing file: [PAIRING_STORE_FILE_NAME] inside an application
 * folder under [configDirectory]. Pass it to [createPairingDataStore].
 *
 * @param configDirectory the per-user config directory, [desktopConfigDirectory] by default.
 */
fun desktopPairingStorePath(configDirectory: String = desktopConfigDirectory()): String =
    File(File(configDirectory, APP_FOLDER_NAME), PAIRING_STORE_FILE_NAME).path
