package com.anihepsi.dizigom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
        "$mainUrl/tum-bolumler/" to "Son Eklenen Bölümler"
    )

    /*
     * Örnek:
     * https://www.dizigom.love/ted-lasso-1-sezon-6-bolum/
     */
    private val episodeRegex = Regex(
        """-(\d+)-sezon-(\d+)-bolum/?(?:\?.*)?$""",
        RegexOption.IGNORE_CASE
    )

    /*
     * ---------------------------------------------------------
     * IMAGE
     * ---------------------------------------------------------
     */

    private fun Element.getImageUrl(): String? {

        val image = if (tagName() == "img") {
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

    /*
     * ---------------------------------------------------------
     * MAIN PAGE
     * ---------------------------------------------------------
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data,
            referer = "$mainUrl/"
        ).document

        val results = when (request.name) {

            "Son Eklenen Bölümler" ->
                parseLatestEpisodes(document)

            else ->
                parseLatestSeries(document)
        }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = false
        )
    }

    /*
     * Gerçek ana sayfa yapısı:
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
     * LATEST EPISODES
     * ---------------------------------------------------------
     */

    private fun parseLatestEpisodes(
        document: Document
    ): List<SearchResponse> {

        return document
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

                val season = match
                    .groupValues
                    .getOrNull(1)

                val episode = match
                    .groupValues
                    .getOrNull(2)

                /*
                 * Kartın yakın çevresinde görsel varsa al.
                 */
                val parent = link.parent()

                val image =
                    link.selectFirst("img")
                        ?: parent?.selectFirst("img")
                        ?: parent?.parent()?.selectFirst("img")

                val poster = image
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        fixUrlNull(it)
                    }

                /*
                 * Dizi adı için önce img title.
                 */
                val imageTitle = image
                    ?.attr("title")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val imageAlt = image
                    ?.attr("alt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val linkTitle = link
                    .attr("title")
                    .trim()
                    .takeIf { it.isNotBlank() }

                val linkText = link
                    .text()
                    .trim()
                    .takeIf { it.isNotBlank() }

                val seriesName =
                    imageTitle
                        ?: imageAlt
                        ?: linkTitle

                val title = when {

                    !seriesName.isNullOrBlank() -> {
                        "$seriesName - $season. Sezon $episode. Bölüm"
                    }

                    !linkText.isNullOrBlank() &&
                        !linkText.matches(
                            Regex(
                                """.*Sezon.*Bölüm.*""",
                                RegexOption.IGNORE_CASE
                            )
                        ) -> {
                        "$linkText - $season. Sezon $episode. Bölüm"
                    }

                    else -> {
                        /*
                         * Son fallback:
                         * URL slugından dizi adını çıkar.
                         */
                        val slug = href
                            .substringBeforeLast("/")
                            .substringAfterLast("/")
                            .replace(
                                Regex(
                                    """-\d+-sezon-\d+-bolum$""",
                                    RegexOption.IGNORE_CASE
                                ),
                                ""
                            )
                            .replace("-", " ")
                            .split(" ")
                            .joinToString(" ") { word ->

                                word.replaceFirstChar { char ->

                                    if (char.isLowerCase()) {
                                        char.titlecase()
                                    } else {
                                        char.toString()
                                    }
                                }
                            }

                        "$slug - $season. Sezon $episode. Bölüm"
                    }
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
         * Önce posterli gerçek kartları ara.
         */
        val visualResults = document
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

        if (visualResults.isNotEmpty()) {
            return visualResults
        }

        /*
         * Search sayfası yalnız yazılı sonuç verirse fallback.
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
     * LOAD SERIES
     * ---------------------------------------------------------
     */

    override suspend fun load(
        url: String
    ): LoadResponse? {

        /*
         * Son Eklenen Bölümler bölümünden gelirsek URL
         * doğrudan episode URL'si olabilir.
         *
         * Episode HTML içinde:
         *
         * #benzerli a[href*='/diziler/']
         *
         * bize dizinin asıl sayfasını verir.
         */
        val firstDocument = app.get(
            url,
            referer = "$mainUrl/"
        ).document

        val seriesUrl = if (
            url.contains("/diziler/")
        ) {

            url

        } else {

            firstDocument
                .selectFirst(
                    "#benzerli a[href*='/diziler/']"
                )
                ?.attr("href")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    fixUrlNull(it)
                }
                ?: url
        }

        val document = if (
            seriesUrl == url
        ) {

            firstDocument

        } else {

            app.get(
                seriesUrl,
                referer = url
            ).document
        }

        /*
         * TITLE
         */
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

        /*
         * POSTER
         */
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

        /*
         * DESCRIPTION
         */
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

        /*
         * YEAR
         */
        val year = Regex(
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

        /*
         * GENRES
         */
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

        /*
         * EPISODES
         *
         * URL gerçek yapısı:
         *
         * /ted-lasso-1-sezon-1-bolum/
         */
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

                val seasonNumber = match
                    .groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()

                val episodeNumber = match
                    .groupValues
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

                    name =
                        episodeName.ifBlank {
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

            if (genres.isNotEmpty()) {
                tags = genres
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * PLAYER
     * ---------------------------------------------------------
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

        /*
         * Gerçek güncel DiziGom episode HTML:
         *
         * <div class="video-container">
         *   <iframe src="https://play2.pilavyerplay.top/...">
         * </div>
         */
        val iframeUrls = document
            .select(
                ".video-container iframe[src], .video iframe[src]"
            )
            .mapNotNull { iframe ->

                iframe.attr("src")
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
                // Bir iframe hata verirse diğerlerini denemeye devam et.
            }
        }

        return found
    }
}
