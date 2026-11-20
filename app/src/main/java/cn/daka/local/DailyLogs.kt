package cn.daka.local

object DailyLogs {
    fun path(timestamp: Long): String = "logs/${Times.day(timestamp)}.md"

    // SQLiteOpenHelper runs these statements in its upgrade transaction.
    // Existing runs stay intact; all their dates become eligible for upload.
    val migrationSql = listOf(
        """DELETE FROM sync WHERE path GLOB 'logs/????-??.md'""",
        """INSERT OR IGNORE INTO sync(path,revision) SELECT 'logs/' || strftime('%Y-%m-%d',scheduled/1000,'unixepoch','+8 hours') || '.md',1 FROM runs""",
        """INSERT OR IGNORE INTO sync(path,revision) VALUES('status.md',1)""",
        """INSERT OR IGNORE INTO sync(path,revision) VALUES('logs/README.md',1)"""
    )
}
