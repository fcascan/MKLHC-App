package com.mklhc.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * What a real window provides to the Compose tree, for the offscreen UI tests: a ViewModel store and a lifecycle
 * that is resumed. Clear [viewModelStore] at the end of a test so the screens' ViewModels are released.
 */
internal class TestHost : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
}
