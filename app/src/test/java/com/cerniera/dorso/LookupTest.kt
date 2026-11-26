package com.cerniera.dorso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupTest {
    @Test fun headwords() {
        assertEquals("basked", Lookup.headword("basked"))
        assertEquals("maduro", Lookup.headword("Maduro’s"))
        assertEquals("pipeline", Lookup.headword("pipeline."))
        assertEquals("NATO", Lookup.headword("NATO"))
        assertEquals("well-known", Lookup.headword("“Well-known,”"))
    }

    @Test fun oneWordOnly() {
        assertTrue(Lookup.isWord("untapped"))
        assertFalse(Lookup.isWord("gold pipeline"))
        assertFalse(Lookup.isWord("—"))
    }
}
