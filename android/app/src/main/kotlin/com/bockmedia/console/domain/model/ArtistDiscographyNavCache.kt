package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AlbumItem

/** Passes discography payload into the discography route without serializing large lists. */
object ArtistDiscographyNavCache {
    var albums: List<AlbumItem> = emptyList()
    var appearsOnNames: Set<String> = emptySet()
}
