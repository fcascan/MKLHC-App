package com.mklhc.app

import android.app.Application
import android.util.Log
import com.mklhc.app.di.initKoin
import org.koin.android.ext.koin.androidContext

/**
 * The Android [Application]: starts Koin once, before any Activity exists.
 *
 * Business logic: the shared UI resolves its ViewModels and the kart session from Koin, so the dependency graph
 * has to be up before [MainActivity] composes anything. This is the only place Android starts Koin;
 * [initKoin] is idempotent, so a recreated process or Activity never starts it twice.
 *
 * TODO(real adapter): the session bound today is the scripted demo, which needs no USB radio, so nothing here asks
 * for USB permission. When the real session replaces it, the USB-permission flow (the `AwaitingUsbPermission` link
 * state) has to be provided by the Android radio adapter, and the Koin binding for the Android `KartRadioAp` added
 * to the platform configuration passed below.
 */
class MklhcApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate(): starting Koin")
        initKoin { androidContext(this@MklhcApplication) }
    }

    private companion object {
        const val TAG = "MklhcApplication"
    }
}
