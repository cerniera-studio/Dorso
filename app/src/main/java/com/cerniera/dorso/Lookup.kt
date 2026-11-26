package com.cerniera.dorso

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

/**
 * A word's meaning, for a word selected on the page (26 Nov 2026, Mechack: select a word to find
 * what it means). From the Free Dictionary API (dictionaryapi.dev: English, Wiktionary's
 * definitions, no key and no account); only the word itself is sent. Call off the main thread.
 */
object Lookup {
    class Sense(val partOfSpeech: String, val definitions: List<Pair<String, String?>>)
    class Entry(val word: String, val phonetic: String?, val senses: List<Sense>)

    /** The word as a dictionary lists it: no possessive, no edge punctuation, lower case unless an acronym. */
    fun headword(selected: String): String {
        var w = selected.trim().trim { !it.isLetterOrDigit() }
        w = w.removeSuffix("’s").removeSuffix("'s")
        return if (w.length > 1 && w.all { it.isUpperCase() || !it.isLetter() }) w else w.lowercase()
    }

    /** Whether [selected] is one word, worth looking up in a dictionary. */
    fun isWord(selected: String): Boolean {
        val w = headword(selected)
        return w.isNotEmpty() && w.length <= 40 && w.none { it.isWhitespace() } && w.any { it.isLetter() }
    }

    /** The entry, or null if the dictionary has none; throws if it can't be reached. */
    fun define(selected: String): Entry? {
        val w = headword(selected)
        val c = URL("https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(w, "UTF-8").replace("+", "%20"))
            .openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw java.io.IOException("the dictionary answered ${c.responseCode}")
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            if (arr.length() == 0) return null
            var phonetic: String? = null
            // Senses of the same part of speech from several entries read as one list
            val byPart = LinkedHashMap<String, MutableList<Pair<String, String?>>>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                if (phonetic == null) phonetic = e.optString("phonetic").takeIf { it.isNotBlank() }
                    ?: e.optJSONArray("phonetics")?.let { p -> (0 until p.length()).map { p.getJSONObject(it).optString("text") }.firstOrNull { it.isNotBlank() } }
                val meanings = e.optJSONArray("meanings") ?: continue
                for (m in 0 until meanings.length()) {
                    val mo = meanings.getJSONObject(m)
                    val list = byPart.getOrPut(mo.optString("partOfSpeech").ifBlank { "—" }) { mutableListOf() }
                    val defs = mo.optJSONArray("definitions") ?: continue
                    for (d in 0 until defs.length()) {
                        val dob = defs.getJSONObject(d)
                        val text = dob.optString("definition").trim()
                        if (text.isNotEmpty() && list.size < 4) list += text to dob.optString("example").trim().takeIf { it.isNotEmpty() }
                    }
                }
            }
            val senses = byPart.filterValues { it.isNotEmpty() }.map { (k, v) -> Sense(k, v) }
            return if (senses.isEmpty()) null else Entry(arr.getJSONObject(0).optString("word", w), phonetic, senses)
        } finally { c.disconnect() }
    }
}
