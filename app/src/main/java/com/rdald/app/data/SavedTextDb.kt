package com.rdald.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rdald.app.SavedText

/**
 * Simple SQLite persistence for saved texts (framework SQLiteOpenHelper; no extra deps).
 * Schema v2 adds an `archived` flag for the inbox/archive split.
 * Schema v4 adds `iconPath` for a cached article favicon.
 * Schema v5 adds `sourceUrl` (the article URL, to open in a browser).
 * Schema v6 adds `sortOrder` for user-defined list ordering (drag & drop).
 */
class SavedTextDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "readaloud.db", null, 6) {

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
                "progress INTEGER NOT NULL DEFAULT 0, " +
                "iconPath TEXT, " +
                "sourceUrl TEXT, " +
                "sortOrder INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE saved_texts ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE saved_texts ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE saved_texts ADD COLUMN iconPath TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE saved_texts ADD COLUMN sourceUrl TEXT")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE saved_texts ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun insert(item: SavedText) {
        val db = writableDatabase
        val nextOrder = db.rawQuery(
            "SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM saved_texts", null
        ).use { if (it.moveToFirst()) it.getLong(0) else 1L }
        val cv = ContentValues().apply {
            put("id", item.id)
            put("title", item.title)
            put("content", item.content)
            put("createdAt", item.createdAt)
            put("archived", if (item.archived) 1 else 0)
            put("progress", item.progress)
            put("iconPath", item.iconPath)
            put("sourceUrl", item.sourceUrl)
            put("sortOrder", nextOrder)
        }
        db.insertWithOnConflict("saved_texts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setProgress(id: String, percent: Int) {
        val cv = ContentValues().apply { put("progress", percent.coerceIn(0, 100)) }
        writableDatabase.update("saved_texts", cv, "id = ?", arrayOf(id))
    }

    /** Move an item to the bottom of the queue (fresh timestamp + highest sort order). */
    fun moveToBottom(id: String) {
        val db = writableDatabase
        val nextOrder = db.rawQuery(
            "SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM saved_texts", null
        ).use { if (it.moveToFirst()) it.getLong(0) else 1L }
        val cv = ContentValues().apply {
            put("createdAt", System.currentTimeMillis())
            put("sortOrder", nextOrder)
        }
        db.update("saved_texts", cv, "id = ?", arrayOf(id))
    }

    /** Persist a new manual order for the given ids (index = order). */
    fun setOrder(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id ->
                val cv = ContentValues().apply { put("sortOrder", i) }
                db.update("saved_texts", cv, "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAll(archived: Boolean): List<SavedText> {
        val list = ArrayList<SavedText>()
        readableDatabase.query(
            "saved_texts", null, "archived = ?", arrayOf(if (archived) "1" else "0"),
            null, null, "sortOrder ASC, createdAt ASC"
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val tIdx = c.getColumnIndexOrThrow("title")
            val cIdx = c.getColumnIndexOrThrow("content")
            val crIdx = c.getColumnIndexOrThrow("createdAt")
            val aIdx = c.getColumnIndexOrThrow("archived")
            val pIdx = c.getColumnIndexOrThrow("progress")
            val iconIdx = c.getColumnIndexOrThrow("iconPath")
            val urlIdx = c.getColumnIndexOrThrow("sourceUrl")
            while (c.moveToNext()) {
                list.add(
                    SavedText(
                        c.getString(tIdx), c.getString(cIdx), c.getLong(crIdx),
                        c.getString(idIdx), c.getInt(aIdx) != 0, c.getInt(pIdx),
                        c.getString(iconIdx), c.getString(urlIdx)
                    )
                )
            }
        }
        return list
    }

    fun getById(id: String): SavedText? {
        readableDatabase.query("saved_texts", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) {
                return SavedText(
                    c.getString(c.getColumnIndexOrThrow("title")),
                    c.getString(c.getColumnIndexOrThrow("content")),
                    c.getLong(c.getColumnIndexOrThrow("createdAt")),
                    c.getString(c.getColumnIndexOrThrow("id")),
                    c.getInt(c.getColumnIndexOrThrow("archived")) != 0,
                    c.getInt(c.getColumnIndexOrThrow("progress")),
                    c.getString(c.getColumnIndexOrThrow("iconPath")),
                    c.getString(c.getColumnIndexOrThrow("sourceUrl"))
                )
            }
        }
        return null
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
