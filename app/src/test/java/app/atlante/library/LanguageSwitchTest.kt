package app.atlante.library

import app.atlante.library.ui.L
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Choosing the app's language in Impostazioni › Lingua reloads the app on the same page, in the new language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageSwitchTest {
    @After fun italian() { L.choose(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "") }

    @Test fun choosingEnglishStaysOnTheLanguagePage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var a = controller.get()
        val nav = MainActivity::class.java.getDeclaredMethod("navigate", String::class.java).apply { isAccessible = true }
        nav.invoke(a, "Impostazioni")
        val section = MainActivity::class.java.getDeclaredField("settingsSection").apply { isAccessible = true }
        section.set(a, "lingua"); MainActivity::class.java.getDeclaredMethod("showCompanion").apply { isAccessible = true }.invoke(a)
        L.choose(a, "en")
        MainActivity::class.java.getDeclaredMethod("recreateHere").apply { isAccessible = true }.invoke(a)
        repeat(3) { ShadowLooper.idleMainLooper() }
        a = controller.get()
        assertTrue(L.english)
        assertEquals("Impostazioni", MainActivity::class.java.getDeclaredField("destination").apply { isAccessible = true }.get(a))
        assertEquals("lingua", section.get(a))
    }
}
