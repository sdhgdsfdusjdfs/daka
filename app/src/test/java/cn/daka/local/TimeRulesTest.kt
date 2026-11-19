package cn.daka.local

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class TimeRulesTest {
    private fun at(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = Times.zone }.parse(s)!!.time
    @Test fun defaultsHaveFourCorrectTimesAndKinds() {
        assertEquals(listOf("09:10", "11:35", "13:40", "18:05"), Times.defaults().map { Times.minuteText(it.minute) })
        assertEquals(listOf("上班", "下班", "上班", "下班"), Times.defaults().map { it.kind })
    }
    @Test fun fridayEveningSkipsWeekend() {
        val n = Times.next(Times.defaults(), emptyList(), at("2026-11-20 18:06:00"))!!
        assertEquals("2026-11-23 09:10:00", Times.format(n.at))
    }
    @Test fun dateOverrideCanStopWeekdayAndEnableWeekend() {
        val rules = listOf(DateRule("2026-11-20", emptySet()), DateRule("2026-11-21", setOf("am-in")))
        assertTrue(Times.onDay(Times.defaults(), rules, at("2026-11-20 08:00:00")).isEmpty())
        assertEquals(listOf("am-in"), Times.onDay(Times.defaults(), rules, at("2026-11-21 08:00:00")).map { it.plan.id })
    }
    @Test fun nextExcludesCurrentAndDeletedPlans() {
        assertEquals("am-out", Times.next(Times.defaults(), emptyList(), at("2026-11-19 09:10:00"))!!.plan.id)
        assertEquals("pm-in", Times.next(Times.defaults().filter { it.id != "am-out" }, emptyList(), at("2026-11-19 09:11:00"))!!.plan.id)
    }
    @Test fun scheduleRejectsOverlapButAllowsDifferentWeekdays() {
        val p = Plan("a", "A", 550, "上班", 1)
        val q = Plan("b", "B", 551, "下班", 1)
        assertNotNull(Times.validate(listOf(p, q), emptyList()))
        assertNull(Times.validate(listOf(p, q.copy(days = 2)), emptyList()))
        assertNotNull(Times.validate(listOf(p, q.copy(days = 2)), listOf(DateRule("2026-11-21", setOf("a", "b")))))
    }
    @Test fun crossMidnightOverlapIsRejected() {
        assertNotNull(Times.validate(listOf(Plan("a", "A", 1439, "上班", 1), Plan("b", "B", 0, "下班", 2)), emptyList()))
    }
    @Test fun matchingSuccessReceiptAcceptsOnlyCurrentWindow() {
        val scheduled = at("2026-11-19 13:40:00")
        assertEquals(at("2026-11-19 13:40:11"), Evidence.find(listOf("打卡成功", "13:40:11"), emptyList(), scheduled, scheduled + 15_000)!!.at)
        assertNull(Evidence.find(listOf("打卡成功", "09:10:12"), emptyList(), scheduled, scheduled + 15_000))
        assertNull(Evidence.find(listOf("打卡成功", "13:41:00"), emptyList(), scheduled, scheduled + 15_000))
    }
    @Test fun buttonClockAndCooldownAreNotReceipts() {
        val scheduled = at("2026-11-19 13:40:00")
        assertNull(Evidence.find(listOf("上班打卡", "13:40:11", "15秒内只可打一次卡"), emptyList(), scheduled, scheduled + 15_000))
        assertNull(Evidence.find(emptyList(), listOf("今日考勤：09:11、11:35"), scheduled, scheduled + 15_000))
    }
    @Test fun todayRecordAndTomorrowStaleReceipt() {
        val scheduled = at("2026-11-19 13:40:00")
        assertNotNull(Evidence.find(emptyList(), listOf("今日考勤：09:11、11:35、13:40"), scheduled, scheduled + 15_000, recordDateVerified = true))
        assertNull(Evidence.find(listOf("打卡成功", "13:40:11"), emptyList(), scheduled, scheduled + 86_400_000))
    }
    @Test fun cachedRecordWithoutVerifiedDateCannotBeSuccess() {
        val scheduled = at("2026-11-19 09:10:00")
        assertNull(Evidence.find(emptyList(), listOf("今日考勤：09:10、11:35"), scheduled, scheduled + 20_000))
    }
    @Test fun dateOverrideCannotCreateMidnightCollision() {
        val plans = listOf(Plan("late", "Late", 1439, "上班", 1), Plan("early", "Early", 0, "下班", 4))
        assertNull(Times.validate(plans, emptyList()))
        assertNotNull(Times.validate(plans, listOf(DateRule("2026-11-24", setOf("early")))))
    }
}
