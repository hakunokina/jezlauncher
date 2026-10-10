package app.olauncher.sync

import androidx.fragment.app.Fragment

/** Builds without kiosk sync have no scanner (no camera permission, no zxing); see the kiosk source set. */
class QrScanner(@Suppress("UNUSED_PARAMETER") fragment: Fragment, private val onResult: (String?) -> Unit) {

    fun launch(@Suppress("UNUSED_PARAMETER") prompt: String) = onResult(null)

    companion object {
        const val AVAILABLE = false
    }
}
