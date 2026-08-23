package com.example

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.toNewSearchResponseList

class AsyaAnimeleriProvider : MainAPI() {

    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val hasMainPage = true

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get("$mainUrl/series/list-mode/").document

        val animeList = document
            .select("a[href*='/series/']")
            .mapNotNull { element ->

                val title = element.text().trim()
                val url = element.absUrl("href")

                if (
                    title.isBlank() ||
                    url.isBlank() ||
                    url.contains("list-mode")
                ) {
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
            listOf(
                HomePageList(
                    "Tüm Animeler",
                    animeList
                )
            )
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {

        val document = app.get("$mainUrl/series/list-mode/").document

        val results = document
            .select("a[href*='/series/']")
            .mapNotNull { element ->

                val title = element.text().trim()
                val url = element.absUrl("href")

                if (
                    title.isBlank() ||
                    url.isBlank() ||
                    url.contains("list-mode") ||
                    !title.contains(query, ignoreCase = true)
                ) {
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

        return results.toNewSearchResponseList()
    }
}
