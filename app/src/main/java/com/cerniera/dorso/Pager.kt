package com.cerniera.dorso

/**
 * Cuts laid-out lines into pages, anchored on one line.
 *
 * The anchor is the line holding the sentence you were reading. It always starts a page, so after
 * a fold, a turn or a new type size the same sentence sits at the top of the page again (top left
 * of the spread). Pages after it run forward from it; pages before it are cut backwards from it,
 * so only the very first page of the article can come out short.
 *
 * Pure arithmetic over line tops and bottoms, so it is tested on the JVM.
 */
object Pager {

    /**
     * Page start lines, ascending. [tops] and [bottoms] are each line's top and bottom (px);
     * [pageH] the height a page holds; [anchor] the line that must start a page.
     */
    fun pages(tops: FloatArray, bottoms: FloatArray, pageH: Float, anchor: Int): IntArray {
        val n = tops.size
        if (n == 0) return intArrayOf(0)
        val a = anchor.coerceIn(0, n - 1)
        val forward = ArrayList<Int>()
        var start = a
        while (start < n) {
            forward += start
            var end = start
            while (end < n && bottoms[end] - tops[start] <= pageH) end++
            start = if (end == start) start + 1 else end   // a line taller than the page gets a page of its own
        }
        val backward = ArrayList<Int>()
        var end = a
        while (end > 0) {
            var s = end
            while (s > 0 && bottoms[end - 1] - tops[s - 1] <= pageH) s--
            if (s == end) s = end - 1
            backward += s
            end = s
        }
        backward.reverse()
        return (backward + forward).toIntArray()
    }

    /** The page index holding [line]. */
    fun pageOf(starts: IntArray, line: Int): Int {
        var i = 0
        while (i + 1 < starts.size && starts[i + 1] <= line) i++
        return i
    }

    /**
     * Spreads: two pages side by side (or one above the other). The anchored page must be the
     * left (or upper) one, so when it falls at an odd index the first spread opens on a blank left
     * page, as a book's first page is a right-hand one. Returns, for each spread, its left and
     * right page indices (-1 for blank).
     */
    fun spreads(pageCount: Int, anchorPage: Int, perSpread: Int): List<IntArray> {
        if (perSpread <= 1) return (0 until pageCount).map { intArrayOf(it, -1) }
        val lead = anchorPage % 2
        val out = ArrayList<IntArray>()
        var i = -lead
        while (i < pageCount) {
            out += intArrayOf(if (i >= 0) i else -1, if (i + 1 < pageCount) i + 1 else -1)
            i += 2
        }
        return out
    }

    /** The spread holding [page]. */
    fun spreadOf(spreads: List<IntArray>, page: Int): Int =
        spreads.indexOfFirst { it[0] == page || it[1] == page }.coerceAtLeast(0)
}
