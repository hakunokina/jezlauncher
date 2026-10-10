package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Alarm and full-screen notes open full screen also while the phone is in use; a reminder stays a notification. */
class AlertLaunchTest {

    private val fullScreenTypes = listOf(AlertType.ALARM, AlertType.FULL_SCREEN)

    @Test
    fun inUseStartsTheScreen() {
        for (type in fullScreenTypes) {
            assertEquals(AlertLaunch.START_SCREEN, type.launch(interactive = true, locked = false, home = true))
        }
    }

    @Test
    fun screenOffOrLockedUsesTheFullScreenIntent() {
        for (type in fullScreenTypes) for (home in listOf(true, false)) {
            assertEquals(AlertLaunch.FULL_SCREEN_INTENT, type.launch(interactive = false, locked = true, home = home))
            assertEquals(AlertLaunch.FULL_SCREEN_INTENT, type.launch(interactive = false, locked = false, home = home))
            assertEquals(AlertLaunch.FULL_SCREEN_INTENT, type.launch(interactive = true, locked = true, home = home))
        }
    }

    /** Android blocks the start from the background unless the app is the default launcher. */
    @Test
    fun notTheLauncherKeepsTheFullScreenIntent() {
        for (type in fullScreenTypes) {
            assertEquals(AlertLaunch.FULL_SCREEN_INTENT, type.launch(interactive = true, locked = false, home = false))
        }
    }

    @Test
    fun reminderIsOnlyANotification() {
        for (interactive in listOf(true, false)) for (locked in listOf(true, false)) for (home in listOf(true, false)) {
            assertEquals(AlertLaunch.NOTIFICATION, AlertType.NOTIFICATION.launch(interactive, locked, home))
        }
    }
}
