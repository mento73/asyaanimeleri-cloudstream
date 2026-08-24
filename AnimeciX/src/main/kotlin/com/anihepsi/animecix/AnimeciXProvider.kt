package com.anihepsi.animecix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class AnimeciXProvider : MainAPI() {

    override var mainUrl = "https://animecix.tv"
    override var name = "AnimeciX"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/secure/last-episodes" to "Son Eklenen Bölümler",
        "$mainUrl/secure/titles?type=series&onlyStreamable=true" to "Seriler",
        "$mainUrl/secure/titles?type=movie&onlyStreamable=true" to "Filmler"
    )

    private val apiHeaders = mapOf(
        "x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return if (request.data.contains("/last-episodes")) {

            val response = app.get(
                "$mainUrl/secure/last-episodes?page=$page&perPage=10",
                headers = apiHeaders
            ).parsedSafe<LastEpisodesResponse>()
                ?.data
                ?: emptyList()

            val home = response.map { item ->

                val formattedTitle =
                    "S${item.seasonNumber}B${item.episodeNumber} - ${item.titleName}"

                newAnimeSearchResponse(
                    formattedTitle,
                    "$mainUrl/secure/titles/${item.titleId}?titleId=${item.titleId}",
                    TvType.Anime
                ) {
                    posterUrl = fixUrlNull(item.titlePoster)
                }
            }

            newHomePageResponse(
                request.name,
                home
            )

        } else {

            val response = app.get(
                "${request.data}&page=$page&perPage=16",
                headers = apiHeaders
            ).parsedSafe<Category>()

            val home = response
                ?.pagination
                ?.data
                ?.map { anime ->

                    newAnimeSearchResponse(
                        anime.title,
                        "$mainUrl/secure/titles/${anime.id}?titleId=${anime.id}",
                        TvType.Anime
                    ) {
                        posterUrl = fixUrlNull(anime.poster)
                    }
                }
                ?: emptyList()

            newHomePageResponse(
                request.name,
                home
            )
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response = app.get(
            "$mainUrl/secure/search/$query?limit=20",
            headers = apiHeaders
        ).parsedSafe<Search>()
            ?: return emptyList()

        return response.results.map { anime ->

            newAnimeSearchResponse(
                anime.title,
                "$mainUrl/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                posterUrl = fixUrlNull(anime.poster)
            }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val response = app.get(
            url,
            headers = apiHeaders
        ).parsedSafe<Title>()
            ?: return null

        val anime = response.title
        val episodes = mutableListOf<Episode>()
        val titleId = url.substringAfter("?titleId=")

        if (anime.titleType == "anime") {

            anime.seasons.forEach { season ->

                val seasonResponse = app.get(
                    "$mainUrl/secure/related-videos" +
                        "?episode=1" +
                        "&season=${season.number}" +
                        "&videoId=0" +
                        "&titleId=$titleId",
                    headers = apiHeaders
                ).parsedSafe<TitleVideos>()

               seasonResponse
    ?.videos
    ?.forEach { video ->

        episodes.add(
            newEpisode(video.url) {
                name =
                    "${video.seasonNum}. Sezon ${video.episodeNum}. Bölüm"

                this.season = video.seasonNum
                this.episode = video.episodeNum
            }
        )
    }
    
 }

        } else {

            anime.videos
                .firstOrNull()
                ?.let { video ->

                    episodes.add(
                        newEpisode(video.url) {
                            name = "Filmi İzle"
                            season = 1
                            episode = 1
                        }
                    )
                }
        }

        return newTvSeriesLoadResponse(
            anime.title,
            "$mainUrl/secure/titles/${anime.id}?titleId=${anime.id}",
            TvType.Anime,
            episodes
        ) {
            posterUrl = fixUrlNull(anime.poster)
            year = anime.year
            plot = anime.description
            tags = anime.tags.map { it.name }

            addActors(
                anime.actors.map {
                    Actor(
                        it.name,
                        fixUrlNull(it.poster)
                    )
                }
            )

            addTrailer(anime.trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl =
            if (data.startsWith("http")) {
                data
            } else {
                "$mainUrl/$data"
            }

        val response = app.get(
            pageUrl,
            referer = "$mainUrl/"
        )

        var iframeLink = response.url

        val doubleUrlRegex = Regex(
            """https://animecix\.tv/(https://animecix\.tv/secure/[^\s]+)"""
        )

        doubleUrlRegex
            .find(iframeLink)
            ?.groupValues
            ?.getOrNull(1)
            ?.let {
                iframeLink = it
            }

        if (iframeLink.contains("/secure/best-video")) {

            val redirectResponse = app.get(
                iframeLink,
                referer = "$mainUrl/"
            )

            val redirectedUrl =
                redirectResponse.url

            if (redirectedUrl.contains("tau-video")) {

                loadExtractor(
                    redirectedUrl,
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }

        } else {

            loadExtractor(
                iframeLink,
                "$mainUrl/",
                subtitleCallback,
                callback
            )
        }

        return true
    }
}
