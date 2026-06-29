package com.bockmedia.console.domain.model

/** Prefix + acronym matching (e.g. rem → R.E.M.), never mid-word (Premjee ✗). */
object SearchPrefixMatch {
    private val nonAlnum = Regex("[^a-z0-9]")
    private val splitParts = Regex("[.\\s]+")
    private val wordSplit = Regex("[\\W_]+")

    fun compact(s: String): String = nonAlnum.replace(s.lowercase(), "")

    fun acronymCompact(text: String): String {
        val parts = splitParts.split(text).filter { it.isNotBlank() }
        if (parts.size > 1) {
            return parts.map { it.first().lowercaseChar() }.joinToString("")
        }
        val words = wordSplit.split(text).filter { it.isNotBlank() }
        if (words.size > 1) {
            return words.map { it.first().lowercaseChar() }.joinToString("")
        }
        return compact(text)
    }

    fun fieldMatchesQuery(q: String, text: String?): Boolean {
        val qc = compact(q)
        if (qc.isEmpty() || text.isNullOrBlank()) return false
        val tc = compact(text)
        if (tc.startsWith(qc)) return true
        val ac = acronymCompact(text)
        if (ac.startsWith(qc)) return true
        for (word in wordSplit.split(text).filter { it.isNotBlank() }) {
            if (compact(word).startsWith(qc)) return true
            if (acronymCompact(word).startsWith(qc)) return true
        }
        return false
    }
}
