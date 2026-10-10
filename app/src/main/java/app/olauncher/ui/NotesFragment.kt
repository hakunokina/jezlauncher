package app.olauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AlertType
import app.olauncher.data.Note
import app.olauncher.data.NoteRow
import app.olauncher.data.moveNoteRow
import app.olauncher.data.nextDayStart
import app.olauncher.databinding.FragmentNotesBinding
import app.olauncher.helper.NoteAlerts
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showMessageDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class NotesFragment : BaseFragment() {

    private val viewModel: NotesViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentNotesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: NotesAdapter

    /** Active note picked up by a long press; dropping it without moving asks to delete it instead. */
    private var dragNote: Note? = null
    private var dragMoved = false
    /** Database updates that arrived mid-drag, applied on drop so they don't yank the row away. */
    private var pendingRows: List<NoteRow>? = null
    /** The dragged note is over the routine area; latched on release, since the row then animates back. */
    private var overRoutineZone = false
    private var droppedOnRoutineZone = false
    /** Alert type of the schedule just saved, while the notification permission prompt is up. */
    private var askedAlert: AlertType? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val alert = askedAlert ?: return@registerForActivityResult
        askedAlert = null
        if (granted && _binding != null) askAlertPermissions(alert)
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        // The row's own long press decides between dragging and deleting
        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            makeMovementFlags(if (isActiveAt(viewHolder)) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0, 0)

        override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            !overRoutineZone && moveNoteRow(adapter.items, current.bindingAdapterPosition, target.bindingAdapterPosition) != null

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (dragNote == null || !isCurrentlyActive) return
            // Pulled but held back (a routine cannot leave the section): still a drag, not a press asking to delete
            if (abs(dY) > ViewConfiguration.get(recyclerView.context).scaledTouchSlop) dragMoved = true
            // The zone lies over the top of the list, so both share coordinates; the row's top edge reaching it counts
            val over = viewHolder.itemView.top + dY < binding.notesRoutineZone.bottom
            if (over == overRoutineZone) return
            overRoutineZone = over
            dragMoved = true
            binding.notesRoutineZone.alpha = if (over) 1f else ZONE_IDLE_ALPHA
            if (over) binding.notesRoutineZone.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        // No auto scroll while hovering the zone
        override fun interpolateOutOfBoundsScroll(
            recyclerView: RecyclerView, viewSize: Int, viewSizeOutOfBounds: Int, totalSize: Int, msSinceStartScroll: Long,
        ) = if (overRoutineZone) 0
        else super.interpolateOutOfBoundsScroll(recyclerView, viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll)

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) droppedOnRoutineZone = overRoutineZone
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val moved = adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            if (moved) dragMoved = true
            return moved
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val note = dragNote ?: return
            val onZone = droppedOnRoutineZone
            dragNote = null
            overRoutineZone = false
            droppedOnRoutineZone = false
            if (_binding == null) return
            binding.notesRoutineZone.isVisible = false
            adapter.forceSectionLine = false
            when {
                // Dropped on the routine area: the list goes back to its order from before the drag
                onZone -> {
                    (pendingRows ?: viewModel.rows.value)?.let(::showRows)
                    pendingRows = null
                    openSchedule(note)
                }
                dragMoved -> viewModel.saveOrder(adapter.items)
                else -> confirmDelete(note)
            }
            pendingRows?.let(::showRows)
            pendingRows = null
        }
    })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = NotesAdapter(
            onToggleStrike = viewModel::toggleStrike,
            onLongPress = ::onNoteLongPress,
            onDelete = ::confirmDelete,
            onMove = ::moveNote,
            onSchedule = ::openSchedule,
        )
        binding.notesRecycler.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.notesRecycler.adapter = adapter
        touchHelper.attachToRecyclerView(binding.notesRecycler)
        binding.noteSend.setOnClickListener { send() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bottom = max(
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            )
            v.updatePadding(bottom = bottom)
            insets
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rows.collect { rows ->
                    rows ?: return@collect
                    if (dragNote != null) pendingRows = rows else showRows(rows)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Done daily and weekly notes come back and weekly ones go off or on their days at local midnight:
                // check when the screen shows and at midnight
                while (true) {
                    viewModel.refreshPeriods()
                    val now = System.currentTimeMillis()
                    delay(nextDayStart(now) - now + 1_000)
                }
            }
        }
    }

    private fun showRows(rows: List<NoteRow>) {
        fun lastNoteId(rows: List<NoteRow>) = (rows.lastOrNull { it is NoteRow.Item } as? NoteRow.Item)?.note?.id
        val lastId = lastNoteId(adapter.items)
        val newLastId = lastNoteId(rows)
        binding.notesEmpty.isVisible = newLastId == null
        adapter.submit(rows)
        // Follow new notes at the bottom, but stay put after a reorder
        if (newLastId != null && newLastId != lastId) binding.notesRecycler.scrollToPosition(rows.lastIndex)
    }

    private fun isActiveAt(holder: RecyclerView.ViewHolder) =
        (adapter.items.getOrNull(holder.bindingAdapterPosition) as? NoteRow.Item)?.note?.isStruck == false

    private fun onNoteLongPress(holder: NotesAdapter.ViewHolder, note: Note) {
        if (note.isStruck) return confirmDelete(note)
        dragNote = note
        dragMoved = false
        holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        binding.notesRoutineZone.alpha = ZONE_IDLE_ALPHA
        binding.notesRoutineZone.isVisible = true
        // An unscheduled note can be dragged into the routine section or out of it, across this line
        adapter.forceSectionLine = note.schedule == null
        touchHelper.startDrag(holder)
    }

    private fun moveNote(from: Int, to: Int) {
        if (adapter.move(from, to)) viewModel.saveOrder(adapter.items)
    }

    override fun onPause() {
        binding.noteInput.hideKeyboard()
        super.onPause()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun send() {
        viewModel.add(binding.noteInput.text.toString())
        binding.noteInput.text?.clear()
    }

    private fun openSchedule(note: Note) {
        NoteScheduleDialog(this, note) { schedule ->
            viewModel.setSchedule(note, schedule)
            if (schedule != null) askAlertPermissions(schedule.alert)
        }.create().showRespectingStatusBar()
    }

    /**
     * Asks for what alerts need, one thing per save: notifications (Android 13+), exact alarms (off by default on
     * Android 14+; alerts still come without it, but late), then full screen for alarm and full-screen notes (Android 14+).
     * Exact alarms go through SCHEDULE_EXACT_ALARM, not USE_EXACT_ALARM: that one is reserved for alarm clock and
     * calendar apps, which a launcher is not.
     */
    @SuppressLint("InlinedApi") // canScheduleExact and canUseFullScreen only fail where those settings exist
    private fun askAlertPermissions(alert: AlertType) {
        val context = requireContext()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED -> {
                askedAlert = alert
                mainViewModel.isGrantingPermission = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            !NoteAlerts.canScheduleExact(context) -> openPermissionSettings(
                R.string.note_exact_alarm_title, R.string.note_exact_alarm_message, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
            )

            alert.fullScreen && !NoteAlerts.canUseFullScreen(context) -> openPermissionSettings(
                R.string.note_full_screen_title, R.string.note_full_screen_message, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
            )
        }
    }

    private fun openPermissionSettings(@StringRes title: Int, @StringRes message: Int, action: String) {
        requireContext().showMessageDialog(title = title, message = message, action = R.string.note_permission_settings) {
            // Coming back from settings lands on the notes again, not on home
            mainViewModel.isGrantingPermission = true
            startActivity(Intent(action, "package:${requireContext().packageName}".toUri()))
        }
    }

    private fun confirmDelete(note: Note) {
        requireContext().showMessageDialog(
            title = R.string.note_delete_title,
            message = R.string.note_delete_message,
            action = R.string.note_delete,
            onAction = { viewModel.delete(note) },
        )
    }

    private companion object {
        const val ZONE_IDLE_ALPHA = 0.6f
    }
}
