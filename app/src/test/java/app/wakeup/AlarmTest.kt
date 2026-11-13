package app.wakeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class AlarmTest {
    private val zone = ZoneId.of("America/New_York")
    // Friday 2026-11-13 06:30
    private val fri0630 = ZonedDateTime.of(2026, 11, 13, 6, 30, 0, 0, zone)

    @Test
    fun onceLaterToday() {
        val t = Alarm(hour = 7, minute = 0).nextTrigger(fri0630)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 7, 0, 0, 0, zone), t)
    }

    @Test
    fun onceTomorrowWhenPassed() {
        val t = Alarm(hour = 6, minute = 0).nextTrigger(fri0630)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 6, 0, 0, 0, zone), t)
    }

    @Test
    fun weekdaysSkipWeekend() {
        val a = Alarm(hour = 6, minute = 0, days = 0b0011111)
        val t = a.nextTrigger(fri0630)
        assertEquals(DayOfWeek.MONDAY, t.dayOfWeek)
        assertEquals(16, t.dayOfMonth)
    }

    @Test
    fun sameMinuteDoesNotRefire() {
        val a = Alarm(hour = 6, minute = 30, days = 0b1111111)
        val t = a.nextTrigger(fri0630)
        assertEquals(14, t.dayOfMonth)
    }

    @Test
    fun dayBits() {
        val a = Alarm().withDay(DayOfWeek.SUNDAY, true).withDay(DayOfWeek.MONDAY, true).withDay(DayOfWeek.MONDAY, false)
        assertTrue(a.hasDay(DayOfWeek.SUNDAY))
        assertEquals(0b1000000, a.days)
    }

    @Test
    fun mathAnswersAreRight() {
        val r = Random(1)
        repeat(200) {
            for (level in 0..3) {
                val p = makeProblem(level, r)
                val parts = p.text.split(" ")
                val v = if (parts.size == 3) parts[0].toInt() + parts[2].toInt()
                else parts[0].toInt() * parts[2].toInt() + parts[4].toInt()
                assertEquals(v, p.answer)
            }
        }
    }

    @Test
    fun durations() {
        assertEquals("7 h 32 min", durationText((7 * 60 + 32) * 60_000L))
        assertEquals("45 min", durationText(45 * 60_000L - 5_000))
        assertEquals("8 h", durationText(8 * 3_600_000L))
    }
}
