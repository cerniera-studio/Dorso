package com.cerniera.dorso

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One block of an article: a paragraph, a heading or a quotation. */
data class Block(val kind: Int, val text: String) {
    companion object { const val PARA = 0; const val HEAD = 1; const val QUOTE = 2 }
}

/**
 * A saved read. [offset] is where you are: the character that starts the line you were reading,
 * counted in the text the reader sets (title and byline included), so it survives any type size,
 * fold or turn.
 */
data class Article(
    val id: String, val url: String, val title: String, val byline: String, val site: String,
    val blocks: List<Block>, val savedAt: Long, var offset: Int = 0, var progress: Float = 0f, var openedAt: Long = 0L,
    /** Dog-eared pages: where each starts, and the size of its fold. */
    val marks: MutableList<Mark> = mutableListOf(),
    /** The jacket design chosen on the cover screen; -1 until you choose (then the read's own). */
    var cover: Int = -1,
    /** Highlighted and underlined passages, by character range in the reader's text. */
    val notes: MutableList<Note> = mutableListOf(),
) {
    val words get() = blocks.sumOf { b -> b.text.split(' ').count { it.isNotBlank() } }
    val minutes get() = (words / 230).coerceAtLeast(1)
}

/**
 * A folded corner: the page starting at [offset] (a character, so it survives a change of type),
 * folded [along] dp along the top edge and [down] dp down the outer edge.
 */
data class Mark(val offset: Int, val along: Float, val down: Float)

/**
 * A passage marked as you would in a printed copy: highlighted, or underlined in pencil. [start]
 * and [end] are characters in the text the reader sets (title and byline included), as [Mark]'s
 * offset is, so they survive any type size, fold or turn.
 */
data class Note(val start: Int, val end: Int, val kind: Int) {
    companion object { const val HIGHLIGHT = 0; const val UNDERLINE = 1 }
}

/** Reads kept as one JSON file each in the app's own files. Nothing leaves the phone. */
object Store {
    private fun dir(c: Context) = File(c.filesDir, "reads").apply { mkdirs() }

    fun all(c: Context): List<Article> = dir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { read(it.readText()) }.getOrNull() }
        .sortedByDescending { maxOf(it.savedAt, it.openedAt) }

    fun get(c: Context, id: String): Article? = File(dir(c), "$id.json").takeIf { it.exists() }?.let { runCatching { read(it.readText()) }.getOrNull() }

    fun put(c: Context, a: Article) { File(dir(c), "${a.id}.json").writeText(write(a).toString()) }

    fun remove(c: Context, id: String) { File(dir(c), "$id.json").delete() }

    fun byUrl(c: Context, url: String): Article? = all(c).firstOrNull { it.url == url }

    private fun write(a: Article) = JSONObject().apply {
        put("id", a.id); put("url", a.url); put("title", a.title); put("byline", a.byline); put("site", a.site)
        put("savedAt", a.savedAt); put("offset", a.offset); put("progress", a.progress.toDouble()); put("openedAt", a.openedAt)
        put("cover", a.cover)
        put("notes", JSONArray().apply { a.notes.forEach { n -> put(JSONArray().put(n.start).put(n.end).put(n.kind)) } })
        put("marks", JSONArray().apply { a.marks.forEach { m -> put(JSONArray().put(m.offset).put(m.along.toDouble()).put(m.down.toDouble())) } })
        put("blocks", JSONArray().apply { a.blocks.forEach { b -> put(JSONObject().put("k", b.kind).put("t", b.text)) } })
    }

    private fun read(s: String): Article {
        val o = JSONObject(s)
        val arr = o.getJSONArray("blocks")
        val blocks = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Block(it.getInt("k"), it.getString("t")) } }
        return Article(o.getString("id"), o.getString("url"), o.getString("title"), o.optString("byline"), o.optString("site"),
            blocks, o.getLong("savedAt"), o.optInt("offset"), o.optDouble("progress", 0.0).toFloat(), o.optLong("openedAt"),
            o.optJSONArray("marks")?.let { m -> (0 until m.length()).map { i -> m.getJSONArray(i).let { Mark(it.getInt(0), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) } }.toMutableList() }
                ?: mutableListOf(), o.optInt("cover", -1),
            o.optJSONArray("notes")?.let { m -> (0 until m.length()).map { i -> m.getJSONArray(i).let { Note(it.getInt(0), it.getInt(1), it.getInt(2)) } }.toMutableList() }
                ?: mutableListOf())
    }
}
