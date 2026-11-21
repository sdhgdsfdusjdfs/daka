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

private enum class RunStage { WAKING, READY, ACTIVE, CLEANUP }

private data class Session(val id: String, val occurrence: Occurrence, val manual: Boolean, val deadlineElapsed: Long,
    val target: String, val organization: String,
    val beganElapsed: Long = SystemClock.elapsedRealtime(),
    var verifiedOrganization: Boolean = false, var receipt: Receipt? = null, var dismissedReceipt: Boolean = false,
    var workbenchSince: Long = 0, var swipes: Int = 0, var lastSwipe: Long = 0, var navigated: Boolean = false,
    var scrolled: Boolean = false, var phase: String = "", var launchElapsed: Long = 0,
    var blockedReason: String? = null,
    var stage: RunStage = RunStage.WAKING,
    var attemptNumber: Int = 0,
    var attemptStartedAt: Long = beganElapsed,
    var exit: AppExit? = null,
    var loginAttemptSent: Boolean = false,
    var lastSubmissionAt: Long = 0)

class DakaAccessibility : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var lock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var wakeOverlay: TextView? = null
    private var testing = false
    private var exitOnlyTest = false
    private var testExit: AppExit? = null
    private var testLaunchElapsed = 0L
    private var testTarget = ""
    private var testOrganization = ""
    private var testUntil = 0L
    private var testSwipes = 0
    private val tick = object : Runnable {
        override fun run() {
            inspectSafely()
            handler.removeCallbacks(this)
            if (session != null || testing) handler.postDelayed(this, 700)
        }
    }
    companion object { var instance: DakaAccessibility? = null; private set }

    override fun onServiceConnected() { instance = this; Scheduler.reschedule(this, true); UploadJob.kick(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* The bounded loop reads fresh nodes; event text is never logged. */ }
    override fun onInterrupt() { if (session != null) finish("结果未知", "无障碍服务被中断") }
    override fun onDestroy() {
        session?.let {
            if (it.stage == RunStage.CLEANUP) DakaApp.store.cleanupResult(it.id, "退出收尾被系统中断")
            else DakaApp.store.finish(it.id, "结果未知", "无障碍服务结束，需核对本次记录")
        }
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
        session = Session(id, o, manual, SystemClock.elapsedRealtime() + remaining, cfg.target, cfg.organization)
        prepareAttempt("开始本轮任务")
        handler.removeCallbacks(tick)
        handler.post(tick)
        UploadJob.kick(this)
    }

    private fun prepareAttempt(reason: String) {
        val current = session ?: return
        val stored = requireNotNull(DakaApp.store.run(current.id))
        if (stored.attempts >= RetryPolicy.MAX_ATTEMPTS) {
            finish("结果未知", "已达到本轮最多尝试次数")
            return
        }
        current.attemptNumber = DakaApp.store.startAttempt(current.id)
        current.attemptStartedAt = SystemClock.elapsedRealtime()
        current.stage = RunStage.WAKING
        current.receipt = null
        current.dismissedReceipt = false
        current.verifiedOrganization = false
        current.workbenchSince = 0
        current.swipes = 0
        current.lastSwipe = 0
        current.navigated = false
        current.scrolled = false
        current.loginAttemptSent = false
        current.blockedReason = null
        current.exit = null
        WakeActivity.current?.finish()
        phase("第${current.attemptNumber}次尝试：$reason")
        try {
            hold((current.deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1) + 12_000)
            showWakeOverlay()
            // Start while the alarm receiver's temporary execution allowance is still active.
            launchWakePage()
        } catch (_: Exception) {
            retryOrFinish("系统阻止后台亮屏，请检查权限")
        }
    }

    private fun retryOrFinish(reason: String, fallback: String = "结果未知") {
        val current = session ?: return
        if (current.receipt != null && current.verifiedOrganization) {
            finish("成功", "已确认本次回执；班次归属以单位记录核对", current.receipt!!.at)
            return
        }
        DakaApp.store.note(current.id, "第${current.attemptNumber}次异常：$reason")
        if (RetryPolicy.canRetry(current.attemptNumber, current.deadlineElapsed - SystemClock.elapsedRealtime())) {
            prepareAttempt("异常重试 · $reason")
        } else {
            finish(if (current.blockedReason != null) "条件不满足" else fallback, reason)
        }
    }

    fun wakeTest(exitTest: Boolean = false) {
        if (session != null || testing) return
        exitOnlyTest = exitTest
        testExit = null
        testLaunchElapsed = 0L
        testTarget = DakaApp.config.target
        testOrganization = DakaApp.config.organization
        testing = true; testUntil = SystemClock.elapsedRealtime() + if (exitTest) 30_000 else 15_000; testSwipes = 0
        hold(if (exitTest) 35_000 else 20_000)
        DakaApp.config.set(if (exitTest) "stopTestResult" else "wakeTestResult", "等待验证")
        try { showWakeOverlay(); launchWakePage() }
        catch (_: Exception) { stopTest("失败：系统阻止后台打开测试页"); return }
        handler.post(tick)
    }
    fun testExitTarget() {
        if (session != null || testing || DakaApp.config.target.isBlank()) return
        wakeTest(true)
    }
    fun cancelRun() { if (session != null) finish("结果未知", "用户停止了当前操作，请核对已提交记录"); if (testing) stopTest("测试已取消") }
    @Suppress("DEPRECATION") private fun hold(timeout: Long) {
        release()
        val pm = getSystemService(PowerManager::class.java)
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:execution").apply { acquire(timeout) }
        // The activity's turnScreenOn path can be blocked while the display is off on OEM systems.
        // Keep this separate from the CPU lock: ACQUIRE_CAUSES_WAKEUP cannot accompany PARTIAL_WAKE_LOCK.
        if (!pm.isInteractive) {
            runCatching {
                screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "$packageName:screen-wake").apply {
                    // Older vivo builds can immediately return to sleep when the display lock ends.
                    // Keep it for the bounded task on those devices, then release during cleanup.
                    acquire(if (Build.VERSION.SDK_INT <= 28) timeout else 8_000)
                }
            }.onFailure { DakaApp.config.set("wakeDiagnostic", "系统拒绝直接亮屏请求") }
        }
    }
    private fun launchWakePage() {
        if (!testing && session?.stage !in setOf(RunStage.WAKING, RunStage.READY)) return
        try {
            Log.i("Daka", "Opening wake page; interactive=${getSystemService(PowerManager::class.java).isInteractive}")
            DakaApp.config.set("wakeDiagnostic", "${Times.format(System.currentTimeMillis())} 请求唤醒页；亮屏=${getSystemService(PowerManager::class.java).isInteractive}")
            startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Exception) {
            if (testing) stopTest("失败：后台亮屏页面被系统阻止") else retryOrFinish("后台亮屏页面被系统阻止", "失败")
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
    private fun release() {
        removeWakeOverlay()
        screenLock?.let { if (it.isHeld) it.release() }; screenLock = null
        lock?.let { if (it.isHeld) it.release() }; lock = null
    }
    private fun stopTest(message: String) {
        testing = false
        DakaApp.config.set(if (exitOnlyTest) "stopTestResult" else "wakeTestResult", "${Times.format(System.currentTimeMillis())} $message")
        testExit = null
        Log.i("Daka", "Wake test: $message")
        WakeActivity.current?.finish(); release(); DakaApp.store.dirtyStatus(); UploadJob.kick(this)
    }
    private fun inspectSafely() {
        try { inspect() } catch (_: Exception) {
            if (session?.stage == RunStage.CLEANUP) completeCleanup("退出失败：返回工作台操作异常")
            else if (session != null) retryOrFinish("页面操作异常")
            if (testing) stopTest("失败：测试过程异常")
        }
    }
    private fun inspect() {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (testing) {
            if (exitOnlyTest && pm.isInteractive && !km.isKeyguardLocked) {
                if (testLaunchElapsed == 0L && WakeActivity.current?.hasWindowFocus() == true) {
                    val launch = packageManager.getLaunchIntentForPackage(testTarget)
                    if (launch == null) { stopTest("失败：找不到目标应用启动入口"); return }
                    testLaunchElapsed = nowElapsed
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    handler.postDelayed({
                        if (testing && exitOnlyTest) { WakeActivity.current?.finish(); removeWakeOverlay() }
                    }, 600)
                    return
                }
                if (testLaunchElapsed != 0L && nowElapsed - testLaunchElapsed >= RetryPolicy.BUFFER_MILLIS) {
                    if (testExit == null) testExit = AppExit(this, testTarget, testOrganization, nowElapsed)
                    if (testExit?.step(nowElapsed) == AppExit.Result.DONE) {
                        val detail = testExit?.detail.orEmpty()
                        stopTest(detail)
                        requestScreenOff { result ->
                            DakaApp.config.set("exitTestSleepResult", result)
                            DakaApp.store.dirtyStatus(); UploadJob.kick(this)
                        }
                        return
                    }
                }
            }
            if (!exitOnlyTest && pm.isInteractive && !km.isKeyguardLocked && WakeActivity.current?.hasWindowFocus() == true) { stopTest("成功：定时亮屏并解除无密码锁屏"); return }
            if (nowElapsed >= testUntil) {
                stopTest("失败：亮屏=${pm.isInteractive}，锁屏=${km.isKeyguardLocked}，测试页=${WakeActivity.current != null}，焦点=${WakeActivity.current?.hasWindowFocus() == true}")
                return
            }
            if (km.isKeyguardLocked && testSwipes < 2 && nowElapsed >= testUntil - (if (exitOnlyTest) 27_000 else 12_000) + testSwipes * 4000) { swipeUp(); testSwipes++ }
            return
        }
        val s = session ?: return
        val cfg = DakaApp.config
        val run = requireNotNull(DakaApp.store.run(s.id))
        if (s.stage == RunStage.CLEANUP) {
            if (s.exit?.step(nowElapsed) == AppExit.Result.DONE) completeCleanup(s.exit?.detail.orEmpty())
            return
        }
        if (s.receipt != null && s.verifiedOrganization &&
            nowElapsed - s.launchElapsed >= RetryPolicy.BUFFER_MILLIS) {
            finish("成功", "已确认本次回执；班次归属以单位记录核对", s.receipt!!.at)
            return
        }
        if (!s.manual && !cfg.enabled) {
            finish(if (run.launched) "结果未知" else "已暂停", "总开关已关闭")
            return
        }
        if (nowElapsed >= s.deadlineElapsed || System.currentTimeMillis() >= s.occurrence.at + 60_000) {
            if (!run.clicked && s.blockedReason != null) finish("条件不满足", s.blockedReason!!)
            else finish(if (run.launched || run.clicked) "结果未知" else "失败", "执行窗口结束：${s.phase}")
            return
        }
        if (!pm.isInteractive || km.isKeyguardLocked) {
            phase("等待屏幕点亮与无密码解锁")
            if (km.isDeviceSecure) { retryOrFinish("手机存在密码锁", "失败"); return }
            if (nowElapsed - s.attemptStartedAt > 2500 && s.swipes < 2 && nowElapsed - s.lastSwipe > 3000) {
                swipeUp(); s.swipes++; s.lastSwipe = nowElapsed
            }
            if (nowElapsed - s.attemptStartedAt > 10_000) retryOrFinish("屏幕或锁屏未就绪", "失败")
            return
        }
        if (s.stage == RunStage.WAKING) {
            if (WakeActivity.current?.hasWindowFocus() != true) {
                if (nowElapsed - s.attemptStartedAt > 12_000) retryOrFinish("亮屏页面未进入前台", "失败")
                return
            }
            s.stage = RunStage.READY
            s.launchElapsed = nowElapsed
            phase("第${s.attemptNumber}次：准备从启动入口重新打开页面")
            return
        }
        if (s.stage == RunStage.READY) {
            if (WakeActivity.current?.hasWindowFocus() != true) {
                if (nowElapsed - s.launchElapsed > 5_000) retryOrFinish("退出后未能恢复前台启动页面", "失败")
                return
            }
            val launch = packageManager.getLaunchIntentForPackage(s.target)
            if (launch == null) { retryOrFinish("找不到目标应用启动入口", "失败"); return }
            DakaApp.store.flag(s.id, "launched")
            s.launchElapsed = nowElapsed
            s.stage = RunStage.ACTIVE
            phase("第${s.attemptNumber}次：重新打开，缓冲8秒")
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            removeWakeOverlay()
            val attempt = s.attemptNumber
            handler.postDelayed({
                if (session?.id == s.id && session?.attemptNumber == attempt && session?.stage == RunStage.ACTIVE) WakeActivity.current?.finish()
            }, 600)
            return
        }
        val age = nowElapsed - s.launchElapsed
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != s.target) {
            if (age >= RetryPolicy.BUFFER_MILLIS + 1500) retryOrFinish("得力未在前台或已跳转其他软件")
            return
        }
        val nodes = collect(root)
        val texts = nodes.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.distinct()
        val joined = texts.joinToString("\n")
        if (texts.any { it == s.organization }) s.verifiedOrganization = true
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
        if (s.receipt != null && s.verifiedOrganization && age >= RetryPolicy.BUFFER_MILLIS) {
            val expectedVisible = texts.any { it.contains("${s.occurrence.plan.kind}打卡") }
            val detail = "${s.receipt!!.source}；${if (expectedVisible) "页面类型符合计划，班次以报表核对" else "本次提交已确认，班次归属待核对"}"
            finish("成功", detail, s.receipt!!.at); return
        }
        // Read short-lived success cards during the buffer, but defer all taps.
        if (age < RetryPolicy.BUFFER_MILLIS) return
        if (age >= 18_000 && nowElapsed - s.lastSubmissionAt >= 5_000 &&
            RetryPolicy.canRetry(s.attemptNumber, s.deadlineElapsed - nowElapsed)) {
            retryOrFinish(s.blockedReason ?: "本次未取得可确认的成功回执")
            return
        }
        if (s.receipt != null && texts.any { it == "打卡成功" } && !s.dismissedReceipt) {
            s.dismissedReceipt = true; performGlobalAction(GLOBAL_ACTION_BACK); phase("已捕获本次回执，核对组织工作台"); return
        }
        if (joined.contains("您登录的账号已失效") || joined.contains("您的账号已失效")) {
            if (s.loginAttemptSent) { retryOrFinish("重新登录后账号再次失效", "失败"); return }
            if (!click(nodes, "确定")) phase("账号失效提示无法识别，请人工处理")
            else phase("账号失效，进入登录页")
            return
        }
        if (listOf("密码错误", "账号或密码", "请输入验证码", "人脸验证", "账号已被锁定", "必须更新", "强制更新").any { joined.contains(it) }) { retryOrFinish("目标应用需要人工处理登录或更新", "失败"); return }
        val edits = nodes.filter { it.className?.toString()?.contains("EditText") == true && it.isVisibleToUser }
        if (edits.isNotEmpty() && texts.any { it == "登录" || it == "登陆" }) {
            phase("处理重新登录")
            if (s.loginAttemptSent) return
            val user = cfg.secret("account"); val pass = cfg.secret("password")
            if (user.isBlank() || pass.isBlank()) { retryOrFinish("未配置登录账号或密码", "失败"); return }
            val password = edits.firstOrNull { it.isPassword } ?: edits.lastOrNull()
            val account = edits.firstOrNull { it != password }
            if (password == null || account == null) { retryOrFinish("登录输入框需要适配", "失败"); return }
            if (!fill(account, user) || !fill(password, pass)) { retryOrFinish("无法填写登录表单", "失败"); return }
            val checkboxes = nodes.filter { it.isCheckable && it.isVisibleToUser }
            if (checkboxes.size != 1) { retryOrFinish("无法可靠识别协议勾选框，需要适配", "失败"); return }
            if (!checkboxes[0].isChecked && !clickNode(checkboxes[0])) { retryOrFinish("协议勾选失败", "失败"); return }
            DakaApp.store.flag(s.id, "login_sent")
            s.loginAttemptSent = true
            s.lastSubmissionAt = nowElapsed
            if (!click(nodes, "登录") && !click(nodes, "登陆")) { retryOrFinish("登录按钮无法识别", "失败"); return }
            return
        }
        if (!joined.contains("今日考勤")) {
            if (!s.navigated && click(nodes, "工作台")) { s.navigated = true; phase("进入工作台") }
            else phase("等待有效工作台加载")
            return
        }
        if (!s.verifiedOrganization || !texts.any { it == s.organization }) { phase("当前组织未核对，等待目标工作台"); return }
        if (s.workbenchSince == 0L) s.workbenchSince = nowElapsed
        if (run.clicked) { phase("等待本次提交回执，避免重复提交"); return }
        if (nowElapsed - s.workbenchSince < 5_000) { phase("观察自动打卡结果"); return }
        if (listOf("未连接打卡", "不在打卡范围", "获取当前位置失败", "手机未开启", "外勤打卡").any { joined.contains(it) }) {
            s.blockedReason = if (joined.contains("外勤打卡")) "页面显示外勤打卡，等待实际地点及指定网络后再验证" else "现场定位或指定打卡网络条件未满足"
            phase("等待定位与打卡网络就绪"); return
        }
        s.blockedReason = null
        val opposite = if (s.occurrence.plan.kind == "上班") "下班打卡" else "上班打卡"
        if (texts.any { it.startsWith(opposite) }) { retryOrFinish("当前按钮类型与计划不一致", "失败"); return }
        val expected = "${s.occurrence.plan.kind}打卡"
        val buttons = nodes.filter { it.isVisibleToUser && (it.text?.toString()?.trim()?.let { t -> t == expected || t.startsWith("$expected\n") || t.startsWith("$expected ") } == true) }
        if (buttons.size != 1) {
            if (!s.scrolled) { nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); s.scrolled = true }
            phase("等待唯一的打卡按钮，可能需要页面适配"); return
        }
        if (!joined.contains("已连接上打卡WiFi") && !joined.contains("已连接打卡WiFi") && cfg.text("allowGps") != "true") { s.blockedReason = "未确认连接指定打卡 Wi-Fi"; phase("等待已连接打卡 Wi-Fi 提示"); return }
        if (s.deadlineElapsed - nowElapsed < 5000) { phase("剩余时间不足，等待自动回执"); return }
        DakaApp.store.flag(s.id, "clicked")
        s.lastSubmissionAt = nowElapsed
        phase("提交一次备用按钮点击")
        if (!clickNode(buttons.single())) retryOrFinish("按钮点击无法确认，保留待核对状态")
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
        val current = session ?: return
        if (current.stage == RunStage.CLEANUP) return
        DakaApp.store.finish(current.id, outcome, detail, receipt)
        DakaApp.store.note(current.id, "本轮结束，正在退出收尾")
        current.stage = RunStage.CLEANUP
        handler.removeCallbacks(tick)
        WakeActivity.current?.finish()
        try {
            showWakeOverlay()
            current.exit = AppExit(this, current.target, current.organization, SystemClock.elapsedRealtime())
            handler.postDelayed(tick, 400)
        } catch (_: Exception) {
            completeCleanup("退出失败：无法开始页面收尾")
        }
        UploadJob.kick(this)
    }

    private fun completeCleanup(result: String) {
        val current = session ?: return
        session = null
        handler.removeCallbacks(tick)
        DakaApp.store.cleanupResult(current.id, result)
        DakaApp.store.note(current.id, result)
        WakeActivity.current?.finish()
        release()
        requestScreenOff { result ->
            DakaApp.store.sleepResult(current.id, result)
            UploadJob.kick(this)
        }
        UploadJob.kick(this)
    }

    private fun requestScreenOff(onResult: (String) -> Unit) {
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
            onResult(if (off) "已熄屏" else if (used) "熄屏请求未生效，请检查" else "使用系统超时熄屏")
        }, 700)
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
