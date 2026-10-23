package com.mklhc.app.di

import com.mklhc.app.demo.DemoKartSession
import com.mklhc.core.data.radio.DemoUsbAdapterRepository
import com.mklhc.core.data.radio.UsbAdapterRepository
import com.mklhc.core.data.session.FakeKartSession
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.LinkState
import com.mklhc.feature.cockpit.presentation.CockpitViewModel
import com.mklhc.feature.link.presentation.LinkViewModel
import com.mklhc.feature.pairing.presentation.PairingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The app's Koin graph: [appKoinModules] must resolve every binding the screens need, with nothing missing, and
 * [initKoin] must start it once and only once. A ViewModel is created for real, so a missing `KartSession` (or any
 * other constructor dependency) fails here instead of when a screen is first opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppKoinModulesTest {

    @BeforeTest
    fun setUp() {
        // The ViewModels collect in viewModelScope, which runs on the main dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    // ---- the module list ---------------------------------------------------------------------

    @Test
    fun theKartSessionIsTheDemoSessionAndIsASingleton() {
        val koin = koinApplication { modules(appKoinModules()) }.koin

        val session = koin.get<KartSession>()

        assertIs<DemoKartSession>(session)
        assertSame(session, koin.get<KartSession>(), "one session for the whole app")
        assertSame(session, koin.get<DemoKartSession>(), "the demo type resolves to the same instance")
        koin.close()
    }

    @Test
    fun theAdapterRepositoryIsTheDemoListAndIsASingleton() {
        val koin = koinApplication { modules(appKoinModules()) }.koin

        val adapters = koin.get<UsbAdapterRepository>()

        assertIs<DemoUsbAdapterRepository>(adapters)
        assertSame(adapters, koin.get<UsbAdapterRepository>(), "one adapter repository for the whole app")
        koin.close()
    }

    @Test
    fun everyFeatureViewModelResolvesFromTheAppModules() = runTest {
        val koin = koinApplication { modules(appKoinModules()) }.koin

        assertNotNull(koin.get<LinkViewModel>())
        assertNotNull(koin.get<CockpitViewModel>())
        // Creating the pairing ViewModel starts the demo pairing script, which must not outlive the test.
        assertNotNull(koin.get<PairingViewModel>())

        koin.get<KartSession>().stop()
        koin.close()
    }

    @Test
    fun theViewModelsAreNewInstancesButShareTheSession() {
        val koin = koinApplication { modules(appKoinModules()) }.koin

        val first = koin.get<LinkViewModel>()
        val second = koin.get<LinkViewModel>()

        assertTrue(first !== second, "a ViewModel is created per owner, not shared")
        koin.close()
    }

    // ---- initKoin ----------------------------------------------------------------------------

    @Test
    fun initKoinStartsTheAppGraphAndIsIdempotent() {
        val first = initKoin()
        val second = initKoin()

        assertSame(first, second, "a second call keeps the running Koin")
        assertIs<DemoKartSession>(first.get<KartSession>())
    }

    @Test
    fun initKoinRunsThePlatformConfigOnTheFirstCallOnly() {
        var configured = 0

        initKoin { configured++ }
        initKoin { configured++ }

        assertEquals(1, configured)
    }

    @Test
    fun anExtraModuleOverridesTheSessionTheViewModelsSee() {
        val fake = FakeKartSession()

        val koin = initKoin(extra = module { single<KartSession> { fake } })

        assertSame(fake, koin.get<KartSession>())
        val viewModel = koin.get<LinkViewModel>()
        fake.setLinkState(LinkState.KartReady)
        assertEquals(LinkState.KartReady, viewModel.state.value.linkState, "the ViewModel follows the overriding session")
    }
}
