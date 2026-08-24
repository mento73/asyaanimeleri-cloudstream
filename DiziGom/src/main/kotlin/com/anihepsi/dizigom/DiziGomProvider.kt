package com.anihepsi.dizigom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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
        "$mainUrl/" to "Son Eklenen Diziler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data,
            referer = "$mainUrl/"
        ).document

        val results = document
            .select("a[href*='/diziler/']")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                if (!href.contains("/diziler/")) {
                    return@mapNotNull null
                }

                val image = element.selectFirst("img")

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
                            ?: img.attr("data-lazy-src")
                                .takeIf { it.isNotBlank() }
                    }
                    ?.let { fixUrlNull(it) }

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = query
            .trim()
            .replace(" ", "+")

        val document = app.get(
            "$mainUrl/?s=$encodedQuery",
            referer = "$mainUrl/"
        ).document

        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                val image = element.selectFirst("img")

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

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
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
                    img.attr("src")
                        .takeIf { it.isNotBlank() }
                        ?: img.attr("data-src")
                            .takeIf { it.isNotBlank() }
                }
                ?.let { fixUrlNull(it) }

        val description = document
            .selectFirst("meta[property='og:description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .select("p")
                .map { it.text().trim() }
                .firstOrNull {
                    it.length > 80 &&
                    !it.contains("Dizigom", ignoreCase = true)
                }

        val year = Regex(
            """Yapım\s*Yılı\s*:\s*((?:19|20)\d{2})""",
            RegexOption.IGNORE_CASE
        )
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""")
                .find(document.text())
                ?.value
                ?.toIntOrNull()

        val episodes = document
            .select("a[href]")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val text = element
                    .text()
                    .trim()

                val combined = "$text $href"

                val seasonEpisode = Regex(
                    """(\d+)\.?\s*Sezon\s*(\d+)\.?\s*Bölüm""",
                    RegexOption.IGNORE_CASE
                ).find(combined)

                if (
                    seasonEpisode == null &&
                    !href.contains("-sezon-", ignoreCase = true) &&
                    !href.contains("-bolum", ignoreCase = true)
                ) {
                    return@mapNotNull null
                }

                if (href.contains("/diziler/")) {
                    return@mapNotNull null
                }

                val episodeUrl = fixUrlNull(href)
                    ?: return@mapNotNull null

                val seasonNumber = seasonEpisode
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex(
                        """(\d+)-sezon""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                val episodeNumber = seasonEpisode
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()
                    ?: Regex(
                        """sezon-(\d+)-bolum""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                newEpisode(episodeUrl) {

                    name = text
                        .replace("İzledim", "")
                        .trim()
                        .ifBlank {

                            buildString {

                                if (seasonNumber != null) {
                                    append("$seasonNumber. Sezon ")
                                }

                                if (episodeNumber != null) {
                                    append("$episodeNumber. Bölüm")
                                }
                            }.ifBlank {
                                "Bölüm"
                            }
                        }

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
