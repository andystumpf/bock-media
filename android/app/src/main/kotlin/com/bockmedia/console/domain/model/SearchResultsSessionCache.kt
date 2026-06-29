package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.SearchResponse

/** Preserves active search query + results when drilling into artist/album/etc. and pressing back. */
object SearchResultsSessionCache {
    var query: String = ""
        private set
    var results: SearchResponse? = null
        private set
    var suggestions: List<SearchSuggestion> = emptyList()
        private set
    var searchSource: String? = null
        private set
    var searchAllLibraries: Boolean = true
        private set
    val expandedSections: MutableSet<String> = linkedSetOf()
    val expandedData: MutableMap<String, SearchResponse> = linkedMapOf()

    fun saveSnapshot(
        query: String,
        results: SearchResponse?,
        suggestions: List<SearchSuggestion>,
        searchSource: String?,
        searchAllLibraries: Boolean,
    ) {
        this.query = query.trim()
        this.results = results
        this.suggestions = suggestions
        this.searchSource = searchSource
        this.searchAllLibraries = searchAllLibraries
    }

    fun hasFreshResults(forQuery: String, source: String?, allLibraries: Boolean): Boolean {
        val q = forQuery.trim()
        if (q.length < 2 || results == null) return false
        return this.query == q &&
            this.searchSource == source &&
            this.searchAllLibraries == allLibraries
    }

    fun clear() {
        query = ""
        results = null
        suggestions = emptyList()
        searchSource = null
        searchAllLibraries = true
        expandedSections.clear()
        expandedData.clear()
    }
}
