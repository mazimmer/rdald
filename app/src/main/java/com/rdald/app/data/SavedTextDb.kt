package com.rdald.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rdald.app.SavedText

/**
 * Simple SQLite persistence for saved texts (framework SQLiteOpenHelper; no extra deps).
 * Schema v2 adds an `archived` flag for the inbox/archive split.
 */
class SavedTextDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "readaloud.db", null, 3) {

    companion object {
        @Volatile
        private var instance: SavedTextDb? = null

        fun get(context: Context): SavedTextDb =
            instance ?: synchronized(this) {
                instance ?: SavedTextDb(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE saved_texts (" +
                "id TEXT PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "archived INTEGER NOT NULL DEFAULT 0, " +
                "progress INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE saved_texts ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE saved_texts ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun insert(item: SavedText) {
        val cv = ContentValues().apply {
            put("id", item.id)
            put("title", item.title)
            put("content", item.content)
            put("createdAt", item.createdAt)
            put("archived", if (item.archived) 1 else 0)
            put("progress", item.progress)
        }
        writableDatabase.insertWithOnConflict("saved_texts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setProgress(id: String, percent: Int) {
        val cv = ContentValues().apply { put("progress", percent.coerceIn(0, 100)) }
        writableDatabase.update("saved_texts", cv, "id = ?", arrayOf(id))
    }

    fun getAll(archived: Boolean): List<SavedText> {
        val list = ArrayList<SavedText>()
        readableDatabase.query(
            "saved_texts", null, "archived = ?", arrayOf(if (archived) "1" else "0"),
            null, null, "createdAt ASC"
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val tIdx = c.getColumnIndexOrThrow("title")
            val cIdx = c.getColumnIndexOrThrow("content")
            val crIdx = c.getColumnIndexOrThrow("createdAt")
            val aIdx = c.getColumnIndexOrThrow("archived")
            val pIdx = c.getColumnIndexOrThrow("progress")
            while (c.moveToNext()) {
                list.add(
                    SavedText(
                        c.getString(tIdx), c.getString(cIdx), c.getLong(crIdx),
                        c.getString(idIdx), c.getInt(aIdx) != 0, c.getInt(pIdx)
                    )
                )
            }
        }
        return list
    }

    fun setArchived(ids: Collection<String>, archived: Boolean) {
        if (ids.isEmpty()) return
        val cv = ContentValues().apply { put("archived", if (archived) 1 else 0) }
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.update(
            "saved_texts", cv, "id IN ($placeholders)", ids.toTypedArray()
        )
    }

    fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.delete("saved_texts", "id IN ($placeholders)", ids.toTypedArray())
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM saved_texts", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
