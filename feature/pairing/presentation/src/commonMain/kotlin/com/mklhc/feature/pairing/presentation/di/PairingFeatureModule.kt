package com.mklhc.feature.pairing.presentation.di

import com.mklhc.feature.pairing.presentation.PairingViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * The Koin module of the pairing feature.
 *
 * Business logic: declares [PairingViewModel], whose only constructor dependency is the `KartSession` the :app
 * module binds (the real repository on a device, the scriptable fake in a hardware-free build). :app includes
 * this module in its Koin start-up and provides that binding; the Root composable then obtains the ViewModel with
 * `koinViewModel()`. The ViewModel starts the pairing flow when it is created, so it must be scoped to the
 * pairing screen's navigation entry, which is what `koinViewModel()` does.
 */
val pairingFeatureModule = module {
    viewModelOf(::PairingViewModel)
}
