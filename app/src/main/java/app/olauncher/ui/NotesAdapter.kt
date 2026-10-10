package app.olauncher.ui

import android.annotation.SuppressLint
import android.graphics.Paint
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AlertType
import app.olauncher.data.Note
import app.olauncher.data.NoteRow
import app.olauncher.data.moveNoteRow
import app.olauncher.databinding.AdapterNoteBinding
import app.olauncher.databinding.AdapterNoteLineBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.nextDayLabel
import app.olauncher.helper.scheduleLabel

/**
 * Not a ListAdapter: dragging has to move rows synchronously, which the async differ cannot do.
 * Lists are small, so diffing on the main thread is fine.
 */
class NotesAdapter(
    private val onToggleStrike: (Note) -> Unit,
    private val onLongPress: (ViewHolder, Note) -> Unit,
    private val onDelete: (Note) -> Unit,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSchedule: (Note) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<NoteRow> = emptyList()
        private set

    /** Which lines show; worked out on submit, so they hold still while a note is dragged across them. */
    private var doneLineShown = false
    private var sectionLineShown = false

    /** Shows the section line also with nothing to separate: while a note is dragged it is where the note crosses. */
    var forceSectionLine = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemChanged(items.indexOf(NoteRow.SectionLine))
        }

    /** Takes rows from [app.olauncher.data.noteRows]; a line shows only between two groups that both have notes. */
    fun submit(rows: List<NoteRow>) {
        val old = items
        items = rows
        val doneLine = rows.indexOf(NoteRow.DoneLine)
        val sectionLine = rows.indexOf(NoteRow.SectionLine)
        val done = doneLine > 0
        val section = sectionLine - doneLine > 1
        val other = sectionLine < rows.lastIndex
        doneLineShown = done && section
        // Around an empty section the two lines become one
        sectionLineShown = other && (done || section)
        DiffUtil.calculateDiff(DiffCallback(old, rows)).dispatchUpdatesTo(this)
        // Move actions depend on the neighbours and lines on the groups, which DiffUtil does not rebind
        notifyItemRangeChanged(0, rows.size, PAYLOAD_ACTIONS)
    }

    /** Moves a row while dragging; false when [moveNoteRow] does not allow it. */
    fun move(from: Int, to: Int): Boolean {
        items = moveNoteRow(items, from, to) ?: return false
        notifyItemMoved(from, to)
        return true
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = if (items[position] is NoteRow.Item) VIEW_NOTE else VIEW_LINE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_NOTE) ViewHolder(AdapterNoteBinding.inflate(inflater, parent, false))
        else LineHolder(AdapterNoteLineBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = onBindViewHolder(holder, position, emptyList())

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        val row = items[position]
        when {
            holder is LineHolder -> holder.bind(row)
            payloads.isNotEmpty() && payloads.all { it == PAYLOAD_ACTIONS } -> (holder as ViewHolder).bindMoveActions(position)
            else -> (holder as ViewHolder).bind(row as NoteRow.Item, position)
        }
    }

    inner class LineHolder(private val binding: AdapterNoteLineBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: NoteRow) {
            binding.noteLine.isVisible = if (row == NoteRow.DoneLine) doneLineShown else sectionLineShown || forceSectionLine
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class ViewHolder(private val binding: AdapterNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        private var note: Note? = null
        private var moveUpActionId = View.NO_ID
        private var moveDownActionId = View.NO_ID
        private val alarmIcon = ContextCompat.getDrawable(binding.root.context, R.drawable.ic_alarm)?.apply {
            setBounds(0, 0, 14.dpToPx(), 14.dpToPx())
        }
        private val fullScreenIcon = ContextCompat.getDrawable(binding.root.context, R.drawable.ic_full_screen)?.apply {
            setBounds(0, 0, 14.dpToPx(), 14.dpToPx())
        }

        private val gestureDetector = GestureDetector(binding.root.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                note?.let(onToggleStrike)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                note?.let { onLongPress(this@ViewHolder, it) }
            }
        })

        init {
            val context = binding.root.context
            binding.noteText.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
            ViewCompat.addAccessibilityAction(binding.noteText, context.getString(R.string.note_toggle_strike)) { _, _ ->
                note?.let(onToggleStrike)
                true
            }
            ViewCompat.addAccessibilityAction(binding.noteText, context.getString(R.string.note_delete)) { _, _ ->
                note?.let(onDelete)
                true
            }
            // Dragging onto the routine area is not reachable without touch
            ViewCompat.addAccessibilityAction(binding.noteText, context.getString(R.string.note_schedule)) { _, _ ->
                note?.let(onSchedule)
                true
            }
            binding.noteSchedule.setOnClickListener { note?.let(onSchedule) }
            TextViewCompat.setCompoundDrawableTintList(binding.noteSchedule, binding.noteSchedule.textColors)
        }

        fun bind(row: NoteRow.Item, position: Int) {
            val note = row.note
            this.note = note
            binding.noteText.text = note.text
            binding.noteText.paintFlags = if (note.isStruck)
                binding.noteText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else
                binding.noteText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            binding.noteText.alpha = when {
                note.isStruck -> 0.5f
                // Not its day: neither to do nor done
                row.offDay -> OFF_DAY_ALPHA
                else -> 1f
            }
            val schedule = note.schedule
            binding.noteSchedule.isVisible = schedule != null
            if (schedule != null) {
                val context = binding.root.context
                binding.noteSchedule.text =
                    if (row.offDay) context.nextDayLabel(schedule, System.currentTimeMillis()) else context.scheduleLabel(schedule)
                binding.noteSchedule.setCompoundDrawablesRelative(
                    when (schedule.alert) {
                        AlertType.NOTIFICATION -> null
                        AlertType.FULL_SCREEN -> fullScreenIcon
                        AlertType.ALARM -> alarmIcon
                    },
                    null, null, null
                )
            }
            bindMoveActions(position)
        }

        fun bindMoveActions(position: Int) {
            val view = binding.noteText
            ViewCompat.removeAccessibilityAction(view, moveUpActionId)
            ViewCompat.removeAccessibilityAction(view, moveDownActionId)
            moveUpActionId = addMoveAction(position, position - 1, R.string.note_move_up)
            moveDownActionId = addMoveAction(position, position + 1, R.string.note_move_down)
        }

        private fun addMoveAction(from: Int, to: Int, label: Int): Int {
            if (moveNoteRow(items, from, to) == null) return View.NO_ID
            return ViewCompat.addAccessibilityAction(binding.noteText, binding.root.context.getString(label)) { _, _ ->
                val current = bindingAdapterPosition
                if (current == RecyclerView.NO_POSITION) return@addAccessibilityAction false
                onMove(current, current + (to - from))
                true
            }
        }
    }

    private class DiffCallback(private val old: List<NoteRow>, private val new: List<NoteRow>) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
            val o = old[oldPosition]
            val n = new[newPosition]
            return if (o is NoteRow.Item && n is NoteRow.Item) o.note.id == n.note.id else o == n
        }
        override fun areContentsTheSame(oldPosition: Int, newPosition: Int) = old[oldPosition] == new[newPosition]
    }

    private companion object {
        const val PAYLOAD_ACTIONS = "actions"
        /** How faint a weekly note shows off its days. */
        const val OFF_DAY_ALPHA = 0.3f
        const val VIEW_NOTE = 0
        const val VIEW_LINE = 1
    }
}
