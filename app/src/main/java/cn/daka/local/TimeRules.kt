package cn.daka.local

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Plan(val id: String, val name: String, val minute: Int, val kind: String, val days: Int = 31, val enabled: Boolean = true)
data class DateRule(val date: String, val planIds: Set<String>)
data class Occurrence(val plan: Plan, val at: Long) { val key: String get() = "${plan.id}-${Times.day(at)}" }

object Times {
    val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    fun format(at: Long, pattern: String = "yyyy-MM-dd HH:mm:ss"): String = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(at))
    fun day(at: Long) = format(at, "yyyy-MM-dd")
    fun month(at: Long) = format(at, "yyyy-MM")
    fun calendar(at: Long) = Calendar.getInstance(zone).apply { timeInMillis = at }
    fun minuteText(minute: Int) = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)
    fun defaults() = listOf(Plan("am-in", "上午上班", 550, "上班"), Plan("am-out", "上午下班", 695, "下班"), Plan("pm-in", "下午上班", 820, "上班"), Plan("pm-out", "下午下班", 1085, "下班"))
    fun applies(p: Plan, date: Calendar, rules: List<DateRule>): Boolean {
        if (!p.enabled) return false
        rules.firstOrNull { it.date == day(date.timeInMillis) }?.let { return p.id in it.planIds }
        val weekday = (date.get(Calendar.DAY_OF_WEEK) + 5) % 7
        return p.days and (1 shl weekday) != 0
    }
    fun onDay(plans: List<Plan>, rules: List<DateRule>, day: Long): List<Occurrence> {
        val c = calendar(day)
        return plans.filter { applies(it, c, rules) }.map { p ->
            val d = c.clone() as Calendar
            d.set(Calendar.HOUR_OF_DAY, p.minute / 60); d.set(Calendar.MINUTE, p.minute % 60)
            d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0)
            Occurrence(p, d.timeInMillis)
        }.sortedBy { it.at }
    }
    fun next(plans: List<Plan>, rules: List<DateRule>, after: Long): Occurrence? {
        val c = calendar(after)
        repeat(370) {
            onDay(plans, rules, c.timeInMillis).firstOrNull { it.at > after }?.let { return it }
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }
    fun validate(plans: List<Plan>, rules: List<DateRule>): String? {
        if (plans.any { it.name.isBlank() || it.minute !in 0..1439 || it.kind !in listOf("上班", "下班") || it.days !in 1..127 }) return "请填写名称、有效时间和执行星期"
        if (plans.map { it.id }.distinct().size != plans.size) return "计划标识重复"
        val active = plans.filter { it.enabled }
        for (a in active.indices) for (b in a + 1 until active.size) {
            val p = active[a]; val q = active[b]
            if (kotlin.math.abs(p.minute - q.minute) < 2 && (p.days and q.days != 0 || rules.any { p.id in it.planIds && q.id in it.planIds })) return "同一天的启用计划至少间隔两分钟"
        }
        // Adjacent dates can also overlap at midnight.
        for (p in active) for (q in active) if (1440 - p.minute + q.minute < 2) {
            for (d in 0..6) if (p.days and (1 shl d) != 0 && q.days and (1 shl ((d + 1) % 7)) != 0) return "跨午夜的计划至少间隔两分钟"
        }
        for (rule in rules) {
            val parsed = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone; isLenient = false }.parse(rule.date) }.getOrNull() ?: return "日期例外包含无效日期"
            val c = calendar(parsed.time); c.add(Calendar.DAY_OF_MONTH, -1)
            val around = mutableListOf<Occurrence>()
            repeat(3) { around += onDay(plans, rules, c.timeInMillis); c.add(Calendar.DAY_OF_MONTH, 1) }
            if (around.sortedBy { it.at }.zipWithNext().any { (a, b) -> b.at - a.at < 120_000 }) return "日期例外产生了间隔不足两分钟的计划"
        }
        return null
    }
    fun receiptTime(text: String, scheduled: Long, secondPrecision: Boolean): Long? {
        val regex = if (secondPrecision) Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d):([0-5]\\d)(?!\\d)") else Regex("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![:\\d])")
        for (match in regex.findAll(text)) {
            val c = calendar(scheduled)
            c.set(Calendar.HOUR_OF_DAY, match.groupValues[1].toInt()); c.set(Calendar.MINUTE, match.groupValues[2].toInt())
            c.set(Calendar.SECOND, if (secondPrecision) match.groupValues[3].toInt() else 0); c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis in scheduled until scheduled + 60_000) return c.timeInMillis
        }
        return null
    }
}
