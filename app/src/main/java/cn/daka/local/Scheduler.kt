package cn.daka.local

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object Scheduler {
    private fun alarm(c: Context) = c.getSystemService(AlarmManager::class.java)
    fun precise(c: Context) = Build.VERSION.SDK_INT < 31 || alarm(c).canScheduleExactAlarms()
    private fun pending(c: Context, i: Intent, code: Int) = PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun next(): Occurrence? = if (DakaApp.config.enabled) Times.next(DakaApp.config.plans(), DakaApp.config.rules(), System.currentTimeMillis()) else null
    fun reschedule(c: Context, recover: Boolean = false) {
        val cfg = DakaApp.config
        alarm(c).cancel(pending(c, Intent(c, AlarmReceiver::class.java), 1))
        UploadJob.ensurePeriodic(c)
        val now = System.currentTimeMillis()
        DakaApp.store.recent(1000).filter { it.outcome == "运行中" && it.scheduled + 60_000 <= now }.forEach {
            DakaApp.store.finish(it.id, if (it.launched || it.clicked) "结果未知" else "中断", "恢复检查：该任务执行窗口已结束")
        }
        if (!cfg.enabled || !precise(c)) { DakaApp.store.dirtyStatus(); return }
        if (recover) reconcile(c)
        val next = next()
        if (next != null) {
            val i = Intent(c, AlarmReceiver::class.java).putExtra("plan", next.plan.id).putExtra("at", next.at).putExtra("revision", cfg.revision)
            alarm(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending(c, i, 1))
        }
        DakaApp.store.dirtyStatus()
    }
    private fun reconcile(c: Context) {
        val cfg = DakaApp.config; val db = DakaApp.store; val now = System.currentTimeMillis()
        val since = cfg.text("reconcileFrom", now.toString()).toLongOrNull() ?: now
        val cal = Times.calendar(maxOf(since, now - 90L * 86_400_000))
        val occurrences = mutableListOf<Occurrence>()
        while (Times.day(cal.timeInMillis) <= Times.day(now)) {
            occurrences += Times.onDay(cfg.plans(), cfg.rules(), cal.timeInMillis).filter { it.at in since..now }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        occurrences.forEach { o ->
            val id = "${cfg.device}-${o.key}"; val old = db.run(id)
            if (old == null && now - o.at >= 60_000) { db.begin(id, o); db.finish(id, "错过", "恢复时已超过执行窗口") }
            else if (now - o.at < 60_000 && (old == null || old.outcome == "运行中")) dispatch(c, o)
        }
        db.recent(1000).filter { it.outcome == "运行中" && it.scheduled + 60_000 <= now }.forEach {
            db.finish(it.id, if (it.launched || it.clicked) "结果未知" else "中断", "执行被中断，恢复时窗口已结束")
        }
        cfg.set("reconcileFrom", now.toString())
    }
    fun dispatch(c: Context, o: Occurrence, manual: Boolean = false) {
        val cfg = DakaApp.config; val db = DakaApp.store
        val id = if (manual) "${cfg.device}-test-${System.currentTimeMillis()}" else "${cfg.device}-${o.key}"
        if (db.run(id)?.outcome?.let { it != "运行中" } == true) return
        if (System.currentTimeMillis() >= o.at + 60_000) { db.begin(id, o); db.finish(id, "错过", "系统触发时间超过允许窗口"); UploadJob.kick(c); return }
        val service = DakaAccessibility.instance
        if (service == null) { db.begin(id, o); db.finish(id, "失败", "无障碍服务未连接"); UploadJob.kick(c); return }
        service.startRun(id, if (manual) o.copy(plan = o.plan.copy(name = "测试 · ${o.plan.name}")) else o, manual)
    }
    fun testWake(c: Context, delayMillis: Long = 120_000) {
        check(precise(c)) { "请先允许精确闹钟" }
        alarm(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pending(c, Intent(c, AlarmReceiver::class.java).putExtra("wakeTest", true), 2))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getBooleanExtra("wakeTest", false)) {
            val service = DakaAccessibility.instance
            if (service != null) service.wakeTest() else {
                DakaApp.config.set("wakeTestResult", "失败：无障碍服务未连接"); DakaApp.store.dirtyStatus(); UploadJob.kick(c)
            }
            return
        }
        val cfg = DakaApp.config
        if (!cfg.enabled || i.getLongExtra("revision", -1) != cfg.revision) return
        Scheduler.reschedule(c)
        val at = i.getLongExtra("at", 0)
        val plan = cfg.plans().find { it.id == i.getStringExtra("plan") && it.enabled } ?: return
        if (Times.applies(plan, Times.calendar(at), cfg.rules())) Scheduler.dispatch(c, Occurrence(plan, at))
    }
}
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        Scheduler.reschedule(c, true); UploadJob.kick(c)
    }
}
