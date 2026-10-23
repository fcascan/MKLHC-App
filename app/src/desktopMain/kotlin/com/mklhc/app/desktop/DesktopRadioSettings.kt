package com.mklhc.app.desktop

import co.touchlab.kermit.Logger
import com.mklhc.core.data.store.PAIRING_STORE_FILE_NAME
import com.mklhc.core.data.store.desktopConfigDirectory
import com.mklhc.core.data.store.desktopPairingStorePath
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/*
 * How the desktop app is configured: demo or radio, the radiod executable and the data directory.
 *
 * Original code. The radiod candidate list and its lookup moved here from the radio hardware spike
 * (app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikeSession.kt), which now uses them from here; the default data
 * directory is the one :core:data's desktopPairingStorePath already puts the pairing file in.
 */

/**
 * How the desktop app runs: the scripted demo, or the real session over the USB radio with the radiod executable and
 * the data directory it uses.
 *
 * Business logic: the app is configured from the environment, so `:app:run` needs no arguments and a packaged app needs
 * no config file. Every value has a default that suits the reference setup (the paths are made absolute against the
 * working directory). The USB adapter is not configured here: the user chooses it on the Kart link screen (automatic by
 * default), and the choice is kept with the pairing data. `MKLHC_RADIO`, which used to name it, is retired: when it is
 * set, a warning says so at start-up and the value is ignored, whatever it is.
 *
 * - `MKLHC_RADIOD`: the radiod executable; by default the first radiod build found among [RADIOD_CANDIDATES] in the
 *   working directory (`:app:run` runs from the repository root, as the spike does). When none exists the path is still
 *   set (to where the build is expected), so the runtime can name it when it reports radiod missing.
 * - `MKLHC_DATA_DIR`: where the pairing file (the installation's identity) is kept; by default the per-user config
 *   location of the operating system with an `MKLHC` folder: `%APPDATA%\MKLHC` on Windows,
 *   `~/Library/Application Support/MKLHC` on macOS, `$XDG_CONFIG_HOME/MKLHC` or `~/.config/MKLHC` elsewhere.
 * - `MKLHC_DEMO=1`, or the `--demo` program argument: the scripted demo, with no radio and no kart.
 * - `MKLHC_VIDEO_DUMP`: a folder to dump the kart's raw video datagrams into, one file per run (see
 *   [VideoDatagramDump]); unset by default, which dumps nothing. A diagnostic for studying the video stream.
 *
 * A variable set to a blank value counts as unset. A path in double quotes is taken without them, since cmd's
 * `set MKLHC_RADIOD="C:\Program Files\radiod.exe"` keeps the quotes in the value. A value that cannot be used (a path
 * the file system rejects, a demo flag that is neither 1 nor 0) and an unknown program argument fail at start-up with a
 * message naming the variable or the argument, instead of being ignored: a typo must not silently run the radio, or the
 * demo. A radiod path that does not exist is not a parse error; the runtime reports it when it starts.
 *
 * @property demo true for the scripted demo session.
 * @property radiodExecutable the absolute path of the radiod executable (it may not exist).
 * @property dataDirectory the absolute directory the pairing file lives in.
 * @property videoDumpDirectory the absolute folder the raw video datagrams are dumped into, or null for no dump.
 */
data class DesktopRadioSettings(
    val demo: Boolean,
    val radiodExecutable: Path,
    val dataDirectory: Path,
    val videoDumpDirectory: Path? = null,
) {
    /** The pairing file inside [dataDirectory]. */
    val pairingStorePath: Path get() = dataDirectory.resolve(PAIRING_STORE_FILE_NAME)

    companion object {

        /**
         * Reads the settings of this process: its environment, its system properties and [args].
         *
         * @param args the program arguments.
         * @param log where a retired variable is reported.
         * @throws IllegalArgumentException when a value cannot be used; the message names the variable or argument.
         */
        fun fromSystem(args: List<String>, log: Logger = Logger.withTag(TAG)): DesktopRadioSettings = parse(
            env = System.getenv(),
            systemProperties = SYSTEM_PROPERTIES_READ.associateWith { System.getProperty(it).orEmpty() },
            args = args,
            log = log,
        )

        /**
         * Builds the settings from the given environment, system properties and program arguments; nothing else is
         * read except whether the radiod candidates exist, through [isRegularFile].
         *
         * Business logic: see the class. Relative paths in `MKLHC_RADIOD` and `MKLHC_DATA_DIR` are taken against the
         * working directory (`user.dir`), and the radiod candidates are looked for there too. So is a default data
         * directory that comes out relative (from a relative `APPDATA`, `XDG_CONFIG_HOME` or `user.home`), so the data
         * directory is always absolute.
         *
         * @param env the environment variables (on Windows, `System.getenv()` looks names up case-insensitively).
         * @param systemProperties at least `os.name`, `user.home` and `user.dir`.
         * @param args the program arguments; `--demo` is the only one.
         * @param isRegularFile whether a radiod candidate exists; replaceable so a test can decide.
         * @param log where a retired variable is reported; injectable so a test can assert it.
         * @throws IllegalArgumentException when a value cannot be used; the message names the variable or argument.
         */
        fun parse(
            env: Map<String, String>,
            systemProperties: Map<String, String>,
            args: List<String>,
            isRegularFile: (Path) -> Boolean = { Files.isRegularFile(it) },
            log: Logger = Logger.withTag(TAG),
        ): DesktopRadioSettings {
            val osName = systemProperties[PROPERTY_OS_NAME].orEmpty()
            val workingDirectory = pathOf(systemProperties[PROPERTY_USER_DIR].orEmpty(), PROPERTY_USER_DIR).toAbsolutePath()
            val demo = demoFromArguments(args) or demoFromEnvironment(env.valueOf(ENV_DEMO))
            env.valueOf(ENV_RADIO)?.let { retired ->
                log.w { "parse(): $ENV_RADIO is no longer used; choose the adapter on the Kart link screen -> value=$retired" }
            }
            val radiod = env.pathValueOf(ENV_RADIOD)?.let { workingDirectory.resolve(pathOf(it, ENV_RADIOD)) }
                ?: findRadiodExecutable(workingDirectory, isRegularFile)
                ?: workingDirectory.resolve(expectedRadiodBuild(osName))
            val dataDirectory = workingDirectory.resolve(
                env.pathValueOf(ENV_DATA_DIR)?.let { pathOf(it, ENV_DATA_DIR) }
                    ?: defaultDataDirectory(osName, systemProperties[PROPERTY_USER_HOME].orEmpty(), env),
            )
            val videoDump = env.pathValueOf(ENV_VIDEO_DUMP)?.let { workingDirectory.resolve(pathOf(it, ENV_VIDEO_DUMP)) }
            return DesktopRadioSettings(
                demo = demo,
                radiodExecutable = radiod.normalize(),
                dataDirectory = dataDirectory.normalize(),
                videoDumpDirectory = videoDump?.normalize(),
            )
        }

        /** The value of [name], or null when it is unset or blank. */
        private fun Map<String, String>.valueOf(name: String): String? = this[name]?.takeIf { it.isNotBlank() }?.trim()

        /**
         * The path in [name], or null when it is unset or blank: one pair of double quotes around the whole value is
         * dropped first (cmd's `set NAME="..."` keeps them), and a pair around nothing counts as unset.
         */
        private fun Map<String, String>.pathValueOf(name: String): String? {
            val value = valueOf(name) ?: return null
            val quoted = value.length >= QUOTED_MIN_LENGTH && value.first() == QUOTE && value.last() == QUOTE
            return (if (quoted) value.substring(1, value.length - 1) else value).takeIf { it.isNotBlank() }
        }

        /** Whether the arguments ask for the demo; any argument other than `--demo` is refused. */
        private fun demoFromArguments(args: List<String>): Boolean {
            val unknown = args.filter { it != ARG_DEMO }
            require(unknown.isEmpty()) { "unknown program argument; the only one is $ARG_DEMO -> arguments=$unknown" }
            return args.isNotEmpty()
        }

        /** `MKLHC_DEMO`: 1 or true for the demo, 0 or false (or unset) for the radio. */
        private fun demoFromEnvironment(value: String?): Boolean = when (value?.lowercase()) {
            null, "0", "false" -> false
            "1", "true" -> true
            else -> throw IllegalArgumentException("$ENV_DEMO must be 1 (demo) or 0 (radio) -> value=$value")
        }

        /** [value] as a path, or a failure naming [source] when the file system rejects it. */
        private fun pathOf(value: String, source: String): Path = try {
            Path.of(value)
        } catch (invalid: InvalidPathException) {
            throw IllegalArgumentException("$source is not a valid path -> value=$value reason=${invalid.reason}", invalid)
        }

        /** Where the radiod build is expected when none is found: the first candidate built for this operating system. */
        private fun expectedRadiodBuild(osName: String): String =
            if (osName.lowercase().startsWith(WINDOWS_PREFIX)) {
                RADIOD_CANDIDATES.first()
            } else {
                RADIOD_CANDIDATES.first { !it.endsWith(WINDOWS_EXECUTABLE_SUFFIX) }
            }

        /** The folder :core:data puts the desktop pairing file in, under the per-user config location. */
        private fun defaultDataDirectory(osName: String, userHome: String, env: Map<String, String>): Path {
            val pairingFile = desktopPairingStorePath(desktopConfigDirectory(osName = osName, userHome = userHome, env = { env[it] }))
            return checkNotNull(Path.of(pairingFile).parent) { "the pairing file has no folder -> path=$pairingFile" }
        }

        /**
         * The retired environment variable that used to name the adapter's USB id; when set it is only reported, since
         * the adapter is chosen on the Kart link screen.
         */
        const val ENV_RADIO = "MKLHC_RADIO"

        /** The environment variable with the radiod executable. */
        const val ENV_RADIOD = "MKLHC_RADIOD"

        /** The environment variable with the data directory. */
        const val ENV_DATA_DIR = "MKLHC_DATA_DIR"

        /** The environment variable that selects the demo. */
        const val ENV_DEMO = "MKLHC_DEMO"

        /** The environment variable with the folder the raw video datagrams are dumped into. */
        const val ENV_VIDEO_DUMP = "MKLHC_VIDEO_DUMP"

        /** The program argument that selects the demo. */
        const val ARG_DEMO = "--demo"

        private const val PROPERTY_OS_NAME = "os.name"
        private const val PROPERTY_USER_HOME = "user.home"
        private const val PROPERTY_USER_DIR = "user.dir"
        private const val WINDOWS_PREFIX = "windows"
        private const val TAG = "DesktopRadioSettings"
        private const val WINDOWS_EXECUTABLE_SUFFIX = ".exe"
        private const val QUOTE = '"'
        private const val QUOTED_MIN_LENGTH = 2
        private val SYSTEM_PROPERTIES_READ = listOf(PROPERTY_OS_NAME, PROPERTY_USER_HOME, PROPERTY_USER_DIR)
    }
}

/**
 * The first radiod build that exists among [RADIOD_CANDIDATES], resolved against [baseDirectory], or null when none does.
 *
 * Business logic: radiod is built in place under `radiod/build` (see docs/HARDWARE_SETUP.md), in a folder that depends on
 * the generator (multi-config Visual Studio puts it under `Release/`, Ninja and Makefiles do not). Both the spike and the
 * desktop app look there, from the repository root, when no path is given.
 *
 * @param baseDirectory the directory the candidates are relative to; the spike passes the working directory as a
 *   relative path, so it gets a relative result back.
 * @param isRegularFile whether a candidate exists.
 */
internal fun findRadiodExecutable(baseDirectory: Path, isRegularFile: (Path) -> Boolean = { Files.isRegularFile(it) }): Path? =
    RADIOD_CANDIDATES.map { baseDirectory.resolve(it) }.firstOrNull(isRegularFile)

/** Where a radiod build is looked for, relative to the repository root, in order of preference. */
internal val RADIOD_CANDIDATES = listOf(
    "radiod/build/Release/radiod.exe",
    "radiod/build/radiod.exe",
    "radiod/build/RelWithDebInfo/radiod.exe",
    "radiod/build/radiod",
)
