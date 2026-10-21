package com.mklhc.app.desktop

import com.mklhc.app.LogCapture
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DesktopDataDirectory] and [createOwnerOnlyDirectory]: one instance at a time holds a data directory, whether the
 * other one runs in this process or in another, the lock comes back once released, and the directory is created
 * readable by its owner only where the file system has POSIX permissions (those tests are skipped on Windows).
 */
class DesktopDataDirectoryTest {

    private val tempDir: Path = Files.createTempDirectory("mklhc-data-dir-test")
    private val dataDirectory: Path = tempDir.resolve("config").resolve("MKLHC")
    private val locks = mutableListOf<DesktopDataDirectory>()

    @AfterTest
    fun tearDown() {
        locks.forEach { it.release() }
        tempDir.toFile().deleteRecursively()
    }

    // ---- One instance per data directory ---------------------------------------------------------------

    @Test
    fun aSecondInstanceOnTheSameDataDirectoryIsRefusedUntilTheFirstReleasesIt() {
        val logs = LogCapture()
        val first = assertNotNull(lock(), "the first instance locks the directory")

        assertNull(DesktopDataDirectory.lock(dataDirectory, logs.logger()), "a second instance is refused")
        assertTrue(logs.has("lock(): the data directory is already locked"), logs.text())

        first.release()
        first.release() // idempotent: a second release does nothing and does not throw
        assertNotNull(lock(), "the directory can be locked again once released")
    }

    @Test
    fun aDataDirectoryLockedByAnotherProcessIsRefused() {
        Files.createDirectories(dataDirectory)
        val holder = startLockHolder(dataDirectory.resolve(DesktopDataDirectory.LOCK_FILE_NAME))
        try {
            assertNull(DesktopDataDirectory.lock(dataDirectory), "the other process holds the lock")
        } finally {
            // The holder exits when its input ends, and the operating system drops its lock with it.
            holder.outputStream.close()
            if (!holder.waitFor(PROCESS_TIMEOUT_S, TimeUnit.SECONDS)) holder.destroyForcibly()
        }

        // Windows drops the locks of an ended process soon after it ends, not necessarily at once.
        val relocked = (1..RELOCK_ATTEMPTS).firstNotNullOfOrNull { lock().also { if (it == null) Thread.sleep(RELOCK_PAUSE_MS) } }
        assertNotNull(relocked, "the lock is free once the other process ended")
    }

    @Test
    fun lockingCreatesTheDataDirectoryAndItsParents() {
        assertNotNull(lock())

        assertTrue(Files.isDirectory(dataDirectory))
        assertTrue(Files.isRegularFile(dataDirectory.resolve(DesktopDataDirectory.LOCK_FILE_NAME)))
    }

    // ---- Owner-only on POSIX ---------------------------------------------------------------------------

    @Test
    fun onPosixTheDataDirectoryIsCreatedReadableByItsOwnerOnly() {
        assumePosix()

        assertNotNull(lock())

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dataDirectory)))
    }

    @Test
    fun onPosixAnExistingDirectoryOpenToOthersIsLeftAsItIsWithAWarning() {
        assumePosix()
        Files.createDirectories(dataDirectory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))
        Files.setPosixFilePermissions(dataDirectory, PosixFilePermissions.fromString("rwxr-xr-x"))
        val logs = LogCapture()

        createOwnerOnlyDirectory(dataDirectory, logs.logger())

        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dataDirectory)))
        assertTrue(logs.has("WARN: warnWhenOthersCanReach(): other users can reach the data directory"), logs.text())
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private fun lock(): DesktopDataDirectory? = DesktopDataDirectory.lock(dataDirectory)?.also { locks += it }

    private fun assumePosix() =
        assumeTrue("posix" in tempDir.fileSystem.supportedFileAttributeViews(), "the file system has no POSIX permissions")

    /**
     * Starts another JVM that locks [lockFile] the way the app does and holds it until its input ends, and returns once
     * it holds it. The program is a Java source file run in source-file mode, so it needs no classpath.
     */
    private fun startLockHolder(lockFile: Path): Process {
        val source = tempDir.resolve("HoldLock.java")
        Files.writeString(source, LOCK_HOLDER_SOURCE)
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(java, source.toString(), lockFile.toString()).redirectErrorStream(true).start()
        val firstLine = try {
            CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }.get(PROCESS_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            process.destroyForcibly()
            throw AssertionError("the lock holder said nothing within $PROCESS_TIMEOUT_S s", timeout)
        }
        if (firstLine != LOCKED_LINE) {
            process.destroyForcibly()
            throw AssertionError("the lock holder did not take the lock -> output=$firstLine")
        }
        return process
    }

    private companion object {
        const val PROCESS_TIMEOUT_S = 30L
        const val RELOCK_ATTEMPTS = 50
        const val RELOCK_PAUSE_MS = 100L
        const val LOCKED_LINE = "locked"

        /** Locks the file named by its argument, says so, and holds the lock until its input ends. */
        val LOCK_HOLDER_SOURCE = """
            import java.nio.channels.FileChannel;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            public class HoldLock {
                public static void main(String[] args) throws Exception {
                    try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        channel.lock();
                        System.out.println("$LOCKED_LINE");
                        System.out.flush();
                        while (System.in.read() != -1) { }
                    }
                }
            }
        """.trimIndent()
    }
}
