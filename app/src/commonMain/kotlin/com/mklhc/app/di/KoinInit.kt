package com.mklhc.app.di

import co.touchlab.kermit.Logger
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.mp.KoinPlatformTools

private val log = Logger.withTag("KoinInit")

/**
 * Starts Koin once for the whole process, from either platform's entry point.
 *
 * Business logic: the Android `Application` and the desktop `main` both call this before any UI is composed, so
 * `koinViewModel()` finds a started Koin. It is idempotent: if Koin is already running (a second call, a restarted
 * Activity in the same process, a test that started its own) the running instance is returned and nothing is
 * reloaded, instead of failing with an "already started" error. Call it from a single thread at start-up.
 *
 * @param extra an optional module loaded after the app's own, so its definitions override them; for tests and for
 *   swapping the [com.mklhc.core.data.session.KartSession] binding. Ignored when Koin is already running.
 * @param platformConfig platform-specific setup applied to the Koin application, for example `androidContext(...)`.
 * @return the running Koin instance.
 */
fun initKoin(extra: Module? = null, platformConfig: KoinApplication.() -> Unit = {}): Koin {
    KoinPlatformTools.defaultContext().getOrNull()?.let { running ->
        log.i { "initKoin(): Koin is already running, keeping it" }
        return running
    }
    log.i { "initKoin(): starting Koin -> hasExtraModule=${extra != null}" }
    return startKoin {
        platformConfig()
        modules(appKoinModules())
        extra?.let { modules(it) }
    }.koin
}
