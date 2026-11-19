package cn.daka.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Run(val id: String, val planId: String, val name: String, val kind: String, val scheduled: Long, val started: Long,
    val outcome: String, val detail: String, val clicked: Boolean, val launched: Boolean, val loginSent: Boolean, val ended: Long, val receipt: Long, val sleep: String)

class Store(context: Context) : SQLiteOpenHelper(context, "daka.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runs(id TEXT PRIMARY KEY,plan_id TEXT,name TEXT,kind TEXT,scheduled INTEGER,started INTEGER,outcome TEXT,detail TEXT,clicked INTEGER DEFAULT 0,launched INTEGER DEFAULT 0,login_sent INTEGER DEFAULT 0,ended INTEGER DEFAULT 0,receipt INTEGER DEFAULT 0,sleep TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE steps(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT,at INTEGER,message TEXT)")
        db.execSQL("CREATE TABLE sync(path TEXT PRIMARY KEY,revision INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    private fun read(c: Cursor) = Run(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getString(6), c.getString(7), c.getInt(8) != 0, c.getInt(9) != 0, c.getInt(10) != 0, c.getLong(11), c.getLong(12), c.getString(13))
    @Synchronized fun run(id: String): Run? = readableDatabase.rawQuery("SELECT * FROM runs WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) read(it) else null }
    @Synchronized fun recent(limit: Int = 100): List<Run> = readableDatabase.rawQuery("SELECT * FROM runs ORDER BY scheduled DESC LIMIT ?", arrayOf(limit.toString())).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    @Synchronized fun month(month: String): List<Run> = recent(20000).filter { Times.month(it.scheduled) == month }.sortedBy { it.scheduled }
    @Synchronized fun begin(id: String, o: Occurrence): Run {
        val v = ContentValues().apply { put("id", id); put("plan_id", o.plan.id); put("name", o.plan.name); put("kind", o.plan.kind); put("scheduled", o.at); put("started", System.currentTimeMillis()); put("outcome", "运行中"); put("detail", "正在唤醒手机") }
        if (writableDatabase.insertWithOnConflict("runs", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) step(id, "任务开始")
        return requireNotNull(run(id))
    }
    private fun dirty(path: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO sync(path,revision) VALUES(?,0)", arrayOf(path))
        writableDatabase.execSQL("UPDATE sync SET revision=revision+1 WHERE path=?", arrayOf(path))
    }
    @Synchronized fun dirtyStatus() = dirty("status.md")
    @Synchronized fun dirtyAll() { dirtyStatus(); recent(20000).map { Times.month(it.scheduled) }.distinct().forEach { dirty("logs/$it.md") } }
    private fun dirtyRun(id: String) { dirtyStatus(); run(id)?.let { dirty("logs/${Times.month(it.scheduled)}.md") } }
    @Synchronized fun step(id: String, message: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insert("steps", null, ContentValues().apply { put("run_id", id); put("at", System.currentTimeMillis()); put("message", message) })
            writableDatabase.update("runs", ContentValues().apply { put("detail", message) }, "id=?", arrayOf(id))
            dirtyRun(id); writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun flag(id: String, column: String) {
        require(column in setOf("clicked", "launched", "login_sent"))
        writableDatabase.update("runs", ContentValues().apply { put(column, 1) }, "id=?", arrayOf(id))
    }
    @Synchronized fun finish(id: String, outcome: String, detail: String, receipt: Long = 0) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("runs", ContentValues().apply { put("outcome", outcome); put("ended", System.currentTimeMillis()); put("receipt", receipt) }, "id=? AND outcome='运行中'", arrayOf(id))
            step(id, detail); writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun sleepResult(id: String, result: String) {
        writableDatabase.update("runs", ContentValues().apply { put("sleep", result) }, "id=?", arrayOf(id)); dirtyRun(id)
    }
    @Synchronized fun steps(id: String): String = readableDatabase.rawQuery("SELECT at,message FROM steps WHERE run_id=? ORDER BY id", arrayOf(id)).use { c -> buildString { while (c.moveToNext()) append("${Times.format(c.getLong(0))} ${c.getString(1)}\n") } }
    @Synchronized fun pending(): List<Pair<String, Long>> = readableDatabase.rawQuery("SELECT path,revision FROM sync ORDER BY CASE WHEN path='status.md' THEN 0 ELSE 1 END", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
    @Synchronized fun acknowledge(path: String, revision: Long) { writableDatabase.delete("sync", "path=? AND revision=?", arrayOf(path, revision.toString())) }
    @Synchronized fun prune() {
        val cutoff = System.currentTimeMillis() - 90L * 86_400_000
        // Preserve pending months until their logs have been acknowledged.
        val protected = pending().map { it.first.removePrefix("logs/").removeSuffix(".md") }.toSet()
        recent(20000).filter { it.scheduled < cutoff && it.outcome != "运行中" && Times.month(it.scheduled) !in protected }.forEach {
            writableDatabase.delete("steps", "run_id=?", arrayOf(it.id)); writableDatabase.delete("runs", "id=?", arrayOf(it.id))
        }
    }
}
