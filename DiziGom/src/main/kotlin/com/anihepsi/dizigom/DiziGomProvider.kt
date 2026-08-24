package com.anihepsi.dizigom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// Original DiziGom provider lineage: Kekik / Kraptor ecosystem.
// Modernized for the Anihepsi CloudStream repository.

class DiziGomProvider : MainAPI() {

    override var mainUrl = "https://www.dizigom.love"
    override var name = "DiziGom"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Diziler",
        "$mainUrl/tum-bolumler/" to "Son Eklenen Bölümler"
    )

    private fun Element.getPoster(): String? {

        val img = selectFirst("img") ?: return null

        val value =
            img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() }

        return value?.let { fixUrlNull(it) }
    }

    private fun Element.toSeriesResult(): SearchResponse? {

        val link = when {
            tagName() == "a" -> this
            else -> selectFirst("a[href*='/diziler/']")
        } ?: return null

        val href = link
            .attr("href")
            .trim()
            .takeIf {
                it.contains("/diziler/")
            }
            ?.let { fixUrlNull(it) }
            ?: return null

        val title =
            link.selectFirst("img")
                ?.attr("alt")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.attr("title")
                    .trim()
                    .takeIf { it.isNotBlank() }
                ?: selectFirst("h2, h3, h4, .title, .name")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: link.text()
                    .trim()
                    .takeIf { it.isNotBlank() }
                ?: return null

        val poster =
            link.getPoster()
                ?: getPoster()

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            if (page > 1 && request.data == "$mainUrl/") {
                "$mainUrl/page/$page/"
            } else {
                request.data
            }

        val document = app.get(
            url,
            referer = "$mainUrl/"
        ).document

        val results =
            if (request.name == "Son Eklenen Bölümler") {

                document
                    .select("a[href]")
                    .mapNotNull { element ->

                        val href = element
                            .attr("href")
                            .trim()

                        val text = element
                            .text()
                            .trim()

                        val match = Regex(
                            """(\d+)\.?\s*Sezon\s+(\d+)\.?\s*Bölüm""",
                            RegexOption.IGNORE_CASE
                        ).find(text)

                        if (match == null) {
                            return@mapNotNull null
                        }

                        /*
                         * On the episode list we link back to the series
                         * page whenever possible.
                         */
                        val parent = element.parent()

                        val seriesLink =
                            parent
                                ?.selectFirst("a[href*='/diziler/']")
                                ?: element
                                    .closest("li, article, div")
                                    ?.selectFirst("a[href*='/diziler/']")

                        seriesLink?.toSeriesResult()
                    }
                    .distinctBy { it.url }

            } else {

                document
                    .select("a[href*='/diziler/']")
                    .mapNotNull {
                        it.toSeriesResult()
                    }
                    .distinctBy { it.url }
            }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = request.data == "$mainUrl/"
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val q = query
            .trim()
            .replace(" ", "+")

        val document = app.get(
            "$mainUrl/?s=$q",
            referer = "$mainUrl/"
        ).document

        return document
            .select("a[href*='/diziler/']")
            .mapNotNull {
                it.toSeriesResult()
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
            ?.substringBefore(" izle")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.substringBefore(" izle")
                ?.trim()
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: document
                .selectFirst("img")
                ?.let { img ->
                    img.attr("data-src")
                        .takeIf { it.isNotBlank() }
                        ?: img.attr("src")
                            .takeIf { it.isNotBlank() }
                }
                ?.let { fixUrlNull(it) }

        val description = document
            .selectFirst("meta[property='og:description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val year = Regex(
            """Yapım\s*Yılı\s*:\s*((?:19|20)\d{2})""",
            RegexOption.IGNORE_CASE
        )
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val episodes = document
            .select("a[href]")
            .mapNotNull { element ->

                val text = element
                    .text()
                    .trim()

                val match = Regex(
                    """(\d+)\.?\s*Sezon\s+(\d+)\.?\s*Bölüm""",
                    RegexOption.IGNORE_CASE
                ).find(text)
                    ?: return@mapNotNull null

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                if (href.contains("/diziler/")) {
                    return@mapNotNull null
                }

                val seasonNumber =
                    match.groupValues[1].toIntOrNull()

                val episodeNumber =
                    match.groupValues[2].toIntOrNull()

                newEpisode(href) {
                    name = text
                        .replace("İzledim", "")
                        .trim()

                    this.season = seasonNumber
                    this.episode = episodeNumber
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode>(
                    { it.season ?: 0 },
                    { it.episode ?: 0 }
                )
            )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot = description
            this.year = year
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
            .mapNotNull { iframe ->

                iframe
                    .attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
            }
            .distinct()

        var found = false

        iframeUrls.forEach { iframeUrl ->

            try {

                val success = loadExtractor(
                    url = iframeUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
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
