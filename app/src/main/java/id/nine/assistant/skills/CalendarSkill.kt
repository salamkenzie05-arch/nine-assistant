package id.nine.assistant.skills

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Membaca dan menulis acara langsung ke kalender di HP (Samsung Calendar / Google Calendar memakai
 * penyimpanan yang sama: CalendarContract). Butuh izin READ_CALENDAR dan WRITE_CALENDAR.
 *
 * Setiap acara yang ditulis langsung dibaca ulang untuk memastikan benar-benar tersimpan.
 */
class CalendarSkill(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    data class Event(val id: Long, val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean)

    data class Cal(val id: Long, val name: String, val account: String, val accountType: String, val primary: Boolean) {
        val google: Boolean get() = accountType == "com.google"
        val label: String get() = if (name == account || account.isBlank()) name else "$name ($account)"
    }

    sealed class AddResult {
        data class Added(val eventId: Long, val calendar: Cal) : AddResult()
        object NoCalendar : AddResult()
        data class Failed(val reason: String) : AddResult()
    }

    /** Kalender yang bisa ditulisi, tersinkron, dan terlihat. */
    fun writableCalendars(): List<Cal> {
        val out = ArrayList<Cal>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.OWNER_ACCOUNT
        )
        resolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, CalendarContract.Calendars._ID + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(5)
                val visible = c.isNull(6) || c.getInt(6) == 1
                val synced = c.isNull(7) || c.getInt(7) == 1
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || !visible || !synced) continue
                val account = c.getString(2) ?: ""
                val owner = c.getString(8) ?: ""
                // Kalender grup (Classroom, dsb.) jarang jadi tujuan acara pribadi.
                if (owner.contains("@group.") ) continue
                out.add(
                    Cal(
                        id = c.getLong(0),
                        name = c.getString(1) ?: account.ifBlank { "Kalender" },
                        account = account,
                        accountType = c.getString(3) ?: "",
                        primary = !c.isNull(4) && c.getInt(4) == 1
                    )
                )
            }
        }
        return out
    }

    /** Banyaknya acara (60 hari ke belakang s.d. 60 hari ke depan) per kalender, untuk menebak kalender utama. */
    private fun activity(): Map<Long, Int> {
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - 60 * day)
        ContentUris.appendId(b, now + 60 * day)
        val counts = HashMap<Long, Int>()
        try {
            resolver.query(b.build(), arrayOf(CalendarContract.Instances.CALENDAR_ID), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    counts[id] = (counts[id] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            // abaikan
        }
        return counts
    }

    /**
     * Kalender tujuan: pilihan pengguna di Pengaturan bila ada; selain itu kalender utama akun yang paling aktif
     * (paling banyak acara), lalu kalender Google utama, lalu kalender lokal.
     */
    fun target(preferredId: Long): Cal? {
        val cals = writableCalendars()
        if (cals.isEmpty()) return null
        cals.firstOrNull { it.id == preferredId }?.let { return it }
        val act = activity()
        return cals.sortedWith(
            compareByDescending<Cal> { act[it.id] ?: 0 }
                .thenByDescending { it.primary && it.google }
                .thenByDescending { it.primary }
                .thenBy { it.id }
        ).first()
    }

    fun add(
        title: String,
        start: LocalDateTime,
        end: LocalDateTime?,
        allDay: Boolean,
        preferredCalendarId: Long,
        reminderMinutes: Int = 15
    ): AddResult {
        return try {
            val cal = target(preferredCalendarId) ?: return AddResult.NoCalendar
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, cal.id)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, "Dibuat oleh Nine")
                if (allDay) {
                    // Acara seharian harus dalam UTC tengah malam.
                    val s = start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                    put(CalendarContract.Events.DTSTART, s)
                    put(CalendarContract.Events.DTEND, s + 24L * 3600 * 1000)
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    val zone = ZoneId.systemDefault()
                    val e = end ?: start.plusHours(1)
                    put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, e.atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                put(CalendarContract.Events.HAS_ALARM, if (!allDay && reminderMinutes >= 0) 1 else 0)
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return AddResult.Failed("insert")
            val id = ContentUris.parseId(uri)
            if (id <= 0 || !exists(id)) return AddResult.Failed("verify")
            if (!allDay && reminderMinutes >= 0) {
                val r = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                try {
                    resolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
                } catch (e: Exception) {
                    // acara tetap tersimpan meski pengingat gagal
                }
            }
            AddResult.Added(id, cal)
        } catch (e: SecurityException) {
            AddResult.Failed("permission")
        } catch (e: Exception) {
            AddResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Pastikan acara benar-benar ada (dan tidak terhapus) di penyimpanan kalender. */
    fun exists(eventId: Long): Boolean = try {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DELETED),
            null, null, null
        )?.use { c -> c.moveToFirst() && (c.isNull(1) || c.getInt(1) == 0) } ?: false
    } catch (e: Exception) {
        false
    }

    fun get(eventId: Long): Event? = try {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(
                CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY, CalendarContract.Events.DELETED
            ),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst() || (!c.isNull(4) && c.getInt(4) == 1)) return@use null
            val allDay = c.getInt(3) == 1
            val z: ZoneId = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
            val s = Instant.ofEpochMilli(c.getLong(1)).atZone(z).toLocalDateTime()
            val e = if (c.isNull(2)) s.plusHours(1) else Instant.ofEpochMilli(c.getLong(2)).atZone(z).toLocalDateTime()
            Event(eventId, c.getString(0) ?: "", s, e, allDay)
        }
    } catch (e: Exception) {
        null
    }

    /** Pindahkan acara ke waktu baru (durasi dipertahankan bila [newEnd] null). */
    fun move(eventId: Long, newStart: LocalDateTime, newEnd: LocalDateTime?): Boolean {
        val old = get(eventId) ?: return false
        val zone = ZoneId.systemDefault()
        val duration = java.time.Duration.between(old.start, old.end).let { if (it.isNegative || it.isZero) java.time.Duration.ofHours(1) else it }
        val end = newEnd ?: newStart.plus(duration)
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, newStart.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return try {
            resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun delete(eventId: Long): Boolean = try {
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) > 0
    } catch (e: Exception) {
        false
    }

    /** Intent cadangan: buka layar "acara baru" di aplikasi Kalender dengan data terisi. */
    fun insertIntent(title: String, start: LocalDateTime, end: LocalDateTime?, allDay: Boolean): Intent {
        val zone = ZoneId.systemDefault()
        val s = start.atZone(zone).toInstant().toEpochMilli()
        val e = (end ?: start.plusHours(1)).atZone(zone).toInstant().toEpochMilli()
        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        }
    }

    /** Buka acara di aplikasi Kalender. */
    fun viewIntent(eventId: Long): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))

    /** Acara pada tanggal tertentu (termasuk acara berulang), urut waktu mulai. */
    fun eventsOn(date: LocalDate): List<Event> {
        val zone = ZoneId.systemDefault()
        val begin = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, begin)
        ContentUris.appendId(builder, end)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.VISIBLE,
            CalendarContract.Instances.EVENT_ID
        )
        val out = ArrayList<Event>()
        resolver.query(builder.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(4) && c.getInt(4) != 1) continue
                val allDay = c.getInt(3) == 1
                val z: ZoneId = if (allDay) ZoneOffset.UTC else zone
                val s = Instant.ofEpochMilli(c.getLong(1)).atZone(z).toLocalDateTime()
                val e = Instant.ofEpochMilli(c.getLong(2)).atZone(z).toLocalDateTime()
                out.add(Event(c.getLong(5), c.getString(0)?.ifBlank { null } ?: "(tanpa judul)", s, e, allDay))
            }
        }
        return out
    }
}
