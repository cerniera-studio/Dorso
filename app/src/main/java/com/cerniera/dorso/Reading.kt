package com.cerniera.dorso

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlin.math.roundToInt

/**
 * The read you're partway through, shared with Momento in Fold Widgets (22 Nov 2026), so its note
 * can say "Carry on · <title> · 12 min left" and a tap opens Dorso at your page.
 *
 * One row, or none: the book on the nightstand ([Nightstand.current]'s rule), but only once it has
 * been started (1%) and isn't finished (99%), and only if opened in the last seven days. Read only
 * by apps holding com.cerniera.dorso.permission.READING, which only the family's apps ask for.
 * Dorso tells Momento to look again when you leave a read ([changed]).
 */
class ReadingProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val out = MatrixCursor(COLUMNS)
        val c = context ?: return out
        current(c, System.currentTimeMillis())?.let { a ->
            out.addRow(arrayOf<Any>(a.id, a.title, a.site, a.progress, (a.minutes * (1f - a.progress)).roundToInt().coerceAtLeast(1), a.openedAt))
        }
        return out
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.cerniera.reading"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        val URI: Uri = Uri.parse("content://com.cerniera.dorso.reading/current")
        val COLUMNS = arrayOf("id", "title", "site", "progress", "minutes_left", "opened_at")
        private const val WEEK = 7 * 24 * 3_600_000L

        fun current(c: Context, now: Long): Article? =
            Store.all(c).filter { it.openedAt > 0 && it.progress >= .01f && it.progress < .99f && now - it.openedAt < WEEK }
                .maxByOrNull { it.openedAt }

        /** Tells Momento (Fold Widgets) to look again; nothing happens if it isn't installed. */
        fun changed(c: Context) {
            runCatching { c.sendBroadcast(Intent("com.cerniera.MOMENTO_REFRESH").setPackage("com.fergusonconsultancy.foldwidgets")) }
        }
    }
}
