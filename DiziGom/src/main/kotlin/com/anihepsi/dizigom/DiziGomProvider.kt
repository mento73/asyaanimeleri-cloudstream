package com.anihepsi.dizigom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

// Original DiziGom provider lineage: Kekik / Kraptor ecosystem.
// Modernized for the Anihepsi CloudStream repository.

class DiziGomProvider : MainAPI() {

    override var mainUrl = "https://dizigom1.com"
    override var name = "DiziGom"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "DiziGom"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data,
            referer = "$mainUrl/"
        ).document

        val items = document
            .select("a[href]")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                /*
                 * Only keep likely series/movie detail pages.
                 */
                if (
                    !href.contains("/dizi", ignoreCase = true) &&
                    !href.contains("/film", ignoreCase = true)
                ) {
                    return@mapNotNull null
                }

                val image = element
                    .selectFirst("img")

                val title = image
                    ?.attr("alt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: element
                        .attr("title")
                        .trim()
                        .takeIf { it.isNotBlank() }
                    ?: element
                        .text()
                        .trim()
                        .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val poster = image
                    ?.let { img ->
                        img.attr("src")
                            .takeIf { it.isNotBlank() }
                            ?: img.attr("data-src")
                                .takeIf { it.isNotBlank() }
                    }
                    ?.let { fixUrlNull(it) }

                if (href.contains("/film", ignoreCase = true)) {

                    newMovieSearchResponse(
                        title,
                        href,
                        TvType.Movie
                    ) {
                        posterUrl = poster
                    }

                } else {

                    newTvSeriesSearchResponse(
                        title,
                        href,
                        TvType.TvSeries
                    ) {
                        posterUrl = poster
                    }
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/"
        ).document

        return document
            .select("a[href]")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                if (
                    !href.contains("/dizi", true) &&
                    !href.contains("/film", true)
                ) {
                    return@mapNotNull null
                }

                val image = element.selectFirst("img")

                val title = image
                    ?.attr("alt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: element
                        .text()
                        .trim()
                        .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val poster = image
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }

                if (href.contains("/film", true)) {

                    newMovieSearchResponse(
                        title,
                        href,
                        TvType.Movie
                    ) {
                        posterUrl = poster
                    }

                } else {

                    newTvSeriesSearchResponse(
                        title,
                        href,
                        TvType.TvSeries
                    ) {
                        posterUrl = poster
                    }
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = "$mainUrl/"
        ).document

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("[property='og:title']")
                ?.attr("content")
                ?.trim()
            ?: return null

        val poster = document
            .selectFirst("[property='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }

        val description = document
            .selectFirst("[property='og:description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(document.text())
            ?.value
            ?.toIntOrNull()

        val episodeLinks = document
            .select("a[href]")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                val text = element
                    .text()
                    .trim()

                if (
                    !text.contains("Bölüm", true) &&
                    !href.contains("bolum", true)
                ) {
                    return@mapNotNull null
                }

                newEpisode(href) {
                    name = text.ifBlank { "Bölüm" }

                    val match = Regex(
                        """(\d+)\s*[xX]\s*(\d+)"""
                    ).find(text + " " + href)

                    this.season = match
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                    this.episode = match
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.toIntOrNull()
                }
            }
            .distinctBy { it.data }

        return if (episodeLinks.isNotEmpty()) {

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodeLinks
            ) {
                posterUrl = poster
                plot = description
                this.year = year
            }

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                plot = description
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            referer = "$mainUrl/"
        ).document

        val iframeUrls = document
            .select("iframe[src]")
            .map {
                it.attr("src")
            }
            .filter {
                it.isNotBlank()
            }
            .map {
                fixUrl(it)
            }
            .distinct()

        var found = false

        iframeUrls.forEach { iframeUrl ->

            try {
                val success = loadExtractor(
                    iframeUrl,
                    data,
                    subtitleCallback,
                    callback
                )

                if (success) {
                    found = true
                }

            } catch (_: Exception) {
            }
        }

        return found
    }
}
