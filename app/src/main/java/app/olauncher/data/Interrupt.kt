package app.olauncher.data

/**
 * Pure decision logic for the interrupt pause shown before selected apps open.
 * Selection and bypass timestamps are stored as string sets in [Prefs]
 * (same "package|userHandle" key format as hidden apps).
 */
object Interrupt {

    /** The pause counts down before "Open" becomes tappable. */
    const val COUNTDOWN_MILLIS = 5000L

    /** Opening the app once skips the pause for this long. */
    const val BYPASS_MILLIS = 10 * 60 * 1000L

    fun key(packageName: String, userString: String): String =
        packageName + "|" + userString

    fun toggleSelection(selected: Set<String>, key: String): Set<String> =
        if (key in selected) selected - key else selected + key

    fun shouldInterrupt(
        selected: Set<String>,
        bypasses: Map<String, Long>,
        key: String,
        nowMillis: Long,
    ): Boolean {
        if (key !in selected) return false
        val lastOpen = bypasses[key] ?: return true
        return nowMillis - lastOpen >= BYPASS_MILLIS
    }

    /** Drops expired entries and stamps [key] as just opened. */
    fun recordBypass(
        bypasses: Map<String, Long>,
        key: String,
        nowMillis: Long,
        expiryMillis: Long = BYPASS_MILLIS,
    ): Map<String, Long> {
        val kept = bypasses.filterValues { nowMillis - it < expiryMillis }.toMutableMap()
        kept[key] = nowMillis
        return kept
    }

    // Stored as "package|userHandle|timestampMillis"; the key itself contains one '|',
    // so only the last '|' separates the timestamp.

    fun parseBypasses(entries: Set<String>): Map<String, Long> =
        entries.mapNotNull { entry ->
            val separator = entry.lastIndexOf('|')
            if (separator <= 0) return@mapNotNull null
            val timestamp = entry.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
            entry.substring(0, separator) to timestamp
        }.toMap()

    fun serializeBypasses(bypasses: Map<String, Long>): Set<String> =
        bypasses.map { it.key + "|" + it.value }.toSet()
}
