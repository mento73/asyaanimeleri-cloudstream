package com.anihepsi.hdfilmcehennemi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/*
 * Original Hdfilmcehennemi implementation:
 * Hexated / cloudstream-extensions-multilingual
 *
 * Adapted for the Anihepsi CloudStream repository.
 */

class HdfilmcehennemiProvider : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"

    override val hasMainPage = true
    override var lang = "tr"

    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "$mainUrl/category/populer-diziler-2/" to "Popüler Diziler",
        "$mainUrl/imdb-7-puan-uzeri-filmler/" to "IMDb 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar-2/" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle/" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageUrl = if (page == 1) {
            request.data
        } else {
            request.data.trimEnd('/') + "/page/$page/"
        }

        val document = app.get(
            pageUrl
        ).document

        val type = if (
            request.name.contains(
                "Dizi",
                ignoreCase = true
            )
        ) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        val home = document
            .select(
                "div.poster-container, div.col-6.col-sm-3.poster-container"
            )
            .mapNotNull {
                it.toSearchResult(type)
            }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    private fun Element.toSearchResult(
        type: TvType
    ): SearchResponse? {

        val anchor = selectFirst("a")
            ?: return null

        val title = anchor
            .attr("title")
            .ifBlank {
                anchor.text()
            }
            .trim()
            .ifBlank {
                selectFirst(
                    "h2, h3, .title"
                )
                    ?.text()
                    ?.trim()
                    .orEmpty()
            }

        if (title.isBlank()) {
            return null
        }

        val href = fixUrlNull(
            anchor.attr("href")
        ) ?: return null

        val image = selectFirst(
            "img"
        )

        val posterUrl = fixUrlNull(
            image
                ?.attr("data-src")
                ?.ifBlank {
                    image.attr(
                        "data-lazy-src"
                    )
                }
                ?.ifBlank {
                    image.attr(
                        "src"
                    )
                }
        )

        return when (type) {

            TvType.TvSeries ->
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    this.posterUrl =
                        posterUrl
                }

            else ->
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    this.posterUrl =
                        posterUrl
                }
        }
    }

    private fun Media.toSearchResponse():
        SearchResponse? {

        val title = title
            ?: return null

        val slug = slug
            ?: return null

        val slugPrefix =
            slugPrefix.orEmpty()

        val url =
            "$mainUrl/$slugPrefix$slug"

        val posterUrl =
            poster?.let {
                "$mainUrl/uploads/poster/$it"
            }

        return newTvSeriesSearchResponse(
            title,
            url,
            TvType.TvSeries
        ) {
            this.posterUrl =
                posterUrl
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        return app.post(
            "$mainUrl/search/",
            data = mapOf(
                "query" to query
            ),
            referer = "$mainUrl/",
            headers = mapOf(
                "Accept" to
                    "application/json, text/javascript, */*; q=0.01",

                "X-Requested-With" to
                    "XMLHttpRequest"
            )
        )
            .parsedSafe<Result>()
            ?.result
            ?.mapNotNull {
                media ->
                media.toSearchResponse()
            }
            ?: emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(
                url
            ).document

        val title = document
            .selectFirst(
                "div.card-header > h1, div.card-header > h2, h1"
            )
            ?.text()
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            document
                .selectFirst(
                    "img.img-fluid, .poster img"
                )
                ?.let {
                    image ->
                    image.attr(
                        "data-src"
                    )
                        .ifBlank {
                            image.attr(
                                "src"
                            )
                        }
                }
        )

        val tags = document
            .select(
                "div.mb-0.lh-lg div:nth-child(5) a"
            )
            .map {
                it.text()
            }

        val year = document
            .selectFirst(
                "div.mb-0.lh-lg div:nth-child(4) a"
            )
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val tvType =
            if (
                document
                    .select(
                        "nav#seasonsTabs, #seasonsTabs"
                    )
                    .isEmpty()
            ) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }

        val description =
            document
                .selectFirst(
                    "article.text-white > p, article p, .description"
                )
                ?.text()
                ?.trim()

        val actors =
            document
                .select(
                    "div.mb-0.lh-lg div:last-child a.chip"
                )
                .map {
                    Actor(
                        it.text(),
                        it.selectFirst(
                            "img"
                        )
                            ?.attr(
                                "src"
                            )
                    )
                }

        val recommendations =
            document
                .select(
                    "div.swiper-wrapper div.poster.poster-pop"
                )
                .mapNotNull {

                    val recName =
                        it.selectFirst(
                            "h2.title, h3.title"
                        )
                            ?.text()
                            ?.trim()
                            ?: return@mapNotNull null

                    val recHref =
                        fixUrlNull(
                            it.selectFirst(
                                "a"
                            )
                                ?.attr(
                                    "href"
                                )
                        )
                            ?: return@mapNotNull null

                    val recPosterUrl =
                        fixUrlNull(
                            it.selectFirst(
                                "img"
                            )
                                ?.let {
                                    image ->
                                    image.attr(
                                        "data-src"
                                    )
                                        .ifBlank {
                                            image.attr(
                                                "src"
                                            )
                                        }
                                }
                        )

                    newMovieSearchResponse(
                        recName,
                        recHref,
                        TvType.Movie
                    ) {
                        posterUrl =
                            recPosterUrl
                    }
                }

        return if (
            tvType == TvType.TvSeries
        ) {

            val trailer =
                document
                    .selectFirst(
                        "button.btn.btn-fragman.btn-danger"
                    )
                    ?.attr(
                        "data-trailer"
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        "https://www.youtube.com/embed/$it"
                    }

            val episodes =
                document
                    .select(
                        "div#seasonsTabs-tabContent div.card-list-item"
                    )
                    .mapNotNull {

                        val href =
                            fixUrlNull(
                                it.selectFirst(
                                    "a"
                                )
                                    ?.attr(
                                        "href"
                                    )
                            )
                                ?: return@mapNotNull null

                        val name =
                            it.selectFirst(
                                "h3"
                            )
                                ?.text()
                                ?.trim()
                                .orEmpty()

                        val episodeNumber =
                            Regex(
                                """(?:Bölüm|Bolum)\s*([0-9]+)""",
                                RegexOption.IGNORE_CASE
                            )
                                .find(
                                    name
                                )
                                ?.groupValues
                                ?.getOrNull(
                                    1
                                )
                                ?.toIntOrNull()

                        val season =
                            it.parents()
                                .firstOrNull {
                                    parent ->
                                    parent.id()
                                        .contains(
                                            "season",
                                            ignoreCase = true
                                        ) ||
                                        parent.id()
                                            .contains(
                                                "sezon",
                                                ignoreCase = true
                                            )
                                }
                                ?.id()
                                ?.let {
                                    id ->
                                    Regex(
                                        """([0-9]+)"""
                                    )
                                        .find(
                                            id
                                        )
                                        ?.groupValues
                                        ?.getOrNull(
                                            1
                                        )
                                        ?.toIntOrNull()
                                }

                        newEpisode(
                            href
                        ) {
                            this.name =
                                name

                            this.season =
                                season

                            this.episode =
                                episodeNumber
                        }
                    }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl =
                    poster

                this.year =
                    year

                plot =
                    description

                this.tags =
                    tags

                addActors(
                    actors
                )

                this.recommendations =
                    recommendations

                addTrailer(
                    trailer
                )
            }

        } else {

            val trailer =
                document
                    .selectFirst(
                        "nav.nav.card-nav.nav-slider a[data-bs-toggle=\"modal\"]"
                    )
                    ?.attr(
                        "data-trailer"
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        "https://www.youtube.com/embed/$it"
                    }

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {

                posterUrl =
                    poster

                this.year =
                    year

                plot =
                    description

                this.tags =
                    tags

                addActors(
                    actors
                )

                this.recommendations =
                    recommendations

                addTrailer(
                    trailer
                )
            }
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        sourceCallback:
            (ExtractorLink) -> Unit
    ) {

        if (
            url.isBlank()
        ) {
            return
        }

        val response =
            app.get(
                url,
                referer = "$mainUrl/"
            )

        val scripts =
            response
                .document
                .select(
                    "script"
                )

        val script =
            scripts.find {

                val data =
                    it.data()

                data.contains(
                    "var sources = [];"
                ) ||
                    data.contains(
                        "playerInstance ="
                    ) ||
                    data.contains(
                        "file:"
                    ) ||
                    data.contains(
                        "\"file\""
                    )
            }
                ?.data()
                ?: return

        val m3uLink =
            Regex(
                """file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    script
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )
                ?: Regex(
                    """"file"\s*:\s*"([^"]+\.m3u8[^"]*)"""",
                    RegexOption.IGNORE_CASE
                )
                    .find(
                        script
                    )
                    ?.groupValues
                    ?.getOrNull(
                        1
                    )
                ?: script
                    .substringAfter(
                        "[{file:\"",
                        ""
                    )
                    .substringBefore(
                        "\"}]",
                        ""
                    )
                    .takeIf {
                        it.isNotBlank()
                    }
                ?: return

        M3u8Helper
            .generateM3u8(
                source,
                m3uLink,
                if (
                    url.startsWith(
                        mainUrl
                    )
                ) {
                    "$mainUrl/"
                } else {
                    url
                }
            )
            .forEach(
                sourceCallback
            )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {

        val document =
            app.get(
                data
            ).document

        val sourceButtons =
            document
                .select(
                    "nav.nav.card-nav.nav-slider a.nav-link"
                )

        for (
            button in sourceButtons
        ) {

            val url =
                fixUrlNull(
                    button.attr(
                        "href"
                    )
                )
                    ?: continue

            val source =
                button.text()
                    .ifBlank {
                        name
                    }

            safeApiCall {

                val sourcePage =
                    app.get(
                        url,
                        referer = data
                    ).document

                val iframe =
                    sourcePage
                        .selectFirst(
                            "div.card-video iframe, iframe"
                        )

                val iframeLink =
                    iframe
                        ?.attr(
                            "data-src"
                        )
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: iframe
                            ?.attr(
                                "src"
                            )
                            ?.takeIf {
                                it.isNotBlank()
                            }
                        ?: return@safeApiCall

                val cleanLink =
                    fixUrlNull(
                        iframeLink
                    )
                        ?: return@safeApiCall

                invokeLocalSource(
                    source,
                    cleanLink,
                    callback
                )
            }
        }

        return true
    }

    data class Result(
        @JsonProperty("result")
        val result:
            ArrayList<Media>? =
            arrayListOf()
    )

    data class Media(
        @JsonProperty("title")
        val title:
            String? = null,

        @JsonProperty("poster")
        val poster:
            String? = null,

        @JsonProperty("slug")
        val slug:
            String? = null,

        @JsonProperty("slug_prefix")
        val slugPrefix:
            String? = null
    )
}
