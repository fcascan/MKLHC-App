package com.mklhc.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Dispatchers.Main on the desktop: the ViewModels launch in viewModelScope, which needs a main dispatcher, and the
 * JVM only has one when kotlinx-coroutines-swing is on the classpath. The other tests replace Main with a test
 * dispatcher; this one checks the real thing, so the app does not start with "Module with the Main dispatcher is
 * missing".
 */
class DesktopMainDispatcherTest {

    @Test
    fun theMainDispatcherIsTheSwingEventQueue() = runBlocking {
        val thread = withContext(Dispatchers.Main) { Thread.currentThread().name }

        assertTrue(thread.contains("AWT-EventQueue"), "expected the AWT event queue thread, got $thread")
    }
}
