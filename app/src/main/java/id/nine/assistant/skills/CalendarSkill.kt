package id.nine.assistant.skills

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Membaca dan menulis acara langsung ke kalender di HP (Samsung Calendar / Google Calendar memakai
 * penyimpanan yang sama: CalendarContract). Butuh izin READ_CALENDAR dan WRITE_CALENDAR.
 */
class CalendarSkill(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    data class Event(val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean)

    sealed class AddResult {
        data class Added(val calendarName: String) : AddResult()
        object NoCalendar : AddResult()
        data class Failed(val reason: String) : AddResult()
    }

    private data class Cal(val id: Long, val name: String, val primary: Boolean, val google: Boolean)

    /** Kalender yang bisa ditulisi, yang paling cocok lebih dulu. */
    private fun writableCalendars(): List<Cal> {
        val out = ArrayList<Cal>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        resolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(4)
                val visible = c.getInt(5) == 1
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || !visible) continue
                out.add(
                    Cal(
                        c.getLong(0),
                        c.getString(1) ?: "Kalender",
                        c.getInt(2) == 1,
                        (c.getString(3) ?: "") == "com.google"
                    )
                )
            }
        }
        return out.sortedWith(compareByDescending<Cal> { it.primary }.thenByDescending { it.google })
    }

    fun add(title: String, start: LocalDateTime, end: LocalDateTime?, allDay: Boolean, reminderMinutes: Int = 15): AddResult {
        return try {
            val cal = writableCalendars().firstOrNull() ?: return AddResult.NoCalendar
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, cal.id)
                put(CalendarContract.Events.TITLE, title)
                if (allDay) {
                    // Acara seharian harus dalam UTC tengah malam.
                    val d = start.toLocalDate()
                    val s = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
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
                put(CalendarContract.Events.HAS_ALARM, if (allDay) 0 else 1)
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return AddResult.Failed("insert")
            if (!allDay && reminderMinutes >= 0) {
                val r = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                try {
                    resolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
                } catch (e: Exception) {
                    // acara tetap tersimpan meski pengingat gagal
                }
            }
            AddResult.Added(cal.name)
        } catch (e: SecurityException) {
            AddResult.Failed("permission")
        } catch (e: Exception) {
            AddResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

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
            CalendarContract.Instances.VISIBLE
        )
        val out = ArrayList<Event>()
        resolver.query(builder.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(4) != 1) continue
                val allDay = c.getInt(3) == 1
                val z = if (allDay) ZoneOffset.UTC else zone
                val s = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(z).toLocalDateTime()
                val e = java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(z).toLocalDateTime()
                out.add(Event(c.getString(0)?.ifBlank { null } ?: "(tanpa judul)", s, e, allDay))
            }
        }
        return out
    }
}
