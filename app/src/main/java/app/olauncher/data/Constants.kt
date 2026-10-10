package app.olauncher.data

object Constants {

    object Key {
        const val FLAG = "flag"
        const val RENAME = "rename"
    }

    object Dialog {
        const val ABOUT = "ABOUT"
        const val WALLPAPER = "WALLPAPER"
        const val HIDDEN = "HIDDEN"
        const val KEYBOARD = "KEYBOARD"
        const val DIGITAL_WELLBEING = "DIGITAL_WELLBEING"
    }

    object UserState {
        const val START = "START"
        const val WALLPAPER = "WALLPAPER"
        const val DONE = "DONE"
    }

    object DateTime {
        const val OFF = 0
        const val ON = 1
        const val DATE_ONLY = 2

        fun isTimeVisible(dateTimeVisibility: Int): Boolean {
            return dateTimeVisibility == ON
        }

        fun isDateVisible(dateTimeVisibility: Int): Boolean {
            return dateTimeVisibility == ON || dateTimeVisibility == DATE_ONLY
        }
    }

    object CharacterIndicator {
        const val SHOW = 102
        const val HIDE = 101
    }

    val CLOCK_APP_PACKAGES = arrayOf(
        "com.google.android.deskclock", //Google Clock
        "com.sec.android.app.clockpackage", //Samsung Clock
        "com.oneplus.deskclock", //OnePlus Clock
        "com.miui.clock", //Xiaomi Clock
    )

    const val WALL_TYPE_LIGHT = "light"
    const val WALL_TYPE_DARK = "dark"

    // Custom feed: the dark pool from NIGHT_START_HOUR (inclusive) up to NIGHT_END_HOUR (exclusive), local time
    const val NIGHT_START_HOUR = 19
    const val NIGHT_END_HOUR = 5

//    const val THEME_MODE_DARK = 0
//    const val THEME_MODE_LIGHT = 1
//    const val THEME_MODE_SYSTEM = 2

    const val FLAG_LAUNCH_APP = 100
    const val FLAG_HIDDEN_APPS = 101

    const val FLAG_SET_HOME_APP_1 = 1
    const val FLAG_SET_HOME_APP_2 = 2
    const val FLAG_SET_HOME_APP_3 = 3
    const val FLAG_SET_HOME_APP_4 = 4
    const val FLAG_SET_HOME_APP_5 = 5
    const val FLAG_SET_HOME_APP_6 = 6
    const val FLAG_SET_HOME_APP_7 = 7
    const val FLAG_SET_HOME_APP_8 = 8

    const val FLAG_SET_CLOCK_APP = 13
    const val FLAG_SET_CALENDAR_APP = 14
    const val FLAG_SET_SCREEN_TIME_APP = 15
    const val FLAG_INTERRUPT_APPS = 16

    const val FLAG_SET_BOTTOM_BAR_APP_1 = 21
    const val FLAG_SET_BOTTOM_BAR_APP_2 = 22
    const val FLAG_SET_BOTTOM_BAR_APP_3 = 23
    const val FLAG_SET_BOTTOM_BAR_APP_4 = 24
    const val FLAG_SET_BOTTOM_BAR_APP_5 = 25

    const val REQUEST_CODE_ENABLE_ADMIN = 666
    const val REQUEST_CODE_LAUNCHER_SELECTOR = 678
    const val REQUEST_CODE_WIDGET_CONFIGURE = 679

    const val LONG_PRESS_DELAY_MS = 500L
    const val ONE_DAY_IN_MILLIS = 86400000L
    const val ONE_HOUR_IN_MILLIS = 3600000L
    const val ONE_MINUTE_IN_MILLIS = 60000L

    const val MIN_ANIM_REFRESH_RATE = 30f

    const val URL_GITHUB = "https://github.com/hakunokina/jezlauncher"
    const val URL_ABOUT = "$URL_GITHUB#readme"
    const val URL_DOUBLE_TAP = "$URL_GITHUB#double-tap-to-lock"
    // blob/HEAD resolves to the default branch, whatever it is called
    const val URL_PRIVACY = "$URL_GITHUB/blob/HEAD/PRIVACY.md"
    const val URL_LICENSE = "$URL_GITHUB/blob/HEAD/LICENSE"
    const val URL_UPSTREAM_GITHUB = "https://github.com/tanujnotes/Olauncher"
    const val URL_DUCK_SEARCH = "https://duck.co/?q="

    const val DIGITAL_WELLBEING_PACKAGE_NAME = "com.google.android.apps.wellbeing"
    const val DIGITAL_WELLBEING_ACTIVITY = "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity"
    const val DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME = "com.samsung.android.forest"
    const val DIGITAL_WELLBEING_SAMSUNG_ACTIVITY = "com.samsung.android.forest.launcher.LauncherActivity"
    const val WALLPAPER_WORKER_NAME = "WALLPAPER_WORKER_NAME"
    const val WALLPAPER_REFRESH_WORKER_NAME = "WALLPAPER_REFRESH_WORKER_NAME"
    const val WALLPAPER_NEXT_WORKER_NAME = "WALLPAPER_NEXT_WORKER_NAME"
    const val KEY_WALLPAPER_NEXT = "KEY_WALLPAPER_NEXT"
    const val WALLPAPER_SWITCH_WORKER_NAME = "WALLPAPER_SWITCH_WORKER_NAME"
    const val KEY_WALLPAPER_SWITCH = "KEY_WALLPAPER_SWITCH"
}