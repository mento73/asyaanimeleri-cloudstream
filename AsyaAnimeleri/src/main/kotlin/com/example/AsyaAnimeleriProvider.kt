package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")

        val description = document
            .selectFirst("meta[property='og:description']")
            ?.attr("content")

        val episodes = document
            .select("a")
            .mapNotNull { element ->

                val episodeUrl = element.absUrl("href")
                val text = element.text().trim()

                if (
                    episodeUrl.isBlank() ||
                    text.isBlank() ||
                    !text.contains("Bölüm", ignoreCase = true) ||
                    episodeUrl.contains("/series/")
                ) {
                    null
                } else {

                    val number = Regex("""(\d+)""")
                        .find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                    newEpisode(episodeUrl) {
                        name = text
                        episode = number
                    }
                }
            }
            .distinctBy { it.data }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = poster
            plot = description

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val document = app.get(data).document

    val iframeUrls = document
        .select("iframe[src]")
        .map { it.absUrl("src") }
        .filter { it.isNotBlank() }
        .distinct()

    var found = false

    iframeUrls.forEach { iframeUrl ->
        val success = loadExtractor(
            url = iframeUrl,
            referer = data,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        if (success) {
            found = true
        }
    }

    return found
}
}
