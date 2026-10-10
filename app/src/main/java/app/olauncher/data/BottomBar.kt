package app.olauncher.data

/**
 * Bottom icon bar on the home screen (search, messages and similar apps).
 * Slot persistence lives in [Prefs] as one serialized string per slot;
 * this object holds the pure, unit-testable logic: slot range, visibility
 * and which icon layer to use for tinting.
 */
object BottomBar {

    const val MAX_SLOTS = 5

    /** Which icon layer gets tinted with the home text color. */
    enum class IconSource {
        /** Android 13+ themed icon layer. */
        MONOCHROME,

        /** Full-color icon desaturated first, then tinted. */
        GRAYSCALE,
    }

    fun coerceSlotCount(count: Int): Int = count.coerceIn(0, MAX_SLOTS)

    /** Slots are 1-based; only slots within the configured bar count are shown. */
    fun isSlotVisible(barCount: Int, slot: Int): Boolean =
        slot in 1..coerceSlotCount(barCount)

    /**
     * [apiLevel] is a parameter (instead of Build.VERSION.SDK_INT) so the
     * decision stays unit-testable on the JVM.
     */
    fun decideIconSource(apiLevel: Int, hasMonochrome: Boolean): IconSource =
        if (apiLevel >= 33 && hasMonochrome) IconSource.MONOCHROME
        else IconSource.GRAYSCALE

    /**
     * One bar slot: package, activity class and user handle string, the same
     * triple home apps persist (work profile support included).
     */
    data class Slot(
        val packageName: String = "",
        val activityClassName: String? = null,
        val userString: String = "",
    ) {
        fun isEmpty(): Boolean = packageName.isBlank()

        fun serialize(): String =
            listOf(packageName, activityClassName.orEmpty(), userString).joinToString("|")

        companion object {
            fun parse(raw: String?): Slot {
                if (raw.isNullOrEmpty()) return Slot()
                val parts = raw.split("|")
                return Slot(
                    packageName = parts.getOrElse(0) { "" },
                    activityClassName = parts.getOrNull(1)?.ifEmpty { null },
                    userString = parts.getOrElse(2) { "" },
                )
            }
        }
    }
}
