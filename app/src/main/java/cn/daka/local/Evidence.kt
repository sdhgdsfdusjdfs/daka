package cn.daka.local

data class Receipt(val at: Long, val source: String)
object Evidence {
    fun find(texts: List<String>, recordRows: List<String>, scheduled: Long, now: Long, recordDateVerified: Boolean = false): Receipt? {
        if (Times.day(now) != Times.day(scheduled)) return null
        if (texts.any { it.trim() == "打卡成功" }) {
            texts.forEach { text -> Times.receiptTime(text.trim(), scheduled, true)?.let { if (it <= now + 1000) return Receipt(it, "本次成功回执") } }
        }
        for (row in recordRows) if (recordDateVerified && row.contains("今日考勤")) {
            Times.receiptTime(row, scheduled, false)?.let { if (it <= now) return Receipt(it, "今日考勤记录") }
        }
        return null
    }
}
