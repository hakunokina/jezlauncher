package app.olauncher.sync

import androidx.fragment.app.Fragment
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Scans the kiosk's pairing QR code with the camera (zxing, no Play services). Create it while the fragment is
 * created, as it registers for the activity result; [onResult] gets the scanned text, null when cancelled.
 */
class QrScanner(fragment: Fragment, onResult: (String?) -> Unit) {

    private val launcher = fragment.registerForActivityResult(ScanContract()) { onResult(it.contents) }

    fun launch(prompt: String) {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(prompt)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    companion object {
        const val AVAILABLE = true
    }
}
