package app.olauncher.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.Menu
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.databinding.DialogWallpaperUrlBinding
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.createDialog
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showToast
import app.olauncher.sync.KioskApi
import app.olauncher.sync.KioskCall
import app.olauncher.sync.KioskEndpoint
import app.olauncher.sync.KioskList
import app.olauncher.sync.KioskSync
import app.olauncher.sync.KioskSyncPrefs
import app.olauncher.sync.QrScanner
import app.olauncher.sync.SyncProblem
import kotlinx.coroutines.launch

/**
 * The "Kiosk sync" row of the settings, in kiosk sync builds only: pair by the kiosk's QR code or link and choose the
 * list, later sync now, change the list or unpair; under the row, how the last sync went. [scanner] belongs to the
 * fragment (it registers for a result); [beforeScan] keeps the settings open while the camera shows.
 */
class KioskSyncSettings(
    private val fragment: Fragment,
    private val binding: FragmentSettingsBinding,
    private val scanner: QrScanner,
    private val showDialog: (OlDialog) -> Unit,
    private val beforeScan: () -> Unit,
) {
    private val context: Context = fragment.requireContext()
    private val prefs = KioskSyncPrefs(context)
    private val listener = prefs.onChange { fragment.view?.post { show() } }

    init {
        binding.kioskSyncRow.isVisible = true
        binding.kioskSyncRow.setOnClickListener { onClick() }
        binding.kioskSync.setOnClickListener { onClick() }
        show()
    }

    fun release() = prefs.removeListener(listener)

    /** A scanned or pasted pairing link: checks it, then lets the user choose the list. */
    fun connect(link: String?) {
        if (link.isNullOrBlank()) return
        val endpoint = KioskEndpoint.fromLink(link)
        if (endpoint == null) {
            context.showToast(R.string.kiosk_link_invalid)
            return
        }
        chooseList(endpoint)
    }

    private fun show() {
        val pairing = KioskSync.pairing(context).value
        binding.kioskSync.text = when {
            pairing == null -> context.getString(R.string.off)
            pairing.listName.isNotBlank() -> pairing.listName
            else -> context.getString(R.string.on)
        }
        binding.kioskSyncStatus.isVisible = pairing != null
        binding.kioskSyncStatus.text = when (prefs.problem) {
            SyncProblem.UNREACHABLE -> context.getString(R.string.kiosk_status_unreachable)
            SyncProblem.UNAUTHORIZED -> context.getString(R.string.kiosk_status_unauthorized)
            SyncProblem.LIST_MISSING -> context.getString(R.string.kiosk_status_list_missing)
            SyncProblem.FAILED -> context.getString(R.string.kiosk_status_failed)
            null -> prefs.lastSyncAt.takeIf { it > 0 }?.let {
                context.getString(
                    R.string.kiosk_status_synced,
                    DateUtils.formatSameDayTime(it, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                )
            } ?: context.getString(R.string.kiosk_status_waiting)
        }
    }

    private fun onClick() {
        val pairing = KioskSync.pairing(context).value
        if (pairing == null) {
            showPairDialog()
            return
        }
        // A token the kiosk refuses needs a new pairing; anything else keeps the kiosk
        if (prefs.problem == SyncProblem.UNAUTHORIZED) {
            showPairDialog()
            return
        }
        binding.kioskSync.showPopupMenu(configure = { menu ->
            menu.add(Menu.NONE, MENU_SYNC, 0, R.string.kiosk_sync_now)
            menu.add(Menu.NONE, MENU_LIST, 1, R.string.kiosk_change_list)
            menu.add(Menu.NONE, MENU_UNPAIR, 2, R.string.kiosk_unpair)
        }) { item ->
            when (item.itemId) {
                MENU_SYNC -> KioskSync.requestSync(context, restart = true)
                MENU_LIST -> chooseList(pairing.endpoint)
                MENU_UNPAIR -> confirmUnpair()
            }
        }
    }

    private fun showPairDialog() {
        var input: EditText? = null
        val dialog = context.createDialog(
            title = R.string.kiosk_sync,
            action = R.string.kiosk_connect,
            message = R.string.kiosk_pair_message,
            neutral = if (QrScanner.AVAILABLE) R.string.kiosk_scan else 0,
            onNeutral = {
                beforeScan()
                scanner.launch(context.getString(R.string.kiosk_scan_prompt))
            },
            onAction = { connect(input?.text?.toString()) },
        ) { container ->
            DialogWallpaperUrlBinding.inflate(fragment.layoutInflater, container, false).root.also {
                it.setHint(R.string.kiosk_link_hint)
                input = it
            }
        }
        showDialog(dialog)
    }

    private fun chooseList(endpoint: KioskEndpoint) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            when (val call = KioskApi(endpoint).lists()) {
                is KioskCall.Ok -> if (call.value.isEmpty()) context.showToast(R.string.kiosk_no_lists) else showLists(endpoint, call.value)
                KioskCall.Unreachable -> context.showToast(R.string.kiosk_unreachable)
                KioskCall.Unauthorized -> context.showToast(R.string.kiosk_refused)
                KioskCall.ListMissing, is KioskCall.Failed -> context.showToast(R.string.kiosk_failed)
            }
        }
    }

    private fun showLists(endpoint: KioskEndpoint, lists: List<KioskList>) {
        binding.kioskSync.showPopupMenu(configure = { menu ->
            lists.forEachIndexed { i, list -> menu.add(Menu.NONE, i, i, list.name.ifBlank { list.id }) }
        }) { item ->
            val list = lists[item.itemId]
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                KioskSync.pair(context, endpoint, list)
                show()
            }
        }
    }

    private fun confirmUnpair() {
        showDialog(
            context.createDialog(
                title = R.string.kiosk_unpair,
                action = R.string.kiosk_unpair,
                message = R.string.kiosk_unpair_message,
                onAction = {
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        KioskSync.unpair(context)
                        show()
                    }
                },
            )
        )
    }

    private companion object {
        const val MENU_SYNC = 1
        const val MENU_LIST = 2
        const val MENU_UNPAIR = 3
    }
}
