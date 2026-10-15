package com.mklhc.core.kart_protocol

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig

/** Collects every Kermit log call so a test can assert what the code under test logged. */
internal class CapturingLogWriter : LogWriter() {
    class Entry(val severity: Severity, val message: String)

    val entries = mutableListOf<Entry>()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        entries += Entry(severity, message)
    }

    /** True if something was logged at exactly [severity]. */
    fun logged(severity: Severity): Boolean = entries.any { it.severity == severity }
}

/** A logger that records into [writer] at every severity, for injecting into the code under test. */
internal fun capturingLogger(writer: CapturingLogWriter, tag: String = "Test"): Logger =
    Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(writer)), tag = tag)
