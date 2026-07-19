package com.bockmedia.console.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RatingKindTest {
    @Test
    fun artistKindUsesApiValue() {
        assertEquals("artist", RatingKind.Artist.apiValue)
    }
}
