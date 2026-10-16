package com.mklhc.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.mklhc.app.di.initKoin
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [App] itself, the way the desktop entry point runs it: Koin is started globally by [initKoin] (with a scripted
 * session swapped in through its extra module) and [App] is composed with no Koin wrapper of its own. If the Koin
 * Compose integration did not pick the global instance up, the first `koinViewModel()` would throw here.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AppTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun appComposesUnderTheGlobalKoinAndShowsTheLinkScreenFirst() = runComposeUiTest {
        val session = FakeKartSession()
        initKoin(extra = module { single<KartSession> { session } })
        val host = TestHost()
        setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides host,
                LocalLifecycleOwner provides host,
            ) {
                App()
            }
        }

        onNodeWithText("Kart link").assertExists()
        onNodeWithText("Pair a kart").performClick()
        waitForIdle()
        onNodeWithText("Point the kart camera at the code.").assertExists()
        assertTrue(session.startPairingCalled)

        host.viewModelStore.clear()
    }
}
