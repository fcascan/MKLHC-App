package com.mklhc.core.design_system

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.mklhc.core.design_system.components.AppButton
import com.mklhc.core.design_system.components.AppButtonStyle
import com.mklhc.core.design_system.components.AppIconButton
import com.mklhc.core.design_system.components.LabeledValue
import com.mklhc.core.design_system.components.LinkStatePill
import com.mklhc.core.design_system.components.StatusPill
import com.mklhc.core.design_system.components.StatusTone
import com.mklhc.core.design_system.drive.BrakeLightButton
import com.mklhc.core.design_system.drive.SteeringSlider
import com.mklhc.core.design_system.drive.ThrottlePedal
import com.mklhc.core.design_system.hud.ArtificialHorizon
import com.mklhc.core.design_system.hud.BatteryGauge
import com.mklhc.core.design_system.hud.GMeter
import com.mklhc.core.design_system.hud.SignalIndicator
import com.mklhc.core.design_system.hud.SpeedOrThrottleReadout
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke tests: every widget composes in both themes, draws a frame without throwing (the canvas draw lambdas
 * only run when a frame is actually rendered, so each test captures the root to an image), and exposes the
 * description a screen reader will speak. They are the headless stand-in for flipping through the @Preview
 * functions, which commonMain cannot execute.
 */
@OptIn(ExperimentalTestApi::class)
class DesignSystemSmokeTest {

    @Test
    fun themeShowcaseRendersInBothThemes() = runComposeUiTest {
        var dark by mutableStateOf(true)
        setContent { AppTheme(darkTheme = dark) { ThemeShowcase() } }
        for (isDark in listOf(true, false)) {
            dark = isDark
            waitForIdle()
            onNodeWithText("Cockpit theme").assertExists()
            val image = onRoot().captureToImage()
            assertTrue(image.width > 0 && image.height > 0)
        }
    }

    @Test
    fun hudWidgetsDrawAndAreDescribed() = runComposeUiTest {
        setContent {
            AppTheme {
                Column {
                    Row {
                        BatteryGauge(bars = 3)
                        BatteryGauge(bars = 1, charging = true)
                        BatteryGauge(bars = 9)
                        SignalIndicator(rssiDbm = -62)
                        SignalIndicator(rssiDbm = null)
                    }
                    Row {
                        ArtificialHorizon(pitchDegrees = 8f, rollDegrees = -15f)
                        ArtificialHorizon(pitchDegrees = Float.NaN, rollDegrees = Float.POSITIVE_INFINITY)
                        GMeter(gX = 0.6f, gY = 0.3f)
                        GMeter(gX = Float.NaN, gY = 9f)
                    }
                    SpeedOrThrottleReadout(value = "35", unit = "%", label = "THROTTLE", fraction = 0.35f)
                    SpeedOrThrottleReadout(value = "-20", unit = "%", label = "THROTTLE", fraction = -0.2f)
                }
            }
        }

        onNodeWithContentDescription("Battery, 3 of 4 bars").assertExists()
        onNodeWithContentDescription("Battery, 1 of 4 bars, charging").assertExists()
        onNodeWithContentDescription("Battery, 4 of 4 bars").assertExists()
        onNodeWithContentDescription("Signal, 3 of 4 bars, -62 dBm").assertExists()
        onNodeWithContentDescription("Signal strength unknown").assertExists()
        onNodeWithContentDescription("Artificial horizon, pitch 8 degrees, roll -15 degrees").assertExists()
        onNodeWithContentDescription("Artificial horizon, pitch 0 degrees, roll 0 degrees").assertExists()
        onNodeWithContentDescription("G meter, lateral 0.6 g, longitudinal 0.3 g").assertExists()
        onNodeWithContentDescription("G meter, lateral 0.0 g, longitudinal 9.0 g").assertExists()
        onNodeWithText("35").assertExists()
        val image = onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun driveControlsDrawAndAreDescribed() = runComposeUiTest {
        setContent {
            AppTheme {
                Row {
                    SteeringSlider(value = -0.4f, onValue = {}, onRelease = {})
                    ThrottlePedal(value = 0.6f, onValue = {}, onRelease = {})
                    ThrottlePedal(value = -0.3f, onValue = {}, onRelease = {}, enabled = false)
                    BrakeLightButton(active = true, onActiveChange = {})
                }
            }
        }

        onNodeWithContentDescription("Steering").assertExists()
        onNodeWithContentDescription("Brake, hold to stop").assertExists()
        val image = onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun buttonsPillsAndValuesRender() = runComposeUiTest {
        setContent {
            AppTheme {
                Column {
                    for (style in AppButtonStyle.entries) AppButton(text = "Button $style", onClick = {}, style = style)
                    AppButton(text = "Disabled", onClick = {}, enabled = false)
                    AppIconButton(onClick = {}, contentDescription = "Open settings") {}
                    for (tone in StatusTone.entries) StatusPill(text = "Pill $tone", tone = tone)
                    LabeledValue(label = "Serial", value = "SN-0012345")
                    LinkStatePill(LinkState.Beaconing(KartNetworkKind.GAME))
                    LinkStatePill(LinkState.Failed("pairing cancelled"))
                }
            }
        }

        onNodeWithText("Button Primary").assertExists()
        onNodeWithText("Pill Error").assertExists()
        onNodeWithContentDescription("Open settings").assertExists()
        onNodeWithText("Waiting for the kart to join").assertExists()
        onNodeWithText("Link failed: pairing cancelled").assertExists()
        val image = onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }
}
