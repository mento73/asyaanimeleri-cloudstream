package com.example

import com.lagradost.cloudstream3.*

class AsyaAnimeleriProvider : MainAPI() {

    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/series/list-mode/" to "Tüm Animeler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(request.data).document

        val items = document
            .select("a[href*='/series/']")
            .mapNotNull { element ->

                val title = element.text().trim()
                val url = element.absUrl("href")

                if (title.isBlank() || url.isBlank()) {
                    null
                } else {
                    newAnimeSearchResponse(
                        title,
                        url,
                        TvType.Anime
                    )
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }
}
