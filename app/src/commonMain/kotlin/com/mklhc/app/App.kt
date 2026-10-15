package com.mklhc.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Root composable shared by the Android and desktop entry points.
 *
 * Phase 0 shows a centered "MKLHC-App" label inside a Material 3 surface; navigation and the
 * feature screens are wired here in later phases.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "MKLHC-App")
            }
        }
    }
}
