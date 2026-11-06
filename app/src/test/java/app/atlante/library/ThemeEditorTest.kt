package app.atlante.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.atlante.library.ui.compose.hsv
import app.atlante.library.ui.compose.hsvColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The touch colour picker works in hue/saturation/brightness and gives back the same colours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeEditorTest {
    @Test fun hsvRoundTrip() {
        for (c in listOf(0xFF326359, 0xFF8C3B2E, 0xFFFBF8F1, 0xFF101014, 0xFF44507A).map { Color(it) }) {
            val h = c.hsv(); val back = hsvColor(h[0], h[1], h[2])
            assertEquals(c.toArgb(), back.toArgb())
        }
        assertEquals(Color.Red.toArgb(), hsvColor(0f, 1f, 1f).toArgb())
        assertEquals(Color.White.toArgb(), hsvColor(200f, 0f, 1f).toArgb())
        assertEquals(Color.Black.toArgb(), hsvColor(200f, 1f, 0f).toArgb())
        assertEquals(hsvColor(360f, 1f, 1f).toArgb(), hsvColor(999f, 2f, 2f).toArgb())     // clamped
    }
}
