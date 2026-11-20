package cn.daka.local

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/** A bounded, visible system-settings operation. Completion requires FLAG_STOPPED. */
class AppStopper(
    private val service: AccessibilityService,
    private val target: String,
    private val startedAt: Long
) {
    enum class Result { WAITING, STOPPED, FAILED }
    private var opened = false
    private var targetVerified = false
    private var clickedStop = false
    private var confirmed = false
    private val targetLabel = service.packageManager.getApplicationInfo(target, 0)
        .loadLabel(service.packageManager).toString()

    fun step(now: Long): Result {
        val info = service.packageManager.getApplicationInfo(target, 0)
        if (info.flags and ApplicationInfo.FLAG_STOPPED != 0) return Result.STOPPED
        if (now - startedAt >= 8_000) return Result.FAILED
        if (!opened) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$target"))
                .setPackage("com.android.settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            opened = true
            return Result.WAITING
        }
        val root = service.rootInActiveWindow ?: return Result.WAITING
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName != "com.android.settings") return Result.WAITING
        val nodes = nodes(root)
        val header = nodes.firstOrNull {
            it.viewIdResourceName in setOf("com.android.settings:id/entity_header_title", "android:id/app_name")
        }?.text?.toString()
        if (header != null && header != targetLabel) return Result.FAILED
        if (StopSafety.isTargetHeader(packageName, header.orEmpty(), targetLabel)) targetVerified = true
        // Older settings pages may expose the app name using an unlabelled title node.
        if (header == null && nodes.any { it.text?.toString() == targetLabel } &&
            nodes.any { it.text?.toString() in StopSafety.stopLabels }) targetVerified = true

        val text = nodes.mapNotNull { it.text?.toString() }.joinToString("\n")
        val positive = nodes.singleOrNull { it.viewIdResourceName == "android:id/button1" && it.isEnabled }
        if (clickedStop && !confirmed && positive != null && StopSafety.mayConfirm(packageName, targetVerified, text)) {
            confirmed = positive.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return Result.WAITING
        }
        if (!clickedStop && targetVerified) {
            val stopLabels = nodes.filter { it.isVisibleToUser && it.text?.toString()?.trim() in StopSafety.stopLabels }
            if (stopLabels.size == 1) clickedStop = clickButton(stopLabels.single())
        }
        return Result.WAITING
    }

    private fun clickButton(label: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = label
        var fallback: AccessibilityNodeInfo? = null
        repeat(5) {
            val current = node ?: return@repeat
            if (current.className?.toString() == "android.widget.Button") {
                return current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (current.isClickable && current.isEnabled) fallback = current
            node = current.parent
        }
        return fallback?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < 500) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return result
    }
}
