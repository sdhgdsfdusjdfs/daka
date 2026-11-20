package cn.daka.local

object RetryPolicy {
    const val BUFFER_MILLIS = 8_000L
    const val MAX_ATTEMPTS = 3
    const val MIN_RETRY_BUDGET = 19_000L

    fun canRetry(attempts: Int, remainingMillis: Long): Boolean =
        attempts < MAX_ATTEMPTS && remainingMillis >= MIN_RETRY_BUDGET
}

object StopSafety {
    val stopLabels = setOf("强行停止", "强制停止", "结束运行", "Force stop", "FORCE STOP")

    fun isTargetHeader(settingsPackage: String, header: String, targetLabel: String): Boolean =
        settingsPackage == "com.android.settings" && header == targetLabel && targetLabel.isNotBlank()

    fun mayConfirm(settingsPackage: String, targetWasVerified: Boolean, dialogText: String): Boolean =
        settingsPackage == "com.android.settings" && targetWasVerified &&
            stopLabels.any { dialogText.contains(it, ignoreCase = true) }
}
