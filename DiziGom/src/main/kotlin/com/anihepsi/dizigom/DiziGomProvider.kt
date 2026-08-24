package com.anihepsi.dizigom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/*
 * DiziGom provider
 *
 * Original provider lineage:
 * Kekik / Kraptor ecosystem.
 *
 * Modernized for the Anihepsi CloudStream repository
 * using the current DiziGom site structure.
 */

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

    /*
     * Current episode URLs look like:
     *
     * /ted-lasso-1-sezon-6-bolum/
     */
    private val episodeRegex = Regex(
        """-(\d+)-sezon-(\d+)-bolum/?(?:\?.*)?$""",
        RegexOption.IGNORE_CASE
    )

    private fun Element.imageUrl(): String? {

        val image =
            if (tagName() == "img") {
                this
            } else {
                selectFirst("img")
            } ?: return null

        val src =
            image.attr("src")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: image.attr("data-src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                ?: image.attr("data-lazy-src")
                    .trim()
                    .takeIf { it.isNotBlank() }

        return src?.let {
            fixUrlNull(it)
        }
    }

    private fun Element.seriesTitle(): String? {

        return selectFirst("img")
            ?.attr("alt")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

            ?: attr("title")
                .trim()
                .takeIf { it.isNotBlank() }

            ?: selectFirst(
                ".title, .name, h2, h3, h4"
            )
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            ?: text()
                .trim()
                .takeIf { it.isNotBlank() }
    }

    private fun Element.toSeriesSearchResponse(): SearchResponse? {

        val href = attr("href")
            .trim()
            .takeIf {
                it.contains("/diziler/")
            }
            ?.let {
                fixUrlNull(it)
            }
            ?: return null

        val title =
            seriesTitle()
                ?: return null

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = imageUrl()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data,
            referer = "$mainUrl/"
        ).document

        val results =
            if (request.name == "Son Eklenen Bölümler") {

                parseLatestEpisodes(document)

            } else {

                parseLatestSeries(document)
            }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = false
        )
    }

    /*
     * Important:
     *
     * The site header contains a huge alphabetical list of
     * /diziler/ links without posters.
     *
     * Therefore we only accept series links that actually
     * contain an image.
     */
    private fun parseLatestSeries(
        document: Document
    ): List<SearchResponse> {

        return document
            .select(
                "a[href*='/diziler/']:has(img)"
            )
            .mapNotNull { element ->

                element.toSeriesSearchResponse()
            }
            .distinctBy {
                it.url
            }
    }

    /*
     * /tum-bolumler/ contains direct episode URLs.
     *
     * Example:
     * /ted-lasso-1-sezon-6-bolum/
     */
    private fun parseLatestEpisodes(
        document: Document
    ): List<SearchResponse> {

        return document
            .select("a[href]")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                val match =
                    episodeRegex.find(href)
                        ?: return@mapNotNull null

                val text = element
                    .text()
                    .trim()

                val season =
                    match.groupValues
                        .getOrNull(1)

                val episode =
                    match.groupValues
                        .getOrNull(2)

                val title =
                    text
                        .takeIf { it.isNotBlank() }
                        ?: "Sezon $season Bölüm $episode"

                /*
                 * We intentionally keep the episode URL here.
                 * load() knows how to resolve an episode page
                 * back to its series page.
                 */
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl =
                        element.imageUrl()
                }
            }
            .distinctBy {
                it.url
            }
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

        /*
         * Prefer visual search-result cards.
         */
        val cards = document
            .select(
                "a[href*='/diziler/']:has(img)"
            )
            .mapNotNull {
                it.toSeriesSearchResponse()
            }
            .distinctBy {
                it.url
            }

        if (cards.isNotEmpty()) {
            return cards
        }

        /*
         * Fallback for simple text search results.
         */
        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                val title = element
                    .attr("title")
                    .trim()
                    .takeIf { it.isNotBlank() }

                    ?: element
                        .text()
                        .trim()
                        .takeIf { it.isNotBlank() }

                    ?: return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                )
            }
            .distinctBy {
                it.url
            }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        /*
         * A result in "Son Eklenen Bölümler" may point
         * directly to an episode page.
         *
         * The episode HTML contains:
         *
         * #benzerli a[href*='/diziler/']
         *
         * which links back to the series page.
         */
        val firstDocument = app.get(
            url,
            referer = "$mainUrl/"
        ).document

        val seriesUrl =
            if (url.contains("/diziler/")) {

                url

            } else {

                firstDocument
                    .selectFirst(
                        "#benzerli a[href*='/diziler/']"
                    )
                    ?.attr("href")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: url
            }

        val document =
            if (seriesUrl == url) {

                firstDocument

            } else {

                app.get(
                    seriesUrl,
                    referer = url
                ).document
            }

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.substringBefore(" izle")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

            ?: document
                .selectFirst(
                    "meta[property='og:title']"
                )
                ?.attr("content")
                ?.substringBefore(" izle")
                ?.substringBefore(" - Dizigom")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            ?: return null

        val poster = document
            .selectFirst(
                "meta[property='og:image']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }

            ?: document
                .selectFirst("img[src]")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrlNull(it) }

        val description = document
            .selectFirst(
                "meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

            ?: document
                .selectFirst(
                    "meta[name='description']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val year = Regex(
            """\b(19|20)\d{2}\b"""
        )
            .find(document.text())
            ?.value
            ?.toIntOrNull()

        val episodes = document
            .select("a[href]")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                val match =
                    episodeRegex.find(href)
                        ?: return@mapNotNull null

                val seasonNumber =
                    match.groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()

                val episodeNumber =
                    match.groupValues
                        .getOrNull(2)
                        ?.toIntOrNull()

                val episodeText =
                    element
                        .selectFirst(
                            ".epidosename"
                        )
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }

                        ?: element
                            .text()
                            .trim()
                            .takeIf { it.isNotBlank() }

                        ?: buildString {

                            if (seasonNumber != null) {
                                append(
                                    "$seasonNumber. Sezon "
                                )
                            }

                            if (episodeNumber != null) {
                                append(
                                    "$episodeNumber. Bölüm"
                                )
                            }
                        }

                newEpisode(href) {

                    name =
                        episodeText.ifBlank {
                            "Bölüm"
                        }

                    this.season =
                        seasonNumber

                    this.episode =
                        episodeNumber
                }
            }
            .distinctBy {
                it.data
            }
            .sortedWith(
                compareBy<Episode>(
                    { it.season ?: 0 },
                    { it.episode ?: 0 }
                )
            )

        return newTvSeriesLoadResponse(
            title,
            seriesUrl,
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

        /*
         * Current DiziGom episode pages use:
         *
         * .video-container iframe
         *
         * Current example host:
         * play2.pilavyerplay.top
         */
        val iframeUrls = document
            .select(
                ".video-container iframe[src], iframe[src]"
            )
            .mapNotNull { iframe ->

                iframe
                    .attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
            }
            .distinct()

        if (iframeUrls.isEmpty()) {
            return false
        }

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
