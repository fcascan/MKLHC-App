package com.mklhc.app.desktop

import co.touchlab.kermit.Logger
import com.mklhc.app.LogCapture
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DesktopRadioSettings.parse] from injected environments, system properties and arguments: the defaults on each
 * operating system (from any host), the overrides, the retired `MKLHC_RADIO` (reported, then ignored), and values that
 * cannot be used failing with the variable's name.
 */
class DesktopRadioSettingsTest {

    // ---- Defaults ------------------------------------------------------------------------------------

    @Test
    fun onWindowsTheDefaultsAreTheRadiodReleaseBuildAndAppDataMklhc() {
        val settings = parse(env = mapOf("APPDATA" to APPDATA), os = WINDOWS)

        assertFalse(settings.demo)
        assertEquals(Path.of(APPDATA, "MKLHC"), settings.dataDirectory)
        assertEquals(Path.of(APPDATA, "MKLHC", "pairing.preferences_pb"), settings.pairingStorePath)
        // No build exists: the path names where the build is expected, so the runtime can report it.
        assertEquals(workingDirectory().resolve("radiod/build/Release/radiod.exe").normalize(), settings.radiodExecutable)
    }

    @Test
    fun onMacOsTheDataDirectoryIsUnderApplicationSupport() {
        val settings = parse(os = "Mac OS X")

        assertEquals(Path.of(HOME, "Library/Application Support", "MKLHC"), settings.dataDirectory)
    }

    @Test
    fun elsewhereTheDataDirectoryIsUnderXdgConfigHomeOrDotConfigAndRadiodHasNoExeSuffix() {
        val xdg = absolute("xdg")
        assertEquals(xdg.resolve("MKLHC"), parse(env = mapOf("XDG_CONFIG_HOME" to xdg.toString()), os = LINUX).dataDirectory)

        val settings = parse(os = LINUX)

        assertEquals(Path.of(HOME, ".config", "MKLHC"), settings.dataDirectory)
        assertEquals(workingDirectory().resolve("radiod/build/radiod").normalize(), settings.radiodExecutable)
    }

    @Test
    fun aDefaultDataDirectoryThatComesOutRelativeIsTakenAgainstTheWorkingDirectory() {
        val windows = parse(env = mapOf("APPDATA" to "roaming"), os = WINDOWS)
        val linux = parse(env = mapOf("XDG_CONFIG_HOME" to "config"), os = LINUX)

        assertEquals(workingDirectory().resolve("roaming").resolve("MKLHC"), windows.dataDirectory)
        assertEquals(workingDirectory().resolve("config").resolve("MKLHC"), linux.dataDirectory)
        assertTrue(windows.dataDirectory.isAbsolute && linux.dataDirectory.isAbsolute)
    }

    @Test
    fun theFirstRadiodBuildThatExistsInTheWorkingDirectoryIsUsed() {
        val ninjaBuild = workingDirectory().resolve("radiod/build/radiod.exe")
        val releaseBuild = workingDirectory().resolve("radiod/build/Release/radiod.exe")

        assertEquals(ninjaBuild.normalize(), parse(os = WINDOWS, existing = setOf(ninjaBuild)).radiodExecutable)
        assertEquals(
            releaseBuild.normalize(),
            parse(os = WINDOWS, existing = setOf(ninjaBuild, releaseBuild)).radiodExecutable,
            "the Release build comes first, as for the spike",
        )
    }

    @Test
    fun theSharedRadiodLookupReturnsNullWhenNoBuildExistsAndKeepsARelativeBaseRelative() {
        assertNull(findRadiodExecutable(Path.of(""), isRegularFile = { false }))

        val found = findRadiodExecutable(Path.of(""), isRegularFile = { it.endsWith("radiod") })

        assertEquals(Path.of("radiod/build/radiod"), found, "the spike passes the working directory as a relative path")
    }

    // ---- Overrides -----------------------------------------------------------------------------------

    @Test
    fun theVariablesOverrideRadiodAndTheDataDirectory() {
        val radiod = absolute("tools", "radiod.exe")
        val data = absolute("data", "mklhc")
        val settings = parse(
            env = mapOf("MKLHC_RADIOD" to radiod.toString(), "MKLHC_DATA_DIR" to data.toString()),
            os = WINDOWS,
        )

        assertEquals(radiod, settings.radiodExecutable)
        assertEquals(data, settings.dataDirectory)
        assertEquals(data.resolve("pairing.preferences_pb"), settings.pairingStorePath)
    }

    @Test
    fun relativePathsAreTakenAgainstTheWorkingDirectory() {
        val settings = parse(env = mapOf("MKLHC_RADIOD" to "bin/radiod", "MKLHC_DATA_DIR" to "state"), os = LINUX)

        assertEquals(workingDirectory().resolve("bin/radiod").normalize(), settings.radiodExecutable)
        assertEquals(workingDirectory().resolve("state").normalize(), settings.dataDirectory)
        assertTrue(settings.radiodExecutable.isAbsolute && settings.dataDirectory.isAbsolute)
    }

    @Test
    fun onePairOfDoubleQuotesAroundAPathIsDropped() {
        // cmd's `set MKLHC_RADIOD="C:\Program Files\radiod.exe"` keeps the quotes in the value.
        val radiod = absolute("Program Files", "radiod.exe")
        val data = absolute("data dir")
        val settings = parse(
            env = mapOf("MKLHC_RADIOD" to "\"$radiod\"", "MKLHC_DATA_DIR" to " \"state\" "),
            os = WINDOWS,
        )

        assertEquals(radiod, settings.radiodExecutable)
        assertEquals(workingDirectory().resolve("state"), settings.dataDirectory, "spaces around the quotes go too")
        assertEquals(data, parse(env = mapOf("MKLHC_DATA_DIR" to "\"$data\"")).dataDirectory)
    }

    @Test
    fun aPairOfQuotesAroundNothingCountsAsUnset() {
        val settings = parse(env = mapOf("MKLHC_RADIOD" to "\"\"", "MKLHC_DATA_DIR" to "\" \"", "APPDATA" to APPDATA), os = WINDOWS)

        assertEquals(parse(env = mapOf("APPDATA" to APPDATA), os = WINDOWS), settings)
    }

    @Test
    fun blankVariablesCountAsUnset() {
        val settings = parse(
            env = mapOf("MKLHC_RADIO" to " ", "MKLHC_RADIOD" to "", "MKLHC_DATA_DIR" to "  ", "MKLHC_DEMO" to "", "APPDATA" to APPDATA),
            os = WINDOWS,
        )

        assertEquals(parse(env = mapOf("APPDATA" to APPDATA), os = WINDOWS), settings)
    }

    @Test
    fun theVideoDumpIsOffByDefaultAndTheVariableNamesItsFolder() {
        val dumps = absolute("captures", "video")

        assertNull(parse().videoDumpDirectory)
        assertNull(parse(env = mapOf("MKLHC_VIDEO_DUMP" to "  ")).videoDumpDirectory, "blank counts as unset")
        assertEquals(dumps, parse(env = mapOf("MKLHC_VIDEO_DUMP" to "\"$dumps\"")).videoDumpDirectory)
        assertEquals(workingDirectory().resolve("video"), parse(env = mapOf("MKLHC_VIDEO_DUMP" to "video")).videoDumpDirectory)
    }

    @Test
    fun theTelemetryDumpIsOffByDefaultAndItsOwnVariableNamesItsFolder() {
        val dumps = absolute("captures", "telemetry")

        assertNull(parse().telemetryDumpDirectory)
        assertNull(parse(env = mapOf("MKLHC_TELEMETRY_DUMP" to "  ")).telemetryDumpDirectory, "blank counts as unset")
        assertEquals(dumps, parse(env = mapOf("MKLHC_TELEMETRY_DUMP" to "\"$dumps\"")).telemetryDumpDirectory)
        assertEquals(
            workingDirectory().resolve("telemetry"),
            parse(env = mapOf("MKLHC_TELEMETRY_DUMP" to "telemetry")).telemetryDumpDirectory,
        )
        assertNull(parse(env = mapOf("MKLHC_VIDEO_DUMP" to "video")).telemetryDumpDirectory, "the video dump alone dumps no telemetry")
        assertNull(parse(env = mapOf("MKLHC_TELEMETRY_DUMP" to "telemetry")).videoDumpDirectory, "and the other way round")
    }

    @Test
    fun theDemoIsChosenByTheVariableOrTheArgument() {
        assertTrue(parse(env = mapOf("MKLHC_DEMO" to "1")).demo)
        assertTrue(parse(env = mapOf("MKLHC_DEMO" to "TRUE")).demo)
        assertTrue(parse(args = listOf("--demo")).demo)
        assertTrue(parse(env = mapOf("MKLHC_DEMO" to "0"), args = listOf("--demo")).demo, "the argument alone is enough")
        assertFalse(parse(env = mapOf("MKLHC_DEMO" to "0")).demo)
        assertFalse(parse(env = mapOf("MKLHC_DEMO" to "false")).demo)
    }

    // ---- The retired adapter variable ----------------------------------------------------------------

    @Test
    fun theRetiredRadioVariableIsReportedAndIgnoredWhateverItsValue() {
        listOf("2357:012D", "0bda-c812", "radio").forEach { value ->
            val logs = LogCapture()

            val settings = parse(env = mapOf("MKLHC_RADIO" to value, "APPDATA" to APPDATA), os = WINDOWS, log = logs.logger())

            assertEquals(parse(env = mapOf("APPDATA" to APPDATA), os = WINDOWS), settings, "the value changes nothing -> value=$value")
            assertTrue(
                logs.has("WARN: parse(): MKLHC_RADIO is no longer used; choose the adapter on the Kart link screen -> value=$value"),
                logs.text(),
            )
        }
    }

    @Test
    fun anUnsetOrBlankRadioVariableIsNotReported() {
        val logs = LogCapture()

        parse(log = logs.logger())
        parse(env = mapOf("MKLHC_RADIO" to "  "), log = logs.logger())

        assertFalse(logs.has("MKLHC_RADIO"), logs.text())
    }

    // ---- Values that cannot be used ------------------------------------------------------------------

    @Test
    fun aDemoFlagThatIsNeitherOneNorZeroFailsNamingTheVariable() {
        val failure = assertFailsWith<IllegalArgumentException> { parse(env = mapOf("MKLHC_DEMO" to "yes please")) }

        assertTrue(failure.message.orEmpty().contains("MKLHC_DEMO"), failure.message)
    }

    @Test
    fun aPathTheFileSystemRejectsFailsNamingTheVariable() {
        // A NUL character is refused by the file system on every platform.
        listOf("MKLHC_RADIOD", "MKLHC_DATA_DIR", "MKLHC_VIDEO_DUMP", "MKLHC_TELEMETRY_DUMP").forEach { variable ->
            val failure = assertFailsWith<IllegalArgumentException>(variable) { parse(env = mapOf(variable to "bad\u0000path")) }
            assertTrue(failure.message.orEmpty().startsWith(variable), failure.message)
        }
    }

    @Test
    fun anUnknownArgumentFailsNamingIt() {
        val failure = assertFailsWith<IllegalArgumentException> { parse(args = listOf("--dmeo")) }

        assertTrue(failure.message.orEmpty().contains("--dmeo"), failure.message)
        assertTrue(failure.message.orEmpty().contains("--demo"), "the message names the one argument there is: ${failure.message}")
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    /** The working directory the tests give as `user.dir`: an absolute path on any host. */
    private fun workingDirectory(): Path = absolute("work", "repo")

    private fun parse(
        env: Map<String, String> = emptyMap(),
        os: String = LINUX,
        args: List<String> = emptyList(),
        existing: Set<Path> = emptySet(),
        log: Logger = LogCapture().logger(),
    ): DesktopRadioSettings = DesktopRadioSettings.parse(
        env = env,
        systemProperties = mapOf("os.name" to os, "user.home" to HOME, "user.dir" to workingDirectory().toString()),
        args = args,
        isRegularFile = { it in existing },
        log = log,
    )

    private companion object {
        const val WINDOWS = "Windows 11"
        const val LINUX = "Linux"

        /** An absolute path on any host: under the root of the working directory's file system. */
        fun absolute(vararg parts: String): Path = Path.of("").toAbsolutePath().root.resolve(Path.of("mklhc-test", *parts))

        // Absolute on the host, as the operating system gives them, so the defaults built on them are absolute too.
        val HOME: String = absolute("home", "kim").toString()
        val APPDATA: String = absolute("users", "kim", "AppData", "Roaming").toString()
    }
}
