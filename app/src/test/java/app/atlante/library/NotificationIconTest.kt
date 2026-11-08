package app.atlante.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The crash after "Ripara saghe" (emulator, Nov 2026): "Bad notification for startForeground". Notifications are drawn
 * by the system, outside the app's theme: their small icon must not use theme attributes such as ?attr/colorControlNormal.
 */
class NotificationIconTest {
    private val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    @Test fun notificationIconHasNoThemeAttributes() {
        assertFalse(File(root, "res/drawable/ic_notification.xml").readText().contains("?attr"))
    }

    @Test fun everyNotificationUsesThatIcon() {
        val sources = root.walk().filter { it.extension == "kt" || it.extension == "java" }.map { it.readText() }.toList()
        val icons = sources.flatMap { Regex("setSmallIcon\\(R\\.drawable\\.(\\w+)\\)").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertTrue(icons.isNotEmpty())
        assertTrue(icons.toString(), icons.all { it == "ic_notification" })
    }
}
