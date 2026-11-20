package cn.daka.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DailyLogsTest {
    private fun utc(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)!!.time

    @Test fun filesFollowBeijingDateAcrossMidnightAndYearBoundary() {
        assertEquals("logs/2026-11-19.md", DailyLogs.path(utc("2026-11-19 15:59:59")))
        assertEquals("logs/2026-11-20.md", DailyLogs.path(utc("2026-11-19 16:00:00")))
        assertEquals("logs/2027-01-01.md", DailyLogs.path(utc("2026-12-31 16:00:00")))
    }

    @Test fun tasksOnSameDateShareOneFile() {
        assertEquals(
            DailyLogs.path(utc("2026-11-20 01:10:00")),
            DailyLogs.path(utc("2026-11-20 10:05:00"))
        )
    }
}
