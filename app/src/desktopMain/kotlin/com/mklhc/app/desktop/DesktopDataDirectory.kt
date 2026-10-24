package com.mklhc.app.desktop

import co.touchlab.kermit.Logger
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The desktop app's data directory: created readable by its owner only, and held by one app instance at a time.
 *
 * Original code.
 */

/**
 * The data directory, locked by this app instance for as long as it runs.
 *
 * Business logic: two instances on the same data directory would fight over the USB adapter (each launches its own
 * radiod for it), and on a first run each would create an identity and write it to the same pairing file, so the kart
 * could end up paired with an identity the surviving file no longer holds. [lock] therefore takes an exclusive lock on
 * [LOCK_FILE_NAME] in the directory before the runtime is built, and a second instance gets null and refuses to start.
 * The lock is held through an open file channel, which the operating system releases when the process ends, however it
 * ends: a crash leaves the empty lock file behind but never a stale lock, and the next start reuses the file.
 *
 * Within one process the lock is also tracked by path, so a second [lock] of the same directory is refused without
 * opening the file again: on POSIX systems closing any channel of a file drops every lock the process holds on it.
 *
 * @property path the data directory.
 */
class DesktopDataDirectory private constructor(
    val path: Path,
    private val lockFile: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val log: Logger,
) {
    private val released = AtomicBoolean(false)

    /** Releases the lock so another instance can start. Idempotent; never throws. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            lock.release()
            channel.close()
            log.i { "release(): data directory released -> path=$path" }
        } catch (failure: IOException) {
            log.w { "release(): releasing the data directory failed, the process exit releases it -> path=$path message=${failure.message}" }
        } finally {
            LOCKED_IN_THIS_PROCESS.remove(lockFile)
        }
    }

    companion object {

        /**
         * Creates the data directory at [path] when it is missing (owner-only, see [createOwnerOnlyDirectory]) and locks
         * it for this instance.
         *
         * @param path the data directory.
         * @param log where the outcome is logged.
         * @return the locked directory, or null when another instance, in this process or another one, holds it.
         * @throws IOException when the directory or its lock file cannot be created or opened.
         */
        fun lock(path: Path, log: Logger = Logger.withTag(TAG)): DesktopDataDirectory? {
            createOwnerOnlyDirectory(path, log)
            val lockFile = path.resolve(LOCK_FILE_NAME).toAbsolutePath().normalize()
            if (!LOCKED_IN_THIS_PROCESS.add(lockFile)) {
                log.w { "lock(): the data directory is already locked by this process -> lockFile=$lockFile" }
                return null
            }
            var locked: DesktopDataDirectory? = null
            try {
                val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                // tryLock answers null when another process holds the lock; the exception is this JVM holding it under
                // another spelling of the path (a link, say), which the tracking above cannot see.
                val lock = try {
                    channel.tryLock()
                } catch (heldHere: OverlappingFileLockException) {
                    null
                } catch (failure: IOException) {
                    channel.close()
                    throw failure
                }
                if (lock == null) {
                    channel.close()
                    log.w { "lock(): the data directory is locked by another instance -> lockFile=$lockFile" }
                    return null
                }
                locked = DesktopDataDirectory(path, lockFile, channel, lock, log)
                log.i { "lock(): data directory locked for this instance -> path=$path" }
                return locked
            } finally {
                // Every way out but a successful lock gives the path back.
                if (locked == null) LOCKED_IN_THIS_PROCESS.remove(lockFile)
            }
        }

        /** The file in the data directory the instance lock is taken on. */
        const val LOCK_FILE_NAME = "instance.lock"

        private const val TAG = "DesktopDataDirectory"

        /** The lock files this process holds, by absolute path. */
        private val LOCKED_IN_THIS_PROCESS: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    }
}

/**
 * Creates [directory] when it is missing: its missing parents with the default permissions, and the directory itself
 * readable by its owner only (`rwx------`) on a file system with POSIX permissions.
 *
 * Business logic: the pairing file in the data directory holds the master secret in the clear, and on macOS and Linux a
 * new directory is readable by everyone by default (umask 022), so the directory is created owner-only before the
 * pairing DataStore first writes into it. On Windows, which has no POSIX permissions, it is created as is: the default,
 * under `%APPDATA%`, is already private to the user. An existing directory is left as it is (it may be one the user
 * chose and shares), with a warning when others can reach it. Best effort: when the permissions cannot be applied the
 * directory is created without them and a warning is logged.
 *
 * @param directory the data directory.
 * @param log where a problem is logged.
 * @param firstLook the first check of whether [directory] exists; tests inject one that misses an existing directory.
 * @throws IOException when the directory cannot be created at all.
 */
internal fun createOwnerOnlyDirectory(directory: Path, log: Logger, firstLook: (Path) -> Boolean = { Files.isDirectory(it) }) {
    val posix = POSIX_ATTRIBUTE_VIEW in directory.fileSystem.supportedFileAttributeViews()
    if (firstLook(directory)) {
        if (posix) warnWhenOthersCanReach(directory, log)
        return
    }
    if (!posix) {
        directory.parent?.let { Files.createDirectories(it) }
        try {
            Files.createDirectory(directory)
        } catch (alreadyThere: FileAlreadyExistsException) {
            acceptExistingDirectoryOrRethrow(directory, alreadyThere, log)
            return
        }
        log.i { "createOwnerOnlyDirectory(): data directory created -> path=$directory" }
        return
    }
    val failure: Exception? = try {
        directory.parent?.let { Files.createDirectories(it) }
        // The umask can only clear bits, so the result is never wider than owner-only.
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        null
    } catch (alreadyThere: FileAlreadyExistsException) {
        acceptExistingDirectoryOrRethrow(directory, alreadyThere, log)
        warnWhenOthersCanReach(directory, log)
        return
    } catch (failure: IOException) {
        failure
    } catch (failure: UnsupportedOperationException) {
        failure
    }
    if (failure == null) {
        log.i { "createOwnerOnlyDirectory(): data directory created, readable by its owner only -> path=$directory" }
        return
    }
    log.w { "createOwnerOnlyDirectory(): the data directory could not be created owner-only, creating it as is -> path=$directory message=${failure.message}" }
    Files.createDirectories(directory)
}

/**
 * Accepts a [directory] that turned out to exist when it was about to be created, logging that nothing was created.
 *
 * Business logic: the first check can miss an existing directory (on Windows `Files.isDirectory` was seen to answer
 * false for one, probably while the antivirus held it), and the log must not then claim a creation: a "created" line
 * means the pairing identity is new, and a false one would hide a real loss of it. A file, not a directory, at that
 * path is still an error, as `Files.createDirectories` treats it.
 *
 * @throws FileAlreadyExistsException [alreadyThere] again when what exists at [directory] is not a directory.
 */
private fun acceptExistingDirectoryOrRethrow(directory: Path, alreadyThere: FileAlreadyExistsException, log: Logger) {
    if (!Files.isDirectory(directory)) throw alreadyThere
    log.w { "acceptExistingDirectoryOrRethrow(): the data directory was already there although the first check missed it, nothing created -> path=$directory" }
}

/** Logs a warning when the group or others have any access to [directory]; best effort. */
private fun warnWhenOthersCanReach(directory: Path, log: Logger) {
    try {
        val permissions = Files.getPosixFilePermissions(directory)
        if ((permissions - OWNER_ONLY).isNotEmpty()) {
            log.w {
                "warnWhenOthersCanReach(): other users can reach the data directory that holds the pairing secret, " +
                    "make it owner-only (chmod 700) -> path=$directory permissions=${PosixFilePermissions.toString(permissions)}"
            }
        }
    } catch (failure: IOException) {
        log.w { "warnWhenOthersCanReach(): the data directory's permissions could not be read -> path=$directory message=${failure.message}" }
    }
}

private const val POSIX_ATTRIBUTE_VIEW = "posix"

/** `rwx------`: the owner reads, writes and enters the directory; nobody else does. */
private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
