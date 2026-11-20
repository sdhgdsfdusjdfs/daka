package cn.daka.local

object RetryPolicy {
    const val BUFFER_MILLIS = 8_000L
    const val MAX_ATTEMPTS = 3
    const val MIN_RETRY_BUDGET = 19_000L

    fun canRetry(attempts: Int, remainingMillis: Long): Boolean =
        attempts < MAX_ATTEMPTS && remainingMillis >= MIN_RETRY_BUDGET
}

object ExitEvidence {
    fun hasSuccessPrompt(texts: List<String>) = texts.any { it == "打卡成功" }
    fun isWorkbench(texts: List<String>, organization: String): Boolean =
        organization.isNotBlank() && texts.any { it == organization } &&
            texts.any { it.contains("今日考勤") } && !hasSuccessPrompt(texts) &&
            texts.any { text -> listOf("上班打卡", "下班打卡", "外勤打卡").any(text::startsWith) }
}
