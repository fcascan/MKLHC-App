package com.mklhc.app.di

import com.mklhc.app.demo.DemoKartSession
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
 * Business logic: every feature ViewModel depends on a single [KartSession]; the feature modules deliberately do
 * not bind it, so the composition root chooses the implementation. For now that is the scripted
 * [DemoKartSession], so the whole UI runs without a radio or a kart (this is what the desktop run shows). The demo
 * session runs on an application-lifetime scope that is never cancelled; this is acceptable only because the demo
 * drives no hardware.
 *
 * TODO(production): once the wifi-ap `KartRadioAp` adapter exists, replace this binding with
 * `KartSessionRepository` (constructed with that adapter, a `PairingStore` and a `SessionConfig`), per platform
 * where the adapter differs (a USB adapter on Android, a desktop one or none on the desktop), and make sure the
 * entry points call `KartSession.stop()` on exit so the kart is put to sleep with a final neutral command. The
 * desktop window already stops the session on close; Android does NOT yet (nothing calls stop() and the scope is
 * never cancelled), so a real session would keep the radio and teleop loop running in the background, relying only
 * on the kart's deadman timeout. Before binding the real session, stop it from the Android lifecycle (for example a
 * `ProcessLifecycleOwner` observer, or the Application/Activity teardown) and cancel the session scope.
 */
val appModule = module {
    single { DemoKartSession(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) } bind KartSession::class
}

/**
 * Every Koin module the app needs, in the order they are loaded: the app module (the [KartSession] binding), then
 * one module per feature (its ViewModel).
 */
fun appKoinModules(): List<Module> = listOf(
    appModule,
    linkFeatureModule,
    pairingFeatureModule,
    cockpitFeatureModule,
)
