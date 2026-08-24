package com.anihepsi.dizigom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

/*
 * DiziGom Provider
 *
 * Original provider lineage:
 * Kekik / Kraptor ecosystem.
 *
 * Modernized for the Anihepsi CloudStream repository.
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
        "$mainUrl/dizi-izle/" to "Tüm Diziler"
    )

    private val episodeRegex = Regex(
        """-(\d+)-sezon-(\d+)-bolum/?(?:\?.*)?$""",
        RegexOption.IGNORE_CASE
    )

    /*
     * ---------------------------------------------------------
     * MAIN PAGE
     * ---------------------------------------------------------
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = when {

            request.name == "Tüm Diziler" && page > 1 ->
                "$mainUrl/dizi-izle/page/$page/"

            else ->
                request.data
        }

        val document = app.get(
            url,
            referer = "$mainUrl/"
        ).document

        val results = when (request.name) {

            "Tüm Diziler" ->
                parseAllSeries(document)

            else ->
                parseLatestSeries(document)
        }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = request.name == "Tüm Diziler"
        )
    }

    /*
     * ---------------------------------------------------------
     * SON EKLENEN DİZİLER
     * ---------------------------------------------------------
     *
     * Gerçek yapı:
     *
     * <a href=".../diziler/ted-lasso/">
     *     <img
     *         src=".../Ted-Lasso.webp"
     *         title="Ted Lasso"
     *     >
     * </a>
     */

    private fun parseLatestSeries(
        document: Document
    ): List<SearchResponse> {

        return document
            .select("a[href*='/diziler/'] > img[src]")
            .mapNotNull { image ->

                val link = image.parent()
                    ?: return@mapNotNull null

                val href = link
                    .attr("href")
                    .trim()
                    .takeIf {
                        it.contains("/diziler/")
                    }
                    ?.let {
                        fixUrlNull(it)
                    }
                    ?: return@mapNotNull null

                val title =
                    image.attr("title")
                        .trim()
                        .takeIf { it.isNotBlank() }

                        ?: image.attr("alt")
                            .trim()
                            .takeIf { it.isNotBlank() }

                        ?: return@mapNotNull null

                val poster = image
                    .attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        fixUrlNull(it)
                    }

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy {
                it.url
            }
    }

    /*
     * ---------------------------------------------------------
     * TÜM DİZİLER
     * ---------------------------------------------------------
     *
     * Gerçek yapı:
     *
     * <a href=".../diziler/breaking-bad/">
     *     Breaking Bad
     * </a>
     *
     * Burada poster özellikle çekilmiyor.
     * Amaç hızlı ve hafif bir tam katalog.
     */

    private fun parseAllSeries(
        document: Document
    ): List<SearchResponse> {

        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { link ->

                val href = link
                    .attr("href")
                    .trim()
                    .takeIf {
                        it.contains("/diziler/")
                    }
                    ?.let {
                        fixUrlNull(it)
                    }
                    ?: return@mapNotNull null

                val title =
                    link.text()
                        .trim()
                        .takeIf { it.isNotBlank() }

                        ?: link.attr("title")
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

    /*
     * ---------------------------------------------------------
     * SEARCH
     * ---------------------------------------------------------
     */

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
         * Önce posterli arama kartı varsa onu kullan.
         */
        val visualResults =
            parseLatestSeries(document)

        if (visualResults.isNotEmpty()) {
            return visualResults
        }

        /*
         * Yoksa normal /diziler/ linklerini tara.
         */
        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { link ->

                val href = link
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        fixUrlNull(it)
                    }
                    ?: return@mapNotNull null

                val title =
                    link.attr("title")
                        .trim()
                        .takeIf { it.isNotBlank() }

                        ?: link.text()
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

    /*
     * ---------------------------------------------------------
     * SERIES DETAIL
     * ---------------------------------------------------------
     */

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = "$mainUrl/"
        ).document

        val title =
            document
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

        val poster =
            document
                .selectFirst(
                    "meta[property='og:image']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    fixUrlNull(it)
                }

                ?: document
                    .selectFirst("img[src]")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        fixUrlNull(it)
                    }

        val description =
            document
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

        val year =
            Regex(
                """Yapım\s*Yılı\s*:?\s*((?:19|20)\d{2})""",
                RegexOption.IGNORE_CASE
            )
                .find(document.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

                ?: Regex(
                    """\b(?:19|20)\d{2}\b"""
                )
                    .find(document.text())
                    ?.value
                    ?.toIntOrNull()

        val genres = document
            .select(
                "a[href*='/tur/'], a[href*='/kategori/'], a[href*='/genre/']"
            )
            .map {
                it.text().trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()

        val episodes = document
            .select("a[href]")
            .mapNotNull { link ->

                val href = link
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        fixUrlNull(it)
                    }
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

                val episodeName =
                    link.selectFirst(
                        ".epidosename"
                    )
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }

                        ?: link.text()
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

                    name = episodeName.ifBlank {
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
            url,
            TvType.TvSeries,
            episodes
        ) {

            posterUrl = poster
            plot = description
            this.year = year

            if (genres.isNotEmpty()) {
                tags = genres
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * PLAYER
     * ---------------------------------------------------------
     *
     * Pilavyer extractor ayrıca ele alınacak.
     */

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
            .select(
                ".video-container iframe[src], .video iframe[src]"
            )
            .mapNotNull { iframe ->

                iframe
                    .attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        fixUrlNull(it)
                    }
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
