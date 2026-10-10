package app.olauncher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.olauncher.data.Note
import app.olauncher.data.NoteRow
import app.olauncher.data.NoteSchedule
import app.olauncher.data.NotesDatabase
import app.olauncher.data.noteRows
import app.olauncher.data.reorderedNotes
import app.olauncher.helper.NoteAlerts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = NotesDatabase.get(application).noteDao()

    /** Bumped by [refreshPeriods]; the list is worked out again even when the database did not change. */
    private val periodTicks = MutableStateFlow(0)

    /**
     * The screen's rows ([noteRows]); they change also when only the day does (a weekly note going off its day). Null
     * until the first database read finishes, so the UI can tell "loading" from "empty".
     */
    val rows: StateFlow<List<NoteRow>?> = dao.observeAll()
        .combine(periodTicks) { notes, _ -> noteRows(notes, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { dao.insertLast(trimmed, System.currentTimeMillis()) }
    }

    fun toggleStrike(note: Note) {
        viewModelScope.launch {
            dao.toggleStrike(note.id, System.currentTimeMillis())?.let { NoteAlerts.onStrikeChanged(getApplication(), it) }
        }
    }

    /** Shows done daily and weekly notes active again once their period is over. */
    fun refreshPeriods() {
        periodTicks.value++
    }

    /** Sets, changes or ([schedule] null) removes the note's schedule and re-arms its alerts. */
    fun setSchedule(note: Note, schedule: NoteSchedule?) {
        viewModelScope.launch {
            dao.setSchedule(note.id, schedule, System.currentTimeMillis())
            if (schedule == null) NoteAlerts.cancel(getApplication(), note.id)
            NoteAlerts.rescheduleAll(getApplication())
        }
    }

    /** Persists the order and routine section shown in [rows] in one write. */
    fun saveOrder(rows: List<NoteRow>) {
        val changed = reorderedNotes(rows)
        if (changed.isEmpty()) return
        viewModelScope.launch { dao.setPlaces(changed, System.currentTimeMillis()) }
    }

    fun delete(note: Note) {
        viewModelScope.launch {
            dao.delete(note.id, System.currentTimeMillis())
            if (note.schedule != null) {
                NoteAlerts.cancel(getApplication(), note.id)
                NoteAlerts.rescheduleAll(getApplication())
            }
        }
    }
}
