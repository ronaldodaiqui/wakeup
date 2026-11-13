package app.wakeup

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class Mission(val title: String) {
    NONE("None"),
    MATH("Math"),
    TYPING("Typing"),
    SHAKE("Shake"),
    MEMORY("Memory"),
    STEPS("Steps"),
    BARCODE("Barcode");

    /** Choices for "how much" of the mission, shown as chips. */
    val amounts: List<Int>
        get() = when (this) {
            MATH -> listOf(1, 3, 5)
            TYPING -> listOf(1, 2, 3)
            SHAKE -> listOf(20, 40, 70)
            MEMORY -> listOf(1, 2, 3)
            STEPS -> listOf(20, 50, 100)
            else -> emptyList()
        }

    fun amountLabel(n: Int): String = when (this) {
        MATH -> if (n == 1) "1 problem" else "$n problems"
        TYPING -> if (n == 1) "1 phrase" else "$n phrases"
        SHAKE -> "$n shakes"
        MEMORY -> if (n == 1) "1 round" else "$n rounds"
        STEPS -> "$n steps"
        else -> ""
    }

    /** Difficulty names, if this mission has difficulty. */
    val levels: List<String>
        get() = when (this) {
            MATH -> listOf("Easy", "Normal", "Hard", "Brutal")
            MEMORY -> listOf("Easy", "Normal", "Hard")
            else -> emptyList()
        }

    val defaultAmount: Int get() = amounts.getOrElse(1) { 0 }
}

data class Alarm(
    val id: Int = 0,
    val hour: Int = 7,
    val minute: Int = 0,
    /** Bit (dayOfWeek.value - 1) set for each repeat day. 0 = rings once. */
    val days: Int = 0,
    val enabled: Boolean = true,
    val label: String = "",
    val mission: Mission = Mission.MATH,
    val missionLevel: Int = 1,
    val missionAmount: Int = 3,
    /** Registered barcode value for the barcode mission. */
    val barcode: String = "",
    /** Ringtone uri, or "" for the phone's default alarm sound. */
    val sound: String = "",
    val vibrate: Boolean = true,
    /** Snoozes allowed: 0 = none, -1 = unlimited. */
    val snoozeLimit: Int = 2,
    val snoozeMinutes: Int = 5,
    val wakeCheck: Boolean = false,
) {
    val repeats: Boolean get() = days != 0

    fun hasDay(d: DayOfWeek) = days and (1 shl (d.value - 1)) != 0

    fun withDay(d: DayOfWeek, on: Boolean): Alarm =
        copy(days = if (on) days or (1 shl (d.value - 1)) else days and (1 shl (d.value - 1)).inv())

    /** Next time this alarm rings, strictly after [from]. */
    fun nextTrigger(from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val zone = from.zone
        var date: LocalDate = from.toLocalDate()
        for (i in 0..8) {
            val candidate = ZonedDateTime.of(LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)), zone)
            if (candidate.isAfter(from) && (!repeats || hasDay(date.dayOfWeek))) return candidate
            date = date.plusDays(1)
        }
        return ZonedDateTime.of(LocalDateTime.of(from.toLocalDate().plusDays(1), java.time.LocalTime.of(hour, minute)), zone)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("hour", hour).put("minute", minute).put("days", days)
        .put("enabled", enabled).put("label", label).put("mission", mission.name)
        .put("missionLevel", missionLevel).put("missionAmount", missionAmount)
        .put("barcode", barcode).put("sound", sound).put("vibrate", vibrate)
        .put("snoozeLimit", snoozeLimit).put("snoozeMinutes", snoozeMinutes)
        .put("wakeCheck", wakeCheck)

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.optInt("id"),
            hour = o.optInt("hour", 7),
            minute = o.optInt("minute"),
            days = o.optInt("days"),
            enabled = o.optBoolean("enabled", true),
            label = o.optString("label"),
            mission = runCatching { Mission.valueOf(o.optString("mission")) }.getOrDefault(Mission.MATH),
            missionLevel = o.optInt("missionLevel", 1),
            missionAmount = o.optInt("missionAmount", 3),
            barcode = o.optString("barcode"),
            sound = o.optString("sound"),
            vibrate = o.optBoolean("vibrate", true),
            snoozeLimit = o.optInt("snoozeLimit", 2),
            snoozeMinutes = o.optInt("snoozeMinutes", 5),
            wakeCheck = o.optBoolean("wakeCheck", false),
        )
    }
}

fun ZonedDateTime.toMillis(): Long = toInstant().toEpochMilli()

fun millisToZoned(ms: Long): ZonedDateTime =
    java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

/** "7 h 32 min", "45 min", "less than a minute" */
fun durationText(ms: Long): String {
    val totalMin = ((ms + 59_999) / 60_000).toInt()
    if (ms < 60_000) return "less than a minute"
    val d = totalMin / (60 * 24)
    val h = (totalMin / 60) % 24
    val m = totalMin % 60
    return buildString {
        if (d > 0) append("$d d ")
        if (h > 0) append("$h h ")
        if (m > 0 || (d == 0 && h == 0)) append("$m min")
    }.trim()
}
