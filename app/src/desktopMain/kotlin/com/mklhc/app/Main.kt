package com.mklhc.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Desktop (JVM) entry point, resolved as `com.mklhc.app.MainKt` by the Compose Desktop
 * application block. Opens a single window hosting the shared [App] composable.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "MKLHC-App") {
        App()
    }
}
