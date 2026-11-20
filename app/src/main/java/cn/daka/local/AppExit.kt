package cn.daka.local

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Return to the verified workbench, then Home. Never clicks a punch button or kills the app. */
class AppExit(private val service: AccessibilityService, private val target: String,
    private val organization: String, private val startedAt: Long) {
    enum class Result { WAITING, DONE }
    private var lastActionAt = 0L
    private var backCount = 0
    private var homeRequested = false
    private var workbenchVerified = false
    var detail = "正在返回工作台和桌面"
        private set

    fun step(now: Long): Result {
        val root = service.windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)
        }.mapNotNull { it.root }.firstOrNull() ?: service.rootInActiveWindow
        val pkg = root?.packageName?.toString().orEmpty()
        if (homeRequested && now - lastActionAt >= 600 && pkg.isNotBlank() && pkg != target && pkg != service.packageName) {
            detail = if (workbenchVerified) "已回到工作台并退到后台" else "已退到后台，未确认工作台页面"
            return Result.DONE
        }
        if (now - startedAt >= 8_000) {
            if (!homeRequested) homeRequested = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            detail = if (homeRequested) "已请求返回桌面，页面收尾未完全确认" else "退出收尾失败：无法返回桌面"
            return Result.DONE
        }
        if (now - lastActionAt < 700) return Result.WAITING
        if (homeRequested) return Result.WAITING
        if (root == null) return Result.WAITING
        if (pkg != target) {
            requestHome(now)
            return Result.WAITING
        }
        val nodes = nodes(root)
        val texts = nodes.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotBlank() }
        if (ExitEvidence.isWorkbench(texts, organization)) {
            workbenchVerified = true
            requestHome(now)
            return Result.WAITING
        }
        if (!ExitEvidence.hasSuccessPrompt(texts)) {
            val workbench = nodes.filter { it.isVisibleToUser && (it.text?.toString()?.trim() == "工作台" || it.contentDescription?.toString()?.trim() == "工作台") }
            if (workbench.size == 1 && click(workbench.single())) {
                lastActionAt = now
                return Result.WAITING
            }
        }
        if (backCount < 3) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            backCount++
            lastActionAt = now
        } else requestHome(now)
        return Result.WAITING
    }

    private fun requestHome(now: Long) {
        homeRequested = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        lastActionAt = now
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val n = current ?: return false
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = n.parent
        }
        return false
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty() && result.size < 1_000) {
            val node = queue.removeFirst(); result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return result
    }
}
