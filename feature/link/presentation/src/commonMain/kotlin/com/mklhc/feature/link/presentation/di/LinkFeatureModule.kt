package com.mklhc.feature.link.presentation.di

import com.mklhc.feature.link.presentation.LinkViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * The Koin module of the link feature.
 *
 * Business logic: declares [LinkViewModel], whose constructor dependencies are the `KartSession` and the
 * `UsbAdapterRepository` the :app module binds (the real ones on a device or the desktop, the scriptable fake
 * session and the demo adapter list in a hardware-free build). :app includes this module in its Koin start-up and
 * provides those bindings; the Root composable then obtains the ViewModel with `koinViewModel()`.
 */
val linkFeatureModule = module {
    viewModelOf(::LinkViewModel)
}
