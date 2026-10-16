package com.mklhc.feature.pairing.presentation.qr

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppTheme
import com.mklhc.feature.pairing.presentation.samplePairingPayload
import com.mklhc.feature.pairing.presentation.scanQrOctets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [QrCode] drawn for real and scanned back: the matrix goes through the composable's canvas, the frame is
 * captured as an image and ZXing's scanner reads the payload octets out of it. That proves the drawing (module
 * size, quiet zone, polarity) produces a code a scanner can read, not just a plausible-looking one.
 */
@OptIn(ExperimentalTestApi::class)
class QrCodeTest {

    private val description = "Pairing code"

    @Test
    fun aDrawnCodeScansBackToThePayloadOctets() = runComposeUiTest {
        val payload = samplePairingPayload()
        setContent { QrCode(encodeQrMatrix(payload), Modifier.size(328.dp), contentDescription = description) }

        val image = onNodeWithContentDescription(description).captureToImage()
        assertContentEquals(payload, image.scanQrOctets())
    }

    @Test
    fun aDrawnCodeStaysBlackOnWhiteInTheDarkTheme() = runComposeUiTest {
        val payload = samplePairingPayload(seedByte = 0x77)
        setContent {
            AppTheme(darkTheme = true) {
                QrCode(encodeQrMatrix(payload), Modifier.size(328.dp), contentDescription = description)
            }
        }

        val image = onNodeWithContentDescription(description).captureToImage()
        assertContentEquals(payload, image.scanQrOctets())
    }

    @Test
    fun aSizeThatIsNotAMultipleOfTheModuleCountStillScans() = runComposeUiTest {
        // 41 modules with the quiet zone; 333 px is not a multiple, so the module is 8 px and the rest is margin.
        val payload = samplePairingPayload(seedByte = 0x05)
        setContent { QrCode(encodeQrMatrix(payload), Modifier.size(333.dp), contentDescription = description) }

        val image = onNodeWithContentDescription(description).captureToImage()
        assertEquals(333, image.width)
        assertContentEquals(payload, image.scanQrOctets())
    }

    @Test
    fun aCodeTooSmallToDrawWholePixelsRendersBlankWithoutFailing() = runComposeUiTest {
        setContent { QrCode(encodeQrMatrix(samplePairingPayload()), Modifier.size(20.dp), contentDescription = description) }

        val image = onNodeWithContentDescription(description).captureToImage()
        assertEquals(20, image.width)
    }
}
