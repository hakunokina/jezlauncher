package app.olauncher.sync

import app.olauncher.data.AlertType
import app.olauncher.data.Note
import app.olauncher.data.NoteSchedule
import app.olauncher.data.ScheduleType
import app.olauncher.data.asOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * A list item as the kiosk's sync API carries it. Times are epoch millis here and ISO-8601 instants on the wire;
 * [meta] is the phone's own data (schedule, pin) that the kiosk keeps without reading it.
 */
data class SyncItem(
    val id: String,
    val text: String,
    val checked: Boolean,
    val checkedAt: Long?,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val meta: String?,
)

data class KioskList(val id: String, val name: String)

/** A pull: the items changed after the rev asked for, and the kiosk's rev now. */
data class ItemsPage(val items: List<SyncItem>, val rev: Long)

/** The kiosk's answer to a push: items it kept its own newer copy of, and ids it ignored (they live in another list). */
data class PushResult(val rev: Long, val rejected: List<SyncItem>, val ignored: List<String>)

fun newSyncId(): String = UUID.randomUUID().toString()

/**
 * The note as the kiosk gets it at [now]: checked as it shows then ([asOf]), so a daily or weekly note done in an
 * earlier period goes unchecked; schedule and pin ride along in [SyncItem.meta].
 */
fun Note.toSyncItem(now: Long, zone: TimeZone = TimeZone.getDefault()): SyncItem {
    val shown = asOf(now, zone)
    return SyncItem(
        id = syncId,
        text = text.take(MAX_TEXT),
        checked = shown.isStruck,
        checkedAt = shown.strikedAt,
        sortOrder = position.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        meta = noteMeta(schedule, pinned),
    )
}

/**
 * The kiosk's [SyncItem] as a note, over [local] (the phone's copy, null for a new one); it waits for nothing. An item
 * without meta keeps the phone's schedule and pin: the kiosk keeps the meta through its own edits, so none means it
 * never had it, not that the schedule went.
 */
fun SyncItem.toNote(local: Note?): Note {
    val (schedule, pinned) = if (meta == null && local != null) local.schedule to local.pinned else parseNoteMeta(meta)
    return (local ?: Note(text = text, createdAt = createdAt)).copy(
        text = text,
        createdAt = createdAt,
        strikedAt = if (checked) checkedAt ?: updatedAt else null,
        position = sortOrder.toLong(),
        schedule = schedule,
        pinned = pinned,
        syncId = id,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        dirty = false,
    )
}

/** Schedule and routine-section pin for [SyncItem.meta]; null for a plain note. */
fun noteMeta(schedule: NoteSchedule?, pinned: Boolean): String? {
    if (schedule == null && !pinned) return null
    val json = JSONObject().put("v", 1).put("pinned", pinned)
    if (schedule != null) {
        json.put(
            "schedule", JSONObject()
                .put("type", schedule.type.name)
                .put("minuteOfDay", schedule.minuteOfDay ?: JSONObject.NULL)
                .put("days", schedule.days ?: JSONObject.NULL)
                .put("at", schedule.at ?: JSONObject.NULL)
                .put("alert", schedule.alert.name)
                .put("leadMinutes", schedule.leadMinutes)
        )
    }
    return json.toString()
}

/** Back from [noteMeta]: a plain, unpinned note for null or anything unreadable. */
fun parseNoteMeta(meta: String?): Pair<NoteSchedule?, Boolean> {
    val json = try {
        JSONObject(meta ?: return null to false)
    } catch (e: Exception) {
        return null to false
    }
    val pinned = json.opt("pinned") as? Boolean ?: false
    val schedule = json.optJSONObject("schedule")?.let { s ->
        val type = ScheduleType.entries.firstOrNull { it.name == s.optString("type") } ?: return@let null
        NoteSchedule(
            type = type,
            minuteOfDay = s.numberOrNull("minuteOfDay")?.toInt(),
            days = s.numberOrNull("days")?.toInt(),
            at = s.numberOrNull("at")?.toLong(),
            alert = AlertType.entries.firstOrNull { it.name == s.optString("alert") } ?: AlertType.NOTIFICATION,
            leadMinutes = s.numberOrNull("leadMinutes")?.toInt() ?: NoteSchedule.DEFAULT_LEAD_MINUTES,
        )
    }
    return schedule to pinned
}

/** `{"lists":[{id,name,color}]}`; null when the body is not that. */
fun parseKioskLists(body: String): List<KioskList>? {
    val array = try {
        JSONObject(body).getJSONArray("lists")
    } catch (e: Exception) {
        return null
    }
    return (0 until array.length()).map { i ->
        val list = array.optJSONObject(i) ?: return null
        KioskList(list.stringOrNull("id") ?: return null, list.stringOrNull("name").orEmpty())
    }
}

/** `{"items":[Item],"rev":N}`; null when the body is not that. */
fun parseItemsPage(body: String): ItemsPage? {
    return try {
        val json = JSONObject(body)
        ItemsPage(parseItems(json.getJSONArray("items")) ?: return null, json.getLong("rev"))
    } catch (e: Exception) {
        null
    }
}

/** `{"rev":N,"rejected":[Item],"ignored":[id]}` (ignored is optional); null when the body is not that. */
fun parsePushResult(body: String): PushResult? {
    return try {
        val json = JSONObject(body)
        val ignored = json.optJSONArray("ignored")
        PushResult(
            rev = json.getLong("rev"),
            rejected = parseItems(json.optJSONArray("rejected") ?: JSONArray()) ?: return null,
            ignored = (0 until (ignored?.length() ?: 0)).mapNotNull { ignored?.opt(it) as? String },
        )
    } catch (e: Exception) {
        null
    }
}

/** `{"items":[...]}`: every field is sent, nulls as JSON null (the kiosk refuses missing ones). */
fun pushBody(items: List<SyncItem>): String =
    JSONObject().put("items", JSONArray().apply { items.forEach { put(itemJson(it)) } }).toString()

/**
 * [items] in pushes the kiosk takes: at most [maxItems] each and about [maxBytes] of JSON (it refuses more than 1000
 * items or 1 MB).
 */
fun pushBatches(items: List<SyncItem>, maxItems: Int = 500, maxBytes: Int = 512_000): List<List<SyncItem>> {
    val batches = mutableListOf<MutableList<SyncItem>>()
    var size = 0
    for (item in items) {
        val itemSize = itemJson(item).toString().toByteArray().size + 1
        val batch = batches.lastOrNull()
        if (batch == null || batch.size >= maxItems || size + itemSize > maxBytes) {
            batches += mutableListOf(item)
            size = itemSize
        } else {
            batch += item
            size += itemSize
        }
    }
    return batches
}

private fun itemJson(item: SyncItem) = JSONObject()
    .put("id", item.id)
    .put("text", item.text)
    .put("checked", item.checked)
    .put("checkedAt", item.checkedAt?.let(::formatIsoInstant) ?: JSONObject.NULL)
    .put("sortOrder", item.sortOrder)
    .put("createdAt", formatIsoInstant(item.createdAt))
    .put("updatedAt", formatIsoInstant(item.updatedAt))
    .put("deletedAt", item.deletedAt?.let(::formatIsoInstant) ?: JSONObject.NULL)
    .put("meta", item.meta ?: JSONObject.NULL)

/** Null when an item has no id or no readable updatedAt; such an item cannot be merged. */
private fun parseItems(array: JSONArray): List<SyncItem>? {
    return (0 until array.length()).map { parseItem(array.getJSONObject(it)) ?: return null }
}

private fun parseItem(json: JSONObject): SyncItem? {
    val updatedAt = json.stringOrNull("updatedAt")?.let(::parseIsoInstant) ?: return null
    return SyncItem(
        id = json.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null,
        text = json.stringOrNull("text").orEmpty(),
        checked = json.opt("checked") as? Boolean ?: false,
        checkedAt = json.stringOrNull("checkedAt")?.let(::parseIsoInstant),
        sortOrder = json.numberOrNull("sortOrder")?.toInt() ?: 0,
        createdAt = json.stringOrNull("createdAt")?.let(::parseIsoInstant) ?: updatedAt,
        updatedAt = updatedAt,
        deletedAt = json.stringOrNull("deletedAt")?.let(::parseIsoInstant),
        meta = json.stringOrNull("meta"),
    )
}

/** `2026-10-10T08:30:00.000Z`, what JavaScript's toISOString gives. */
fun formatIsoInstant(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = UTC }.format(millis)

/** An ISO-8601 instant with `Z` or an offset, seconds and an optional fraction; null for anything else. */
fun parseIsoInstant(text: String): Long? {
    val match = ISO_INSTANT.matchEntire(text.trim()) ?: return null
    val (year, month, day, hour, minute, second, fraction, zone) = match.destructured
    val local = try {
        GregorianCalendar(UTC).apply {
            clear()
            isLenient = false
            set(year.toInt(), month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
        }.timeInMillis
    } catch (e: IllegalArgumentException) {
        return null
    }
    val millis = fraction.take(3).padEnd(3, '0').toInt()
    val offset = if (zone.equals("Z", ignoreCase = true)) 0 else {
        val digits = zone.drop(1).replace(":", "")
        val minutes = digits.take(2).toInt() * 60 + digits.drop(2).toInt()
        (if (zone[0] == '-') -minutes else minutes) * 60_000L
    }
    return local + millis - offset
}

private fun JSONObject.stringOrNull(name: String) = opt(name).takeIf { it != JSONObject.NULL } as? String

private fun JSONObject.numberOrNull(name: String) = opt(name).takeIf { it != JSONObject.NULL } as? Number

private val UTC = TimeZone.getTimeZone("UTC")
private val ISO_INSTANT = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})""", RegexOption.IGNORE_CASE)

/** The kiosk takes at most this much text per item. */
private const val MAX_TEXT = 10_000
