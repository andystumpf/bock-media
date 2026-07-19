package com.bockmedia.console.domain.model

data class HomeDecadeSection(
    val id: String,
    val title: String,
)

/** Fixed home rows — one section per decade, all matching library playlists. */
object HomeDecadeSections {
    val sixties = HomeDecadeSection("60s", "60s")
    val seventies = HomeDecadeSection("70s", "70s")
    val eighties = HomeDecadeSection("80s", "80s")
    val nineties = HomeDecadeSection("90s", "90s")

    fun all(): List<HomeDecadeSection> = listOf(sixties, seventies, eighties, nineties)
}
