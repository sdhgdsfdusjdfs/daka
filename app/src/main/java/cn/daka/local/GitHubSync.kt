package cn.daka.local

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Base64
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ApiFailure(val code: Int, val retrySeconds: Long = 0) : Exception("GitHub HTTP $code")

class GitHubSync(private val cfg: Config) {
    companion object { private val writeLock = Any() }
    private val repository = cfg.repo
    private val logBranch = cfg.branch
    private val device = cfg.device
    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) { "仓库格式应为 owner/repo" }
        val token = cfg.secret("github")
        require(token.isNotBlank()) { "请配置 GitHub 访问令牌" }
        val conn = URL(GitHubRoutes.repositoryUrl(repository, path)).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = method; conn.connectTimeout = 12_000; conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("User-Agent", "Daka-Android/0.1")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            if (code !in 200..299) {
                val retry = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 0
                val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.minus(System.currentTimeMillis() / 1000) ?: 0
                throw ApiFailure(code, maxOf(retry, if (conn.getHeaderField("X-RateLimit-Remaining") == "0") reset else 0))
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally { conn.disconnect() }
    }
    fun verify(): String {
        val repo = request("GET", "")
        require(repo.optJSONObject("permissions")?.optBoolean("push", true) != false) { "令牌没有仓库写入权限" }
        // Verify the selected log branch as well. It is initialized from the desktop via SSH.
        request("GET", "branches/${java.net.URLEncoder.encode(logBranch, "UTF-8")}")
        return if (repo.optBoolean("private")) "连接成功：私有仓库" else "连接成功：公开仓库，上传日志将公开可见"
    }
    fun put(relative: String, content: String) {
        synchronized(writeLock) {
        require(Regex("[A-Za-z0-9_-]{1,40}").matches(device))
        require(Regex("[A-Za-z0-9_./-]+").matches(logBranch))
        val path = "contents/devices/$device/$relative"
        repeat(3) { attempt ->
            val old = try { request("GET", "$path?ref=${java.net.URLEncoder.encode(logBranch, "UTF-8")}") } catch (e: ApiFailure) { if (e.code == 404) null else throw e }
            val payload = JSONObject().put("message", "Update $device $relative").put("branch", logBranch)
                .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (old != null) payload.put("sha", old.getString("sha"))
            try { request("PUT", path, payload); return } catch (e: ApiFailure) { if (e.code !in listOf(409, 422) || attempt == 2) throw e }
        }
        }
    }
}

object RemoteText {
    private fun clean(s: String) = s.replace("|", "／").replace("\n", " ").replace("\r", " ").take(300)
    fun log(month: String): String = buildString {
        append("# ${DakaApp.config.device} · $month 执行记录\n\n时间均为北京时间。成功表示本次页面回执已确认，班次以单位考勤报表核对。\n\n")
        append("| 计划时间 | 名称 | 类型 | 开始 | 回执 | 结果 | 说明 | 熄屏 |\n|---|---|---|---|---|---|---|---|\n")
        DakaApp.store.month(month).forEach { r -> append("| ${Times.format(r.scheduled)} | ${clean(r.name)} | ${r.kind} | ${Times.format(r.started, "HH:mm:ss")} | ${if (r.receipt > 0) Times.format(r.receipt, "HH:mm:ss") else "—"} | ${r.outcome} | ${clean(r.detail)} | ${clean(r.sleep)} |\n") }
    }
    fun status(c: Context): String = buildString {
        val cfg = DakaApp.config; val now = System.currentTimeMillis()
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val next = Scheduler.next()
        append("# ${cfg.device} 状态\n\n")
        append("快照生成时间：${Times.format(now)}（北京时间）。请刷新本页查看更新。\n\n")
        append("最近上报超过约 90 分钟时，状态可能过期。休眠、网络或工具停止都可能造成延迟，请结合下次计划和月度记录检查。\n\n")
        append("| 项目 | 状态 |\n|---|---|\n")
        append("| 计划总开关 | ${if (cfg.enabled) "启用" else "暂停"} |\n| 无障碍连接 | ${if (DakaAccessibility.instance != null) "已连接" else "未连接"} |\n")
        append("| 精确闹钟 | ${if (Scheduler.precise(c)) "已允许" else "未允许"} |\n| 电量／供电 | $level%／${if (plugged) "接电" else "电池"} |\n")
        append("| 下次任务 | ${if (next != null) "${Times.format(next.at)} ${clean(next.plan.name)}" else "暂无"} |\n")
        append("| 上次上传确认 | ${clean(cfg.text("lastUpload", "尚无"))} |\n| 待同步文件数 | ${DakaApp.store.pending().size} |\n")
        append("\n## 最近四次\n\n| 计划时间 | 任务 | 结果 | 说明 |\n|---|---|---|---|\n")
        DakaApp.store.recent(4).forEach { r -> append("| ${Times.format(r.scheduled)} | ${clean(r.name)} | ${r.outcome} | ${clean(r.detail)} |\n") }
        append("\n[本月完整记录](logs/${Times.month(now)}.md)\n")
    }
}

class UploadJob : JobService() {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private val busy = AtomicBoolean(false)
        fun ensurePeriodic(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            val interval = (DakaApp.config.text("heartbeatMinutes", "30").toLongOrNull() ?: 30).coerceIn(15, 240) * 60_000L
            if (js.allPendingJobs.none { it.id == 101 && it.intervalMillis == interval }) js.schedule(JobInfo.Builder(101, ComponentName(c, UploadJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(interval).setPersisted(true).build())
        }
        fun kick(c: Context) {
            val delay = ((DakaApp.config.text("retryAt", "0").toLongOrNull() ?: 0) - System.currentTimeMillis()).coerceAtLeast(0)
            c.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(102, ComponentName(c, UploadJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
    override fun onStartJob(params: JobParameters): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        executor.execute {
            var retry = false
            try {
                val cfg = DakaApp.config; val db = DakaApp.store
                if (params.jobId == 101) db.dirtyStatus()
                val retryAt = cfg.text("retryAt", "0").toLongOrNull() ?: 0
                if (cfg.secret("github").isNotBlank() && retryAt <= System.currentTimeMillis()) {
                    val client = GitHubSync(cfg)
                    for ((path, revision) in db.pending().take(24)) {
                        val content = if (path == "status.md") RemoteText.status(this) else RemoteText.log(path.removePrefix("logs/").removeSuffix(".md"))
                        client.put(path, content); db.acknowledge(path, revision)
                        cfg.set("lastUpload", Times.format(System.currentTimeMillis())); cfg.set("syncError", ""); cfg.set("retryAt", "0")
                    }
                    db.prune(); retry = db.pending().isNotEmpty()
                }
            } catch (e: ApiFailure) {
                val wait = maxOf(e.retrySeconds, if (e.code in listOf(401, 403, 404)) 1800 else 60)
                DakaApp.config.set("syncError", "GitHub HTTP ${e.code}，请检查网络、仓库权限或令牌")
                DakaApp.config.set("retryAt", (System.currentTimeMillis() + wait * 1000).toString()); retry = true
            } catch (_: Exception) {
                DakaApp.config.set("syncError", "同步失败，请检查网络、配置或本地凭据")
                DakaApp.config.set("retryAt", (System.currentTimeMillis() + 60_000).toString()); retry = true
            } finally { busy.set(false); jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
