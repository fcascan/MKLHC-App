package com.mklhc.core.data

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig

/**
 * Reads a UTF-8 text resource from the test classpath (for example `identity_vectors.json`).
 *
 * commonTest has no file or classpath API, so the JVM test source set supplies the `actual` (see
 * jvmSharedTest). The identity golden vectors are a plain JSON file under `src/commonTest/resources`.
 */
internal expect fun readTestResource(name: String): String

/** Collects every Kermit log call so a test can assert what the code under test logged. */
internal class CapturingLogWriter : LogWriter() {
    class Entry(val severity: Severity, val message: String)

    val entries = mutableListOf<Entry>()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        entries += Entry(severity, message + (throwable?.let { " | ${it::class.simpleName}: ${it.message}" } ?: ""))
    }

    /** True if something was logged at exactly [severity]. */
    fun logged(severity: Severity): Boolean = entries.any { it.severity == severity }

    /** Everything logged so far as one string, for "must not contain" assertions. */
    fun allText(): String = entries.joinToString("\n") { it.message }
}

/** A logger that records into [writer] at every severity, for injecting into the code under test. */
internal fun capturingLogger(writer: CapturingLogWriter, tag: String = "Test"): Logger =
    Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(writer)), tag = tag)

/** Lower-case hex of [bytes], for asserting that key material never shows up in a log. */
internal fun hex(bytes: ByteArray): String = bytes.toHexString()
