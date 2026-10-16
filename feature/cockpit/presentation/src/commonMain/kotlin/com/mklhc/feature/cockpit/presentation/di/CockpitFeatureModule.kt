package com.mklhc.feature.cockpit.presentation.di

import com.mklhc.feature.cockpit.presentation.CockpitViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * The Koin module of the cockpit feature.
 *
 * Business logic: declares [CockpitViewModel], whose only constructor dependency is the `KartSession` the :app
 * module binds (the real repository on a device, the scriptable fake in a hardware-free build). :app includes
 * this module in its Koin start-up and provides that binding; the Root composable then obtains the ViewModel with
 * `koinViewModel()`.
 */
val cockpitFeatureModule = module {
    viewModelOf(::CockpitViewModel)
}
