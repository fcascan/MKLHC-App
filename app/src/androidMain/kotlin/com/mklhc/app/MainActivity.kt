package com.mklhc.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Single launcher Activity of the Android app. It owns no business logic: it only hosts the shared
 * Compose UI ([App]) that is also used by the desktop entry point.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate(): hosting shared App composable -> hasSavedState=${savedInstanceState != null}")
        setContent { App() }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
