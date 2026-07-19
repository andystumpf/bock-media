package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.SearchHit
import com.bockmedia.console.data.api.dto.SearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryCacheTest {
    @Test
    fun exactMatchRoundTrip() {
        SearchQueryCache.invalidate()
        val response = SearchResponse(songs = listOf(SearchHit(title = "Learn to Fly")))
        SearchQueryCache.put("learn to", response)
        assertEquals(response, SearchQueryCache.get("learn to"))
        assertNull(SearchQueryCache.get("learn"))
    }

    @Test
    fun prefixExtensionReusesShorterQuery() {
        SearchQueryCache.invalidate()
        val response = SearchResponse(songs = listOf(SearchHit(title = "Learn to Fly")))
        SearchQueryCache.put("learn", response)
        assertNotNull(SearchQueryCache.getPrefixExtension("learn t"))
        assertNull(SearchQueryCache.getPrefixExtension("lea"))
    }

    @Test
    fun learnToMatchesTitle() {
        assertTrue(SearchPrefixMatch.fieldMatchesQuery("learn to", "Learn to Fly"))
    }
}

private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
