package com.mklhc.app.di

import com.mklhc.app.demo.DemoKartSession
import com.mklhc.core.data.radio.DemoUsbAdapterRepository
import com.mklhc.core.data.radio.UsbAdapterRepository
import com.mklhc.core.data.session.KartSession
import com.mklhc.feature.cockpit.presentation.di.cockpitFeatureModule
import com.mklhc.feature.link.presentation.di.linkFeatureModule
import com.mklhc.feature.pairing.presentation.di.pairingFeatureModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * The app-level Koin module: the bindings only :app can decide.
 *
 * Business logic: every feature ViewModel depends on a single [KartSession] (and the Kart link screen on a single
 * [UsbAdapterRepository]); the feature modules deliberately do not bind them, so the composition root chooses the
 * implementations. This module binds the scripted [DemoKartSession] and the fixed [DemoUsbAdapterRepository], so the
 * whole UI runs without a radio or a kart. The demo session runs on an application-lifetime
 * scope that is never cancelled; this is acceptable only because the demo drives no hardware.
 *
 * - The desktop binds the real session: its entry point (`Main.kt`, desktopMain) builds the `DesktopKartRuntime`
 *   (radiod, the radio access point, the persistent pairing store, the USB adapter repository over radiod and
 *   `KartSessionRepository`) and overrides these bindings with the runtime's session and adapter repository through
 *   `initKoin`'s extra module, which Koin loads after this one. It keeps
 *   this demo binding only when asked to, with `MKLHC_DEMO=1` or the `--demo` argument. Its window stops the session
 *   and shuts the runtime down on close.
 * - Android still binds the demo, adapter list included: its radio link (USB) is not built yet.
 *
 * TODO(production): when the Android radio adapter exists, bind `KartSessionRepository` on Android too (constructed
 * with that `KartRadioAp`, a `PairingStore` and a `SessionConfig`), and first make sure the Android entry point calls
 * `KartSession.stop()` on exit so the kart is put to sleep with a final neutral command. Android does NOT do this yet
 * (nothing calls stop() and the scope is never cancelled), so a real session would keep the radio and teleop loop
 * running in the background, relying only on the kart's deadman timeout. Before binding the real session there, stop
 * it from the Android lifecycle (for example a `ProcessLifecycleOwner` observer, or the Application/Activity teardown)
 * and cancel the session scope.
 */
val appModule = module {
    single { DemoKartSession(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) } bind KartSession::class
    // The demo's fixed adapter list. The desktop overrides it with radiod's devices; Android keeps it until its USB
    // enumeration exists (Phase 2).
    single<UsbAdapterRepository> { DemoUsbAdapterRepository() }
}

/**
 * Every Koin module the app needs, in the order they are loaded: the app module (the demo [KartSession] binding,
 * which the desktop overrides with the real session), then one module per feature (its ViewModel).
 */
fun appKoinModules(): List<Module> = listOf(
    appModule,
    linkFeatureModule,
    pairingFeatureModule,
    cockpitFeatureModule,
)
