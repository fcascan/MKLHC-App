package com.mklhc.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import com.mklhc.app.di.initKoin
import com.mklhc.core.data.session.KartSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Desktop (JVM) entry point, resolved as `com.mklhc.app.MainKt` by the Compose Desktop application block.
 *
 * Business logic: starts Koin first (the screens' ViewModels are resolved from it), then opens a single window
 * hosting the shared [App] composable. With the demo session bound (see the app Koin module) the whole UI runs
 * without hardware. Closing the window stops the session before the process exits, so a real session would put the
 * kart to sleep with a final neutral command.
 */
fun main() {
    val log = Logger.withTag("Main")
    val koin = initKoin()
    application {
        Window(
            onCloseRequest = {
                log.i { "onCloseRequest(): stopping the kart session before exit" }
                // onCloseRequest runs on the AWT event thread. The demo's stop() returns at once, but a real
                // session's stop() sends a final neutral command and tears the network down; run it off the EDT and
                // bound it so a slow or Main-dispatched teardown can never hang the window, and exit regardless.
                try {
                    runBlocking(Dispatchers.Default) {
                        withTimeout(SESSION_STOP_TIMEOUT_MS) { koin.get<KartSession>().stop() }
                    }
                } catch (failure: Exception) {
                    log.e(failure) { "onCloseRequest(): stopping the session failed -> message=${failure.message}" }
                } finally {
                    exitApplication()
                }
            },
            title = "MKLHC-App",
            state = rememberWindowState(width = 1100.dp, height = 700.dp),
        ) {
            App()
        }
    }
}

/** How long to wait for [KartSession.stop] on window close before giving up and exiting anyway. */
private const val SESSION_STOP_TIMEOUT_MS = 3_000L
