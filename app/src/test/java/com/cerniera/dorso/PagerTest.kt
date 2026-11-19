package com.cerniera.dorso

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerTest {
    /** [n] lines of height [h], stacked from 0. */
    private fun lines(n: Int, h: Float = 10f) = FloatArray(n) { it * h } to FloatArray(n) { (it + 1) * h }

    @Test fun fromTheStart() {
        val (t, b) = lines(25)
        assertArrayEquals(intArrayOf(0, 10, 20), Pager.pages(t, b, 100f, 0))
    }

    @Test fun anchorAlwaysStartsAPage() {
        val (t, b) = lines(25)
        // Anchored on line 13: pages go 13, 23 forward and 3, then a short first page at 0, backward
        val p = Pager.pages(t, b, 100f, 13)
        assertArrayEquals(intArrayOf(0, 3, 13, 23), p)
        assertEquals(2, Pager.pageOf(p, 13))
        assertEquals(2, Pager.pageOf(p, 22))
        assertEquals(0, Pager.pageOf(p, 2))
    }

    @Test fun everyLineIsOnExactlyOnePageAndFits() {
        val tops = FloatArray(200); val bottoms = FloatArray(200)
        var y = 0f
        for (i in 0 until 200) { val h = if (i % 17 == 0) 31f else 23f; tops[i] = y; bottoms[i] = y + h; y += h }
        for (anchor in listOf(0, 1, 57, 199)) {
            val p = Pager.pages(tops, bottoms, 400f, anchor)
            assertEquals(0, p.first())
            assertTrue(p.contains(anchor))
            for (k in p.indices) {
                val end = if (k + 1 < p.size) p[k + 1] else 200
                assertTrue("page $k is empty", end > p[k])
                assertTrue("page $k overflows", bottoms[end - 1] - tops[p[k]] <= 400f)
            }
        }
    }

    @Test fun tallLineGetsItsOwnPage() {
        val t = floatArrayOf(0f, 10f, 200f); val b = floatArrayOf(10f, 200f, 210f)
        assertArrayEquals(intArrayOf(0, 1, 2), Pager.pages(t, b, 100f, 0))
    }

    @Test fun anchoredPageIsOnTheLeft() {
        // 7 pages, anchored on page 3 (odd): a blank left page opens the book
        val s = Pager.spreads(7, 3, 2)
        assertArrayEquals(intArrayOf(-1, 0), s[0])
        assertArrayEquals(intArrayOf(3, 4), s[Pager.spreadOf(s, 3)])
        assertArrayEquals(intArrayOf(5, 6), s.last())
        // Anchored on an even page: no blank
        val e = Pager.spreads(6, 2, 2)
        assertArrayEquals(intArrayOf(0, 1), e[0])
        assertArrayEquals(intArrayOf(2, 3), e[Pager.spreadOf(e, 2)])
        // One page at a time
        assertEquals(5, Pager.spreads(5, 3, 1).size)
    }
}
