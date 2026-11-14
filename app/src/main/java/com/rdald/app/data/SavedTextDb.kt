package com.rdald.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rdald.app.SavedText

/**
 * Simple SQLite persistence for saved texts (built-in SQLiteOpenHelper; no extra deps).
 * Replaces the previous in-memory sample list so texts survive app restarts.
 */
class SavedTextDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "readaloud.db", null, 1) {

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
                "createdAt INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS saved_texts")
        onCreate(db)
    }

    fun insert(item: SavedText) {
        val cv = ContentValues().apply {
            put("id", item.id)
            put("title", item.title)
            put("content", item.content)
            put("createdAt", item.createdAt)
        }
        writableDatabase.insertWithOnConflict("saved_texts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAll(): List<SavedText> {
        val list = ArrayList<SavedText>()
        readableDatabase.query(
            "saved_texts", null, null, null, null, null, "createdAt ASC"
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val tIdx = c.getColumnIndexOrThrow("title")
            val cIdx = c.getColumnIndexOrThrow("content")
            val crIdx = c.getColumnIndexOrThrow("createdAt")
            while (c.moveToNext()) {
                list.add(SavedText(c.getString(tIdx), c.getString(cIdx), c.getLong(crIdx), c.getString(idIdx)))
            }
        }
        return list
    }

    fun delete(id: String) {
        writableDatabase.delete("saved_texts", "id = ?", arrayOf(id))
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM saved_texts", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
