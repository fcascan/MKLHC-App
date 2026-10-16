package com.mklhc.feature.pairing.presentation.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.floor
import kotlin.math.min

/**
 * Draws a QR code: black modules on a white background with the quiet zone around them.
 *
 * Business logic: the colours are fixed black on white, whatever the theme, because a scanner needs the full
 * contrast and the standard polarity (a themed or inverted code may not be read at all), and the code is
 * surrounded by a quiet zone of [QUIET_ZONE_MODULES] modules, the margin the QR standard requires so the finder
 * patterns can be found. The matrix itself comes from [encodeQrMatrix]: the pairing code has to be byte mode at
 * level M, with the raw payload octets, or the kart cannot scan it (see [encodeQrMatrix]); this composable only
 * draws whatever matrix it is given.
 *
 * The drawing is square: it takes the smaller side of the space it is given, and a module is a whole number of
 * pixels so every edge is crisp (a fractional module size would blur or leave hairline gaps, which hurts
 * scanning); any leftover space is just more white margin. A canvas this size is far cheaper than one composable
 * per module, and horizontal runs of dark modules are drawn as one rectangle.
 *
 * @param matrix the module matrix to draw (without quiet zone).
 * @param modifier the modifier to apply; the code is kept square.
 * @param contentDescription what the code is for, spoken by the screen reader; null leaves it undescribed.
 */
@Composable
fun QrCode(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val described = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier = described.aspectRatio(1f)) {
        drawRect(color = Color.White)

        val modulesPerSide = matrix.size + 2 * QUIET_ZONE_MODULES
        val moduleSize = floor(min(size.width, size.height) / modulesPerSide)
        if (moduleSize < 1f) return@Canvas

        val codeSide = moduleSize * modulesPerSide
        val originX = floor((size.width - codeSide) / 2f) + QUIET_ZONE_MODULES * moduleSize
        val originY = floor((size.height - codeSide) / 2f) + QUIET_ZONE_MODULES * moduleSize
        for (y in 0 until matrix.size) {
            var x = 0
            while (x < matrix.size) {
                if (!matrix[x, y]) {
                    x++
                    continue
                }
                val runStart = x
                while (x < matrix.size && matrix[x, y]) x++
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(originX + runStart * moduleSize, originY + y * moduleSize),
                    size = Size((x - runStart) * moduleSize, moduleSize),
                )
            }
        }
    }
}

/** The width of the quiet zone, in modules, that the QR standard requires around a code. */
const val QUIET_ZONE_MODULES = 4

/** A 21 x 21 checkerboard: not a scannable code, just enough dark and light modules to see the drawing and its quiet zone. */
@Preview
@Composable
private fun QrCodePreview() {
    val side = 21
    val checkerboard = QrMatrix(size = side, modules = BooleanArray(side * side) { (it % side + it / side) % 2 == 0 })
    PreviewSurface {
        QrCode(matrix = checkerboard, modifier = Modifier.size(240.dp))
    }
}
