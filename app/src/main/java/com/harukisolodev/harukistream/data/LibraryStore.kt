package com.harukisolodev.harukistream.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LibraryStore(context: Context) : SQLiteOpenHelper(context, "haruki_library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE library (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id TEXT NOT NULL,
                title TEXT NOT NULL,
                uri TEXT NOT NULL,
                source_url TEXT NOT NULL,
                thumbnail_url TEXT NOT NULL,
                quality TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                downloaded_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_library_media_id ON library(media_id)")
        db.execSQL("CREATE INDEX idx_library_downloaded_at ON library(downloaded_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insert(
        mediaId: String,
        title: String,
        uri: String,
        sourceUrl: String,
        thumbnailUrl: String,
        quality: String,
        mimeType: String
    ): Long {
        val values = ContentValues().apply {
            put("media_id", mediaId)
            put("title", title)
            put("uri", uri)
            put("source_url", sourceUrl)
            put("thumbnail_url", thumbnailUrl)
            put("quality", quality)
            put("mime_type", mimeType)
            put("downloaded_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("library", null, values)
    }

    @Synchronized
    fun all(): List<LibraryItem> {
        val out = mutableListOf<LibraryItem>()
        readableDatabase.query(
            "library", null, null, null, null, null, "downloaded_at DESC"
        ).use { c ->
            val id = c.getColumnIndexOrThrow("id")
            val mediaId = c.getColumnIndexOrThrow("media_id")
            val title = c.getColumnIndexOrThrow("title")
            val uri = c.getColumnIndexOrThrow("uri")
            val source = c.getColumnIndexOrThrow("source_url")
            val thumb = c.getColumnIndexOrThrow("thumbnail_url")
            val quality = c.getColumnIndexOrThrow("quality")
            val mime = c.getColumnIndexOrThrow("mime_type")
            val date = c.getColumnIndexOrThrow("downloaded_at")
            while (c.moveToNext()) {
                out += LibraryItem(
                    c.getLong(id), c.getString(mediaId), c.getString(title), c.getString(uri),
                    c.getString(source), c.getString(thumb), c.getString(quality), c.getString(mime),
                    c.getLong(date)
                )
            }
        }
        return out
    }

    @Synchronized
    fun byId(id: Long): LibraryItem? = all().firstOrNull { it.id == id }

    @Synchronized
    fun hasMedia(mediaId: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM library WHERE media_id = ? LIMIT 1", arrayOf(mediaId)
        ).use { return it.moveToFirst() }
    }

    @Synchronized
    fun remove(id: Long) {
        writableDatabase.delete("library", "id = ?", arrayOf(id.toString()))
    }
}
