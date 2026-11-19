package cn.daka.local

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.view.Gravity
import android.widget.TextView
import android.graphics.PixelFormat
import android.util.Log

class ScreenAdmin : DeviceAdminReceiver()

private data class Session(val id: String, val occurrence: Occurrence, val manual: Boolean, val deadlineElapsed: Long,
    val recovered: Boolean, val beganElapsed: Long = SystemClock.elapsedRealtime(), var launchedThisSession: Boolean = false,
    var verifiedOrganization: Boolean = false, var receipt: Receipt? = null, var dismissedReceipt: Boolean = false,
    var workbenchSince: Long = 0, var swipes: Int = 0, var lastSwipe: Long = 0, var navigated: Boolean = false,
    var scrolled: Boolean = false, var phase: String = "", var targetSeen: Boolean = false, var launchElapsed: Long = 0,
    var blockedReason: String? = null)

class DakaAccessibility : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var lock: PowerManager.WakeLock? = null
    private var wakeOverlay: TextView? = null
    private var testing = false
    private var testUntil = 0L
    private var testSwipes = 0
    private val tick = object : Runnable { override fun run() { inspectSafely(); if (session != null || testing) handler.postDelayed(this, 700) } }
    companion object { var instance: DakaAccessibility? = null; private set }

    override fun onServiceConnected() { instance = this; Scheduler.reschedule(this, true); UploadJob.kick(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* The bounded loop reads fresh nodes; event text is never logged. */ }
    override fun onInterrupt() { if (session != null) finish("结果未知", "无障碍服务被中断") }
    override fun onDestroy() {
        session?.let { DakaApp.store.finish(it.id, "结果未知", "无障碍服务结束，需核对本次记录") }
        handler.removeCallbacksAndMessages(null); release(); instance = null; super.onDestroy()
    }
    fun startRun(id: String, o: Occurrence, manual: Boolean) {
        if (session != null || testing) {
            if (session?.id == id) return
            DakaApp.store.begin(id, o); DakaApp.store.finish(id, "跳过", "另一个操作正在执行"); UploadJob.kick(this); return
        }
        val cfg = DakaApp.config; val db = DakaApp.store
        val old = db.run(id)
        if (old != null && old.outcome != "运行中") return
        db.begin(id, o)
        if (cfg.target.isBlank() || cfg.organization.isBlank()) { db.finish(id, "失败", "请先配置目标应用和组织名称"); UploadJob.kick(this); return }
        val remaining = o.at + 60_000 - System.currentTimeMillis()
        if (remaining <= 0) { db.finish(id, "错过", "执行窗口已结束"); UploadJob.kick(this); return }
        session = Session(id, o, manual, SystemClock.elapsedRealtime() + remaining, old?.launched == true)
        if (old?.launched == true) {
            // Relaunching can itself submit another punch. Recovery only inspects the current page.
            session?.targetSeen = rootInActiveWindow?.packageName?.toString() == cfg.target
            phase("中断恢复：只核对当前页面记录")
        } else {
            hold(remaining + 5000)
            try { showWakeOverlay(); handler.postDelayed({ if (session?.id == id && session?.launchedThisSession == false) launchWakePage() }, 400) }
            catch (_: Exception) { finish("失败", "系统阻止后台亮屏，请检查悬浮窗与后台权限"); return }
        }
        handler.removeCallbacks(tick); handler.post(tick); UploadJob.kick(this)
    }
    fun wakeTest() {
        if (session != null || testing) return
        testing = true; testUntil = SystemClock.elapsedRealtime() + 15_000; testSwipes = 0
        hold(20_000)
        DakaApp.config.set("wakeTestResult", "等待验证")
        try { showWakeOverlay(); handler.postDelayed({ if (testing) launchWakePage() }, 400) }
        catch (_: Exception) { stopTest("失败：系统阻止后台打开测试页"); return }
        handler.post(tick)
    }
    fun cancelRun() { if (session != null) finish("结果未知", "用户停止了当前操作，请核对已提交记录"); if (testing) stopTest("测试已取消") }
    @Suppress("DEPRECATION") private fun hold(timeout: Long) {
        release()
        val pm = getSystemService(PowerManager::class.java)
        val flags = if (Build.VERSION.SDK_INT < 27) PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP else PowerManager.PARTIAL_WAKE_LOCK
        lock = pm.newWakeLock(flags, "$packageName:execution").apply { acquire(timeout) }
    }
    private fun launchWakePage() {
        if (session?.launchedThisSession == true) return
        try {
            Log.i("Daka", "Opening wake page; interactive=${getSystemService(PowerManager::class.java).isInteractive}")
            startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            if (testing) stopTest("失败：后台亮屏页面被系统阻止") else finish("失败", "后台亮屏页面被系统阻止")
        }
    }
    private fun showWakeOverlay() {
        if (wakeOverlay != null) return
        val overlay = TextView(this).apply { text = "定时打卡助手 · 正在唤醒"; textSize = 16f; setTextColor(android.graphics.Color.WHITE); setBackgroundColor(0xff176b5b.toInt()); setPadding(24, 16, 24, 16) }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; params.y = 160
        getSystemService(WindowManager::class.java).addView(overlay, params); wakeOverlay = overlay
    }
    private fun removeWakeOverlay() { wakeOverlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; wakeOverlay = null }
    private fun release() { removeWakeOverlay(); lock?.let { if (it.isHeld) it.release() }; lock = null }
    private fun stopTest(message: String) {
        testing = false; DakaApp.config.set("wakeTestResult", "${Times.format(System.currentTimeMillis())} $message")
        Log.i("Daka", "Wake test: $message")
        WakeActivity.current?.finish(); release(); DakaApp.store.dirtyStatus(); UploadJob.kick(this)
    }
    private fun inspectSafely() {
        try { inspect() } catch (_: Exception) { if (session != null) finish("结果未知", "页面操作异常，已停止本轮"); if (testing) stopTest("失败：测试过程异常") }
    }
    private fun inspect() {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (testing) {
            if (pm.isInteractive && !km.isKeyguardLocked && WakeActivity.current?.hasWindowFocus() == true) { stopTest("成功：定时亮屏并解除无密码锁屏"); return }
            if (nowElapsed >= testUntil) {
                stopTest("失败：亮屏=${pm.isInteractive}，锁屏=${km.isKeyguardLocked}，测试页=${WakeActivity.current != null}，焦点=${WakeActivity.current?.hasWindowFocus() == true}")
                return
            }
            if (km.isKeyguardLocked && testSwipes < 2 && nowElapsed >= testUntil - 12_000 + testSwipes * 4000) { swipeUp(); testSwipes++ }
            return
        }
        val s = session ?: return
        val cfg = DakaApp.config; val run = requireNotNull(DakaApp.store.run(s.id))
        if (!s.manual && !cfg.enabled) { finish(if (run.launched) "结果未知" else "已暂停", "总开关已关闭"); return }
        if (nowElapsed >= s.deadlineElapsed || System.currentTimeMillis() >= s.occurrence.at + 60_000) {
            if (!run.clicked && s.blockedReason != null) finish("条件不满足", s.blockedReason!!)
            else finish(if (run.launched || run.clicked) "结果未知" else "失败", "执行窗口结束：${s.phase}")
            return
        }
        if (!pm.isInteractive || km.isKeyguardLocked) {
            phase("等待屏幕点亮与无密码解锁")
            if (km.isDeviceSecure) { finish("失败", "手机存在密码锁，无法无人值守解锁"); return }
            if (nowElapsed - s.beganElapsed > 2500 && s.swipes < 2 && nowElapsed - s.lastSwipe > 3000) { swipeUp(); s.swipes++; s.lastSwipe = nowElapsed }
            if (nowElapsed - s.beganElapsed > 10_000) finish("失败", "屏幕或锁屏未就绪")
            return
        }
        if (!run.launched && !s.recovered) {
            // A launch requested before this page actually resumes can be blocked,
            // or overtaken by the delayed wake-page launch. Wait for a visible anchor.
            if (WakeActivity.current?.hasWindowFocus() != true) {
                phase("等待亮屏页面进入前台")
                if (nowElapsed - s.beganElapsed > 12_000) finish("失败", "亮屏页面未能进入前台，请检查后台启动设置")
                return
            }
            val launch = packageManager.getLaunchIntentForPackage(cfg.target)
            if (launch == null) { finish("失败", "找不到目标应用启动入口"); return }
            DakaApp.store.flag(s.id, "launched"); phase("打开目标应用，等待自动打卡")
            s.launchedThisSession = true
            s.launchElapsed = nowElapsed
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); removeWakeOverlay()
            handler.postDelayed({ if (session?.id == s.id) WakeActivity.current?.finish() }, 600)
            return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != cfg.target) {
            if (s.recovered || nowElapsed - s.launchElapsed > 15_000) finish("结果未知", "目标应用未处于前台，请检查启动权限")
            return
        }
        s.targetSeen = true
        val nodes = collect(root)
        val texts = nodes.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.distinct()
        val joined = texts.joinToString("\n")
        if (texts.any { it == cfg.organization }) s.verifiedOrganization = true
        // Only the dated record row has a colon. The card heading's parent also
        // contains the live button clock, which must never be used as a receipt.
        val rows = nodes.filter { it.text?.toString()?.trim()?.let { t -> t.startsWith("今日考勤：") || t.startsWith("今日考勤:") } == true }.map { n ->
            val own = n.text.toString()
            if (Regex("\\d{1,2}:\\d{2}").containsMatchIn(own)) own
            else n.parent?.takeIf { it.childCount <= 8 }?.let { parent ->
                val children = collect(parent, 40)
                val values = children.mapNotNull { child -> child.text?.toString() }
                if (children.size < 40 && values.none { it.contains("上班打卡") || it.contains("下班打卡") || it.contains("打卡成功") }) values.joinToString(" ") else own
            } ?: own
        }
        val successTexts = nodes.filter { it.text?.toString()?.trim() == "打卡成功" }.flatMap { label ->
            var scope = label.parent
            var accepted = emptyList<String>()
            repeat(3) {
                val children = scope?.let { collect(it, 100) }.orEmpty()
                val values = children.mapNotNull { it.text?.toString() }
                if (accepted.isEmpty() && children.size < 100 && values.none { it.contains("工作台") || it.contains("今日考勤") || it.contains("上班打卡") || it.contains("下班打卡") } && values.any { Regex("\\d{1,2}:\\d{2}:\\d{2}").containsMatchIn(it) }) accepted = values
                scope = scope?.parent
            }
            accepted
        }
        val date = Times.calendar(s.occurrence.at)
        val fullChineseDate = "${date.get(java.util.Calendar.YEAR)}年${date.get(java.util.Calendar.MONTH) + 1}月${date.get(java.util.Calendar.DAY_OF_MONTH)}日"
        val recordDateVerified = texts.any { it.contains(Times.day(s.occurrence.at)) || it.contains(fullChineseDate) }
        val receipt = Evidence.find(successTexts, rows, s.occurrence.at, System.currentTimeMillis(), recordDateVerified)
        if (receipt != null) s.receipt = receipt
        if (s.receipt != null && s.verifiedOrganization) {
            val expectedVisible = texts.any { it.contains("${s.occurrence.plan.kind}打卡") }
            val detail = "${s.receipt!!.source}；${if (expectedVisible) "页面类型符合计划，班次以报表核对" else "本次提交已确认，班次归属待核对"}"
            finish("成功", detail, s.receipt!!.at); return
        }
        if (s.receipt != null && texts.any { it == "打卡成功" } && !s.dismissedReceipt) {
            s.dismissedReceipt = true; performGlobalAction(GLOBAL_ACTION_BACK); phase("已捕获本次回执，核对组织工作台"); return
        }
        if (joined.contains("您登录的账号已失效") || joined.contains("您的账号已失效")) {
            if (run.loginSent) { finish("失败", "重新登录后账号再次失效"); return }
            if (!click(nodes, "确定")) phase("账号失效提示无法识别，请人工处理")
            else phase("账号失效，进入登录页")
            return
        }
        if (listOf("密码错误", "账号或密码", "请输入验证码", "人脸验证", "账号已被锁定", "必须更新", "强制更新").any { joined.contains(it) }) { finish("失败", "目标应用需要人工处理登录或更新"); return }
        val edits = nodes.filter { it.className?.toString()?.contains("EditText") == true && it.isVisibleToUser }
        if (edits.isNotEmpty() && texts.any { it == "登录" || it == "登陆" }) {
            phase("处理重新登录")
            if (run.loginSent) return
            val user = cfg.secret("account"); val pass = cfg.secret("password")
            if (user.isBlank() || pass.isBlank()) { finish("失败", "未配置登录账号或密码"); return }
            val password = edits.firstOrNull { it.isPassword } ?: edits.lastOrNull()
            val account = edits.firstOrNull { it != password }
            if (password == null || account == null) { finish("失败", "登录输入框需要适配"); return }
            if (!fill(account, user) || !fill(password, pass)) { finish("失败", "无法填写登录表单"); return }
            val checkboxes = nodes.filter { it.isCheckable && it.isVisibleToUser }
            if (checkboxes.size != 1) { finish("失败", "无法可靠识别协议勾选框，需要适配"); return }
            if (!checkboxes[0].isChecked && !clickNode(checkboxes[0])) { finish("失败", "协议勾选失败"); return }
            DakaApp.store.flag(s.id, "login_sent")
            if (!click(nodes, "登录") && !click(nodes, "登陆")) { finish("失败", "登录按钮无法识别"); return }
            return
        }
        if (!joined.contains("今日考勤")) {
            if (!s.navigated && click(nodes, "工作台")) { s.navigated = true; phase("进入工作台") }
            else phase("等待有效工作台加载")
            return
        }
        if (!s.verifiedOrganization || !texts.any { it == cfg.organization }) { phase("当前组织未核对，等待目标工作台"); return }
        if (s.workbenchSince == 0L) s.workbenchSince = nowElapsed
        if (run.clicked || s.recovered) { phase("等待本次提交回执，避免重复提交"); return }
        if (nowElapsed - s.workbenchSince < 15_000) { phase("观察自动打卡结果"); return }
        if (listOf("未连接打卡", "不在打卡范围", "获取当前位置失败", "手机未开启", "外勤打卡").any { joined.contains(it) }) {
            s.blockedReason = if (joined.contains("外勤打卡")) "页面显示外勤打卡，等待实际地点及指定网络后再验证" else "现场定位或指定打卡网络条件未满足"
            phase("等待定位与打卡网络就绪"); return
        }
        s.blockedReason = null
        val opposite = if (s.occurrence.plan.kind == "上班") "下班打卡" else "上班打卡"
        if (texts.any { it.startsWith(opposite) }) { finish("失败", "当前按钮类型与计划不一致"); return }
        val expected = "${s.occurrence.plan.kind}打卡"
        val buttons = nodes.filter { it.isVisibleToUser && (it.text?.toString()?.trim()?.let { t -> t == expected || t.startsWith("$expected\n") || t.startsWith("$expected ") } == true) }
        if (buttons.size != 1) {
            if (!s.scrolled) { nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); s.scrolled = true }
            phase("等待唯一的打卡按钮，可能需要页面适配"); return
        }
        if (!joined.contains("已连接上打卡WiFi") && !joined.contains("已连接打卡WiFi") && cfg.text("allowGps") != "true") { s.blockedReason = "未确认连接指定打卡 Wi-Fi"; phase("等待已连接打卡 Wi-Fi 提示"); return }
        if (s.deadlineElapsed - nowElapsed < 5000) { phase("剩余时间不足，等待自动回执"); return }
        DakaApp.store.flag(s.id, "clicked"); phase("提交一次备用按钮点击")
        if (!clickNode(buttons.single())) finish("结果未知", "按钮点击无法确认，保留待核对状态")
    }
    private fun phase(message: String) { val s = session ?: return; if (s.phase != message) { s.phase = message; DakaApp.store.step(s.id, message) } }
    private fun fill(node: AccessibilityNodeInfo, text: String) = node.text?.toString() == text || node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
    private fun click(nodes: List<AccessibilityNodeInfo>, text: String): Boolean {
        val found = nodes.filter { it.isVisibleToUser && (it.text?.toString()?.trim() == text || it.contentDescription?.toString()?.trim() == text) }
        return found.size == 1 && clickNode(found.single())
    }
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) { val n = current ?: return false; if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK); current = n.parent }
        return false
    }
    private fun collect(root: AccessibilityNodeInfo, limit: Int = 1500): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(); val queue = java.util.ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty() && result.size < limit) { val n = queue.removeFirst(); result.add(n); for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) } }
        return result
    }
    private fun swipeUp() {
        if (Build.VERSION.SDK_INT < 24) return
        val d = resources.displayMetrics; val path = Path().apply { moveTo(d.widthPixels * .5f, d.heightPixels * .83f); lineTo(d.widthPixels * .5f, d.heightPixels * .2f) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 450)).build(), null, null)
    }
    private fun finish(outcome: String, detail: String, receipt: Long = 0) {
        val s = session ?: return
        session = null; handler.removeCallbacks(tick)
        DakaApp.store.finish(s.id, outcome, detail, receipt)
        WakeActivity.current?.finish(); release()
        var requested = false
        if (Build.VERSION.SDK_INT >= 28) requested = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        if (!requested) {
            val admin = getSystemService(DevicePolicyManager::class.java)
            if (admin.isAdminActive(ComponentName(this, ScreenAdmin::class.java))) {
                try { admin.lockNow(); requested = true } catch (_: SecurityException) { }
            }
        }
        val used = requested
        handler.postDelayed({
            val off = !getSystemService(PowerManager::class.java).isInteractive
            DakaApp.store.sleepResult(s.id, if (off) "已熄屏" else if (used) "熄屏请求未生效，请检查" else "使用系统超时熄屏")
            UploadJob.kick(this)
        }, 700)
        UploadJob.kick(this)
    }
    fun diagnostic(): String {
        val root = rootInActiveWindow ?: return "无前台窗口"
        if (root.packageName?.toString() != DakaApp.config.target) return "请先打开配置的目标应用，再采集结构"
        return collect(root).joinToString("\n") { n ->
            val r = Rect(); n.getBoundsInScreen(r)
            val t = n.text?.toString().orEmpty()
            val safe = if (t in listOf("工作台", "今日考勤", "上班打卡", "下班打卡", "打卡成功", "登录", "确定")) t else "[文本长度 ${t.length}]"
            "${n.className} id=${n.viewIdResourceName} bounds=$r click=${n.isClickable} check=${n.isCheckable} password=${n.isPassword} $safe"
        }
    }
}
