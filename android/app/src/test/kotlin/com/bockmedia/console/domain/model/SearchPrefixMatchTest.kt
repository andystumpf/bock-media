package com.bockmedia.console.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPrefixMatchTest {
    @Test
    fun remMatchesDottedArtist() {
        assertTrue(SearchPrefixMatch.fieldMatchesQuery("rem", "R.E.M."))
        assertTrue(SearchPrefixMatch.fieldMatchesQuery("REM", "R.E.M."))
    }

    @Test
    fun remDoesNotMatchMidWord() {
        assertFalse(SearchPrefixMatch.fieldMatchesQuery("rem", "Abbas Premjee"))
        assertFalse(SearchPrefixMatch.fieldMatchesQuery("rem", "Premjee"))
    }

    @Test
    fun learnToMatchesMultiWordTitle() {
        assertTrue(SearchPrefixMatch.fieldMatchesQuery("learn to", "Learn to Fly"))
    }

    @Test
    fun typingToleranceExtendsValidPrefix() {
        assertTrue(SearchPrefixMatch.fieldMatchesQuery("rem", "R.E.M."))
        assertTrue(SearchPrefixMatch.fieldMatchesQuery("reme", "R.E.M."))
        assertFalse(SearchPrefixMatch.fieldMatchesQuery("prem", "R.E.M."))
    }
}
