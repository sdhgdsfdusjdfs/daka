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
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private val navigation = PageNavigation()
    private var page: String
        get() = navigation.current
        set(value) = navigation.open(value)
    private var logFilter: String? = null
    private val ink = 0xff172c35.toInt()
    private val muted = 0xff687b83.toInt()
    private val green = 0xff176b5b.toInt()
    private val cfg get() = DakaApp.config
    private val db get() = DakaApp.store
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        state?.getStringArrayList("pages")?.let(navigation::restore)
        logFilter = state?.getString("logFilter")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!navigateBack()) finish()
            }
        }
        if (cfg.text("initialized").isBlank()) {
        cfg.device = if (Build.MANUFACTURER.equals("vivo", true)) "vivo-test" else "honor8-main"
        cfg.set("initialized", "true")
    } }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("pages", ArrayList(navigation.snapshot()))
        outState.putString("logFilter", logFilter)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Legacy system back support")
    override fun onBackPressed() {
        if (!navigateBack()) super.onBackPressed()
    }
    private fun navigateBack(): Boolean {
        if (page == "logs" && logFilter != null) {
            logFilter = null
            logs()
            return true
        }
        if (!navigation.back()) return false
        render()
        return true
    }
    override fun onResume() { super.onResume(); Scheduler.reschedule(this, true); render() }
    private fun render() { when (page) { "plans" -> plans(); "settings" -> settings(); "logs" -> logs(); "permissions" -> permissions(); else -> home() } }
    private fun screen(title: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff5f7fa.toInt())
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        if (page != "home") {
            toolbar.addView(TextView(this).apply {
                text = "‹"
                textSize = 32f
                gravity = Gravity.CENTER
                contentDescription = "返回上一级"
                setTextColor(ink)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { navigateBack() }
            })
        }
        toolbar.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(toolbar)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabs = LinearLayout(this).apply {
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        listOf("home" to "首页", "plans" to "计划", "logs" to "记录", "settings" to "设置").forEach { (key, label) ->
            val selected = page == key || page == "permissions" && key == "settings"
            tabs.addView(TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (selected) green else muted)
                if (selected) background = surface(0xffe8f3ee.toInt(), 12)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                setOnClickListener { navigation.selectTab(key); logFilter = null; render() }
            })
        }
        root.addView(tabs)
        setContentView(root)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun surface(color: Int, radius: Int = 18): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float = 15f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(if (size >= 18f) ink else muted)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(6), 0, dp(8))
        if (size >= 19f) setTypeface(typeface, Typeface.BOLD)
        content.addView(this)
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextColor(if (primary) android.graphics.Color.WHITE else green)
        background = RippleDrawable(ColorStateList.valueOf(0x22176b5b), surface(if (primary) green else 0xffe8f3ee.toInt(), 12), null)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(5) }
        content.addView(this)
        setOnClickListener { safe(action) }
    }
    private fun card(block: () -> Unit) {
        val parent = content
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = surface(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
        parent.addView(box)
        content = box
        try { block() } finally { content = parent }
    }
    private fun row(title: String, detail: String, action: () -> Unit) {
        card {
            val box = content
            text(title, 18f)
            text(detail, 13f)
            box.background = RippleDrawable(ColorStateList.valueOf(0x14176b5b), surface(android.graphics.Color.WHITE), null)
            box.isClickable = true
            box.isFocusable = true
            box.setOnClickListener { safe(action) }
        }
    }
    private fun field(label: String, value: String, secret: Boolean = false): EditText {
        text(label, 13f)
        return EditText(this).apply {
            setText(value)
            textSize = 15f
            setTextColor(ink)
            isSingleLine = true
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            background = surface(android.graphics.Color.WHITE, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) }
            content.addView(this)
        }
    }
    private fun check(label: String, checked: Boolean): CheckBox = CheckBox(this).apply { text = label; isChecked = checked; content.addView(this) }
    private fun safe(action: () -> Unit) { try { action() } catch (_: Exception) { message("操作未完成，请检查配置、权限或凭据。详细异常不会包含在远程日志中。") } }
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("知道了", null).show() }
    private fun home() {
        page = "home"
        screen("定时打卡")
        val next = Scheduler.next()
        card {
            text(if (cfg.enabled) "计划运行中" else "计划已暂停", 14f).setTextColor(green)
            text(next?.let { Times.format(it.at, "HH:mm") } ?: "— — : — —", 38f)
            text(next?.let { "${it.plan.name} · ${Times.format(it.at, "MM月dd日 E")}" } ?: "启用计划后，显示下一次执行时间")
            button(if (cfg.enabled) "暂停所有计划" else "启用时间计划", primary = true) {
                val missing = readiness()
                if (!cfg.enabled && missing.isNotEmpty()) {
                    message(missing.joinToString("\n"))
                    return@button
                }
                cfg.enabled = !cfg.enabled
                cfg.revision++
                if (!cfg.enabled) DakaAccessibility.instance?.cancelRun()
                cfg.set("reconcileFrom", System.currentTimeMillis().toString())
                Scheduler.reschedule(this)
                UploadJob.kick(this)
                home()
            }
        }
        val missing = readiness()
        row("运行准备", if (missing.isEmpty()) "基础配置已就绪 · 查看权限与测试" else missing.joinToString(" · ")) {
            page = "permissions"
            permissions()
        }
        card {
            text("最近一次执行", 18f)
            val last = db.recent(1).firstOrNull()
            if (last == null) text("暂无执行记录") else {
                text("${last.outcome} · ${last.name}", 17f).setTextColor(if (last.outcome == "成功") green else 0xffa26027.toInt())
                text("${Times.format(last.scheduled)}\n${last.detail}", 13f)
                button("查看执行记录") { page = "logs"; logFilter = null; logs() }
            }
        }
        card {
            text("GitHub 同步", 18f)
            text("最近上传 ${cfg.text("lastUpload", "尚无")}\n待同步 ${db.pending().size} 个文件", 13f)
            if (cfg.text("secret-github").isBlank()) text("请在设置中填写上传令牌", 13f)
            else if (cfg.text("syncError").isNotBlank()) text(cfg.text("syncError"), 13f)
            button("立即同步") {
                cfg.set("retryAt", "0")
                db.dirtyStatus()
                UploadJob.kick(this)
                message("已安排同步，完成后可刷新查看。")
            }
            button("查看远程状态") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${cfg.repo}/blob/${cfg.branch}/devices/${cfg.device}/status.md"))) }
        }
        text("${cfg.device} · 北京时间", 12f)
        button("刷新状态") { home() }
    }

    private fun readiness(): List<String> = buildList {
        if (DakaAccessibility.instance == null) add("无障碍未连接")
        if (!Scheduler.precise(this@MainActivity)) add("精确闹钟未允许")
        if (cfg.target.isBlank() || cfg.organization.isBlank()) add("目标应用／组织未配置")
    }

    private fun realTest() {
        val list = cfg.plans()
        if (list.isEmpty()) { message("请先添加计划"); return }
        AlertDialog.Builder(this)
            .setTitle("选择真实打卡测试")
            .setItems(list.map { "${it.name}（${it.kind}）" }.toTypedArray()) { _, index ->
                AlertDialog.Builder(this)
                    .setMessage("现在执行 ${list[index].name}？这会彻底退出并重新打开得力，可能产生真实考勤记录。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始测试") { _, _ -> safe { Scheduler.dispatch(this, Occurrence(list[index], System.currentTimeMillis()), true) } }
                    .show()
            }.show()
    }

    private fun plans() {
        page = "plans"; screen("时间计划")
        text("所有时间均按北京时间。默认周一至周五，可为每条计划单独选择星期。")
        cfg.plans().sortedBy { it.minute }.forEach { p ->
            val weekdays = listOf("一", "二", "三", "四", "五", "六", "日").filterIndexed { i, _ -> p.days and (1 shl i) != 0 }.joinToString("、")
            row("${Times.minuteText(p.minute)}  ${p.name}", "${if (p.enabled) "已启用" else "已暂停"} · 星期$weekdays") { editPlan(p) }
        }
        button("添加时间", primary = true) { editPlan(null) }
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
        row("环境检查与测试", "权限、亮屏、退出与真实打卡测试") { page = "permissions"; permissions() }
        text("得力 e+", 19f)
        val target = field("目标应用包名", cfg.target)
        button("从已安装应用选择得力 e+") {
            @Suppress("DEPRECATION") val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
            AlertDialog.Builder(this).setTitle("选择目标应用").setItems(apps.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }.toTypedArray()) { _, i -> target.setText(apps[i].activityInfo.packageName) }.show()
        }
        val org = field("工作台顶部的组织全名（必须完全一致）", cfg.organization)
        text("登录恢复", 19f)
        val account = field("得力登录账号（留空保留）", "")
        val password = field("得力登录密码（留空保留已保存值）", "", true)
        val gps = check("允许 GPS 打卡条件（默认只等待已连接打卡 Wi-Fi）", cfg.text("allowGps") == "true")
        val device = field("设备别名：honor8-main 或 vivo-test", cfg.device)
        text("产生记录后设备别名固定，避免上传到错误设备目录。")
        text("远程日志", 19f)
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
        button("保存设置", primary = true) { save(); message("已保存，请完成环境检查及手机测试。") }
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
        text("运行测试", 19f)
        text("只测亮屏时不会打开得力。真实打卡测试会彻底退出后重新进入得力。", 13f)
        button("两分钟后测试亮屏") { Scheduler.testWake(this); message("已安排两分钟后测试，请返回桌面并熄屏。") }
        button("十秒后快速测试亮屏") { Scheduler.testWake(this, 10_000); message("已安排十秒后测试，请返回桌面并熄屏。") }
        text("最近亮屏测试：${cfg.text("wakeTestResult", "尚未测试")}", 13f)
        button("选择计划进行真实打卡测试") { realTest() }
        button("仅测试彻底退出") {
            if (DakaAccessibility.instance == null || cfg.target.isBlank()) message("请先配置目标应用并开启无障碍")
            else DakaAccessibility.instance?.testStopTarget()
        }
        text("最近退出测试：${cfg.text("stopTestResult", "尚未测试")}", 13f)
        button("停止当前操作") { DakaAccessibility.instance?.cancelRun(); message("已停止后续打卡操作，正在退出收尾。") }
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
        page = "logs"
        screen("执行记录")
        val filter = field("日期筛选", logFilter ?: Times.day(System.currentTimeMillis()))
        button("查看这一天") {
            val date = filter.text.toString().trim()
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = Times.zone }
            if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date) || runCatching { parser.parse(date) }.getOrNull() == null) {
                message("请输入有效日期，例如 2026-11-20")
            } else { logFilter = date; logs() }
        }
        if (logFilter != null) button("查看最近全部") { logFilter = null; logs() }
        val records = logFilter?.let { db.day(it).reversed() } ?: db.recent(100)
        if (records.isEmpty()) text("这一天还没有执行记录。")
        records.groupBy { Times.day(it.scheduled) }.forEach { (day, runs) ->
            text(day, 18f)
            runs.forEach { run ->
                row("${Times.format(run.scheduled, "HH:mm")}  ${run.name}", "${run.outcome} · ${run.detail}") {
                    message("${db.steps(run.id)}\n回执：${if (run.receipt > 0) Times.format(run.receipt) else "无"}\n退出：${run.cleanup}\n熄屏：${run.sleep}")
                }
            }
        }
        button("导出记录") {
            val data = records.joinToString("\n\n") { "${Times.format(it.scheduled)} ${it.name} ${it.outcome}\n${db.steps(it.id)}退出：${it.cleanup}\n熄屏：${it.sleep}" }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, data), "导出执行记录"))
        }
    }
}
