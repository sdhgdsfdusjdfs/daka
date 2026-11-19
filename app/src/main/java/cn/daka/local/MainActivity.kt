package cn.daka.local

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private var page = "home"
    private val cfg get() = DakaApp.config
    private val db get() = DakaApp.store
    override fun onCreate(state: Bundle?) { super.onCreate(state); if (cfg.text("initialized").isBlank()) {
        cfg.device = if (Build.MANUFACTURER.equals("vivo", true)) "vivo-test" else "honor8-main"
        cfg.set("initialized", "true")
    } }
    override fun onResume() { super.onResume(); Scheduler.reschedule(this, true); render() }
    private fun render() { when (page) { "plans" -> plans(); "settings" -> settings(); "logs" -> logs(); "permissions" -> permissions(); else -> home() } }
    private fun screen(title: String) {
        val scroll = ScrollView(this)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 40) }
        scroll.addView(content); setContentView(scroll)
        text(title, 25f)
        if (page != "home") button("返回首页") { page = "home"; home() }
    }
    private fun text(value: String, size: Float = 16f): TextView = TextView(this).apply { text = value; textSize = size; setTextColor(0xff173e36.toInt()); setPadding(0, 12, 0, 12); content.addView(this) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; content.addView(this); setOnClickListener { safe(action) } }
    private fun field(label: String, value: String, secret: Boolean = false): EditText {
        text(label)
        return EditText(this).apply { setText(value); isSingleLine = true; inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT; content.addView(this) }
    }
    private fun check(label: String, checked: Boolean): CheckBox = CheckBox(this).apply { text = label; isChecked = checked; content.addView(this) }
    private fun safe(action: () -> Unit) { try { action() } catch (_: Exception) { message("操作未完成，请检查配置、权限或凭据。详细异常不会包含在远程日志中。") } }
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("知道了", null).show() }
    private fun home() {
        page = "home"; screen("定时打卡助手")
        val next = Scheduler.next()
        text(if (cfg.enabled) "计划已启用" else "计划已暂停", 20f)
        text("${cfg.device}\n北京时间 ${Times.format(System.currentTimeMillis())}\n下次任务：${next?.let { "${Times.format(it.at)} ${it.plan.name}" } ?: "暂无"}")
        val ready = mutableListOf<String>()
        if (DakaAccessibility.instance == null) ready += "无障碍未连接"
        if (!Scheduler.precise(this)) ready += "精确闹钟未允许"
        if (cfg.target.isBlank() || cfg.organization.isBlank()) ready += "目标应用／组织未配置"
        text(if (ready.isEmpty()) "基础配置就绪，实际能力以手机测试结果为准" else ready.joinToString("\n"))
        button(if (cfg.enabled) "暂停所有计划" else "启用时间计划") {
            if (!cfg.enabled && ready.isNotEmpty()) { message(ready.joinToString("\n")); return@button }
            cfg.enabled = !cfg.enabled; cfg.revision++
            if (!cfg.enabled) DakaAccessibility.instance?.cancelRun()
            cfg.set("reconcileFrom", System.currentTimeMillis().toString())
            Scheduler.reschedule(this); UploadJob.kick(this); home()
        }
        button("编辑时间与日期例外") { page = "plans"; plans() }
        button("环境检查与权限") { page = "permissions"; permissions() }
        button("两分钟后测试亮屏") { Scheduler.testWake(this); message("已安排两分钟后测试。现在可以返回桌面并熄屏；测试只打开本工具。") }
        button("十秒后快速测试亮屏") { Scheduler.testWake(this, 10_000); message("已安排十秒后亮屏测试，请立即返回桌面并熄屏。") }
        text("最近亮屏测试：${cfg.text("wakeTestResult", "尚未测试")}")
        button("选择计划进行真实打卡测试") {
            val list = cfg.plans()
            if (list.isEmpty()) { message("请先添加计划"); return@button }
            AlertDialog.Builder(this).setTitle("将打开得力 e+，可能立即产生考勤记录").setItems(list.map { "${it.name}（${it.kind}）" }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this).setMessage("现在执行 ${list[i].name}？目标 App 打开后可能自动打卡。")
                    .setNegativeButton("取消", null).setPositiveButton("开始真实测试") { _, _ -> safe { Scheduler.dispatch(this, Occurrence(list[i], System.currentTimeMillis()), true) } }.show()
            }.show()
        }
        button("停止当前操作") { DakaAccessibility.instance?.cancelRun(); message("已停止后续操作，已经提交到目标应用的结果请核对。") }
        db.recent(1).firstOrNull()?.let { text("最近执行：${Times.format(it.scheduled)} ${it.name}\n${it.outcome} · ${it.detail}\n${it.sleep}") }
        button("查看执行记录") { page = "logs"; logs() }
        button("设置应用、登录和 GitHub") { page = "settings"; settings() }
        text("最近上传：${cfg.text("lastUpload", "尚无")}\n待同步文件：${db.pending().size}\n${if (cfg.text("secret-github").isBlank()) "GitHub 令牌尚未配置" else cfg.text("syncError")}")
        button("立即同步远程日志") { cfg.set("retryAt", "0"); db.dirtyStatus(); UploadJob.kick(this); message("已加入同步队列，需要可用网络与正确的 GitHub 令牌。") }
        button("打开 GitHub 状态页") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${cfg.repo}/blob/${cfg.branch}/devices/${cfg.device}/status.md"))) }
        button("刷新状态") { home() }
    }
    private fun plans() {
        page = "plans"; screen("时间计划")
        text("所有时间均按北京时间。默认周一至周五，可为每条计划单独选择星期。")
        cfg.plans().sortedBy { it.minute }.forEach { p ->
            val weekdays = listOf("一", "二", "三", "四", "五", "六", "日").filterIndexed { i, _ -> p.days and (1 shl i) != 0 }.joinToString("、")
            button("${if (p.enabled) "●" else "○"} ${Times.minuteText(p.minute)} ${p.name}\n星期$weekdays") { editPlan(p) }
        }
        button("添加时间") { editPlan(null) }
        text("日期例外", 21f)
        cfg.rules().sortedBy { it.date }.forEach { r ->
            val names = cfg.plans().filter { it.id in r.planIds }.joinToString("、") { it.name }
            button("${r.date}：${if (names.isBlank()) "当天停用" else names}") {
                AlertDialog.Builder(this).setMessage("删除这条日期例外，恢复星期规则？").setPositiveButton("删除") { _, _ -> cfg.saveRules(cfg.rules().filter { it.date != r.date }); changed(); plans() }.setNegativeButton("取消", null).show()
            }
        }
        button("添加日期例外") { editDateRule() }
    }
    private fun editPlan(old: Plan?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val name = EditText(this).apply { hint = "计划名称"; setText(old?.name.orEmpty()); box.addView(this) }
        val time = TimePicker(this).apply { setIs24HourView(true); hour = (old?.minute ?: 540) / 60; minute = (old?.minute ?: 540) % 60; box.addView(this) }
        val kind = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("上班", "下班")); setSelection(if (old?.kind == "下班") 1 else 0); box.addView(this) }
        val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").mapIndexed { i, label -> CheckBox(this).apply { text = label; isChecked = (old?.days ?: 31) and (1 shl i) != 0; box.addView(this) } }
        val enabled = CheckBox(this).apply { text = "启用"; isChecked = old?.enabled ?: true; box.addView(this) }
        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = AlertDialog.Builder(this).setTitle(if (old == null) "添加计划" else "修改计划").setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null)
            .apply { if (old != null) setNeutralButton("删除") { _, _ -> cfg.savePlans(cfg.plans().filter { it.id != old.id }); changed(); plans() } }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safe {
            val mask = days.indices.filter { days[it].isChecked }.fold(0) { a, i -> a or (1 shl i) }
            val p = Plan(old?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(), time.hour * 60 + time.minute, kind.selectedItem.toString(), mask, enabled.isChecked)
            val list = cfg.plans().filter { it.id != p.id } + p
            val error = Times.validate(list, cfg.rules())
            if (error != null) message(error) else { cfg.savePlans(list); changed(); dialog.dismiss(); plans() }
        } } }; dialog.show()
    }
    private fun editDateRule() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val date = EditText(this).apply { hint = "日期 YYYY-MM-DD"; setText(Times.day(System.currentTimeMillis())); box.addView(this) }
        box.addView(TextView(this).apply { text = "勾选当天执行的计划，全部不勾选表示停用当天。" })
        val list = cfg.plans(); val boxes = list.map { p -> CheckBox(this).apply { text = "${Times.minuteText(p.minute)} ${p.name}"; isChecked = p.enabled; box.addView(this) } }
        val dialog = AlertDialog.Builder(this).setTitle("日期例外").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = date.text.toString().trim()
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = Times.zone }
            val valid = Regex("\\d{4}-\\d{2}-\\d{2}").matches(value) && runCatching { parser.parse(value) }.getOrNull() != null
            if (!valid) { message("请填写有效日期，例如 2026-11-20"); return@setOnClickListener }
            val rule = DateRule(value, list.indices.filter { boxes[it].isChecked }.map { list[it].id }.toSet())
            val rules = cfg.rules().filter { it.date != value } + rule
            val error = Times.validate(cfg.plans(), rules)
            if (error != null) message(error) else { cfg.saveRules(rules); changed(); dialog.dismiss(); plans() }
        } }; dialog.show()
    }
    private fun changed() { cfg.set("reconcileFrom", System.currentTimeMillis().toString()); Scheduler.reschedule(this); UploadJob.kick(this) }
    private fun settings() {
        page = "settings"; screen("应用与远程日志")
        val target = field("目标应用包名（可用下方按钮选择）", cfg.target)
        button("从已安装应用选择得力 e+") {
            @Suppress("DEPRECATION") val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
            AlertDialog.Builder(this).setTitle("选择目标应用").setItems(apps.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }.toTypedArray()) { _, i -> target.setText(apps[i].activityInfo.packageName) }.show()
        }
        val org = field("工作台顶部的组织全名（必须完全一致）", cfg.organization)
        val account = field("得力登录账号（留空保留已保存值）", "")
        val password = field("得力登录密码（留空保留已保存值）", "", true)
        val gps = check("允许 GPS 打卡条件（默认只等待已连接打卡 Wi-Fi）", cfg.text("allowGps") == "true")
        val device = field("设备别名：honor8-main 或 vivo-test", cfg.device)
        text("产生记录后设备别名固定，避免上传到错误设备目录。")
        val repo = field("GitHub 仓库", cfg.repo)
        val branch = field("日志分支", cfg.branch)
        val token = field("GitHub 限定仓库访问令牌（留空保留）", "", true)
        val heartbeat = field("状态心跳间隔（分钟，15～240，系统可能延迟）", cfg.text("heartbeatMinutes", "30"))
        heartbeat.inputType = InputType.TYPE_CLASS_NUMBER
        val save = {
            val id = device.text.toString().trim()
            require(Regex("[A-Za-z0-9_-]{1,40}").matches(id))
            require(id == cfg.device || db.recent(1).isEmpty())
            require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo.text.toString().trim()))
            require(Regex("[A-Za-z0-9_./-]+").matches(branch.text.toString().trim()))
            val interval = heartbeat.text.toString().toIntOrNull()
            require(interval != null && interval in 15..240)
            cfg.set("target", target.text.toString().trim()); cfg.set("organization", org.text.toString().trim()); cfg.device = id
            cfg.set("repo", repo.text.toString().trim()); cfg.set("branch", branch.text.toString().trim()); cfg.set("allowGps", gps.isChecked.toString())
            cfg.set("heartbeatMinutes", interval.toString())
            if (account.text.isNotBlank()) cfg.saveSecret("account", account.text.toString().trim())
            if (password.text.isNotBlank()) cfg.saveSecret("password", password.text.toString())
            if (token.text.isNotBlank()) cfg.saveSecret("github", token.text.toString().trim())
            cfg.revision++; cfg.set("retryAt", "0"); db.dirtyAll(); changed()
        }
        button("保存设置") { save(); message("已保存，请完成环境检查及手机测试。") }
        button("保存并测试 GitHub 连接与写入") {
            save()
            val progress = AlertDialog.Builder(this).setMessage("正在检查仓库及日志分支…").setCancelable(false).create(); progress.show()
            Thread {
                val result = try {
                    val api = GitHubSync(cfg); val result = api.verify(); db.dirtyStatus()
                    api.put("status.md", RemoteText.status(this)); cfg.set("lastUpload", Times.format(System.currentTimeMillis())); result + "，状态文件已写入"
                } catch (e: ApiFailure) { "连接失败：GitHub HTTP ${e.code}，请检查令牌权限和分支" }
                catch (_: Exception) { "连接失败，请检查网络、令牌和配置" }
                runOnUiThread { if (!isFinishing) { progress.dismiss(); message(result) } }
            }.start()
        }
        button("清除已保存的登录凭据") { AlertDialog.Builder(this).setMessage("清除本地账号和密码？").setPositiveButton("清除") { _, _ -> cfg.saveSecret("account", ""); cfg.saveSecret("password", ""); message("已清除") }.setNegativeButton("取消", null).show() }
        button("清除 GitHub 令牌") { cfg.saveSecret("github", ""); message("已清除令牌") }
    }
    private fun permissions() {
        page = "permissions"; screen("环境检查")
        val km = getSystemService(KeyguardManager::class.java)
        val admin = getSystemService(DevicePolicyManager::class.java)
        text("手机：${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}／API ${Build.VERSION.SDK_INT}\n密码锁：${if (km.isDeviceSecure) "存在，需要先改为无密码" else "无"}\n无障碍：${if (DakaAccessibility.instance != null) "已连接" else "未连接"}\n精确闹钟：${Scheduler.precise(this)}\n悬浮窗特殊访问：${Settings.canDrawOverlays(this)}\n旧机熄屏权限：${admin.isAdminActive(ComponentName(this, ScreenAdmin::class.java))}")
        button("打开无障碍设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (Build.VERSION.SDK_INT >= 31) button("允许精确闹钟") { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
        button("允许悬浮窗特殊访问（用于部分系统后台拉起）") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        button("打开电池优化设置") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        button("打开本工具系统设置／自启动配置") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        if (Build.VERSION.SDK_INT >= 33) button("允许通知") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        button("激活旧系统主动熄屏权限") { startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, ScreenAdmin::class.java)).putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "仅用于完成任务后熄屏")) }
        button("取消设备管理激活（卸载前使用）") { admin.removeActiveAdmin(ComponentName(this, ScreenAdmin::class.java)); permissions() }
        text("请允许自启动和后台运行。充电时保持唤醒的开发者选项应关闭。荣耀／vivo 的实际菜单以手机界面为准。")
        button("刷新检查") { permissions() }
        button("五秒后采集目标页面结构（用于适配）") {
            if (DakaAccessibility.instance == null) { message("请先开启无障碍服务"); return@button }
            android.os.Handler(mainLooper).postDelayed({
                val data = DakaAccessibility.instance?.diagnostic() ?: "无障碍服务未连接"
                java.io.File(filesDir, "diagnostic.txt").writeText(data)
            }, 5000)
            message("五秒后采集当前目标页面。可先切换到得力；手动打开得力也可能自动打卡。采集结果仅保留结构和指定标签，其他文本会隐藏。")
        }
        button("查看／分享最近采集的页面结构") {
            val file = java.io.File(filesDir, "diagnostic.txt")
            if (!file.exists()) message("尚未采集") else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, file.readText()), "分享页面结构"))
        }
    }
    private fun logs() {
        page = "logs"; screen("执行记录")
        val filter = field("筛选日期 YYYY-MM-DD（留空显示最近记录）", "")
        button("按日期筛选") { showRuns(filter.text.toString().trim()) }
        button("导出最近记录文本") {
            val data = db.recent(300).joinToString("\n\n") { "${Times.format(it.scheduled)} ${it.name} ${it.outcome}\n${db.steps(it.id)}熄屏：${it.sleep}" }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, data), "导出执行记录"))
        }
        showRuns("")
    }
    private fun showRuns(date: String) {
        if (date.isNotBlank()) { page = "logs"; screen("执行记录 · $date"); button("返回全部记录") { logs() } }
        db.recent(500).filter { date.isBlank() || Times.day(it.scheduled) == date }.forEach { r ->
            button("${Times.format(r.scheduled)} ${r.name}\n${r.outcome} · ${r.detail}") { message("${db.steps(r.id)}\n回执：${if (r.receipt > 0) Times.format(r.receipt) else "无"}\n熄屏：${r.sleep}") }
        }
    }
}
