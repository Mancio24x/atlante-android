package app.atlante.library

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The back gesture leaves every settings page: "Il mio tema" → Aspetto → all settings → Home. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackTest {
    private fun call(a: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(a)
    private val section = MainActivity::class.java.getDeclaredField("settingsSection").apply { isAccessible = true }
    private val destination = MainActivity::class.java.getDeclaredField("destination").apply { isAccessible = true }

    @Test fun backLeavesEverySettingsPage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = controller.get()
        for (page in listOf("aspetto", "obiettivi", "lettura", "lingua", "libreria", "info")) {
            call(a, "settings"); section.set(a, page); call(a, "showCompanion")
            a.onBackPressedDispatcher.onBackPressed()
            assertEquals(page, "", section.get(a)); assertEquals("Impostazioni", destination.get(a))
            a.onBackPressedDispatcher.onBackPressed()
            assertEquals("Home", destination.get(a))
        }
        call(a, "settings"); section.set(a, "aspetto"); call(a, "showCompanion")
        section.set(a, "tema"); call(a, "showCompanion")
        a.onBackPressedDispatcher.onBackPressed(); assertEquals("aspetto", section.get(a))
        a.onBackPressedDispatcher.onBackPressed(); assertEquals("", section.get(a))
        a.onBackPressedDispatcher.onBackPressed(); assertEquals("Home", destination.get(a))
        controller.pause().stop().destroy()
    }
}
