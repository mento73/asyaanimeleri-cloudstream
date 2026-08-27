package com.anihepsi.dizibox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/*
 * DiziBox provider adapted for the Anihepsi CloudStream repository.
 *
 * References / attribution:
 * keyiflerolsun / Kekik-cloudstream
 * nikyokki / nik-cloudstream
 *
 * This implementation uses:
 * - publicly visible catalogue HTML
 * - publicly exposed episode/player pages
 * - publicly exposed iframe URLs
 * - CloudStream's existing extractor system
 *
 * No custom stream decryption or protected-media extraction
 * is implemented here.
 */

class DiziBoxProvider : MainAPI() {

    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBox"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi/" to "Tüm Diziler"
    )

    /*
     * DiziBox mobile User-Agent ile eksik HTML döndürdüğü için
     * desktop User-Agent kullanıyoruz.
     */
    private val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/151.0.0.0 Safari/537.36",

        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,image/apng,*/*;q=0.8",

        "Accept-Language" to
            "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",

        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    /*
     * ============================================================
     * ANA SAYFA
     * ============================================================
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            if (page <= 1) {
                request.data
            } else {
                "${request.data.trimEnd('/')}/page/$page/"
            }

        val document =
            app.get(
                url,
                headers = headers,
                referer = "$mainUrl/"
            ).document

        val results =
            document
                .select(".col-1 article.detailed-article")
                .mapNotNull { article ->
                    article.toSearchResult()
                }
                .distinctBy {
                    it.url
                }

        val hasNext =
            document.selectFirst(
                ".woca-pagination a[href*=\"/page/${page + 1}/\"]"
            ) != null

        return newHomePageResponse(
            request.name,
            results,
            hasNext = hasNext
        )
    }

    /*
     * ============================================================
     * ARAMA
     * ============================================================
     *
     * DiziBox'ın kendi arama endpoint'i CloudStream tarafında
     * tutarlı olmadığı için alfabetik arşiv kullanılıyor.
     */

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val cleanQuery =
            query.trim()

        if (cleanQuery.isBlank()) {
            return emptyList()
        }

        val document =
            app.get(
                "$mainUrl/dizi-arsivi/",
                headers = headers,
                referer = "$mainUrl/"
            ).document

        val normalizedQuery =
            cleanQuery.lowercase()

        return document
            .select(
                "ul.alphabetical-category-list a[href*=\"/diziler/\"]"
            )
            .mapNotNull { link ->

                val href =
                    fixUrlNull(
                        link.attr("href")
                    )
                        ?: return@mapNotNull null

                val title =
                    link
                        .text()
                        .trim()
                        .ifBlank {
                            link
                                .attr("title")
                                .removeSuffix(" izle")
                                .trim()
                        }

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                if (
                    !title
                        .lowercase()
                        .contains(normalizedQuery)
                ) {
                    return@mapNotNull null
                }

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
     * ============================================================
     * ARŞİV KARTI
     * ============================================================
     */

    private fun Element.toSearchResult(): SearchResponse? {

        val link =
            selectFirst(
                ".detailed-article-container h3 a[href]"
            )
                ?: selectFirst(
                    "h3 a[href]"
                )
                ?: selectFirst(
                    "figure a[href]"
                )
                ?: return null

        val href =
            fixUrlNull(
                link.attr("href")
            )
                ?: return null

        if (!href.contains("/diziler/")) {
            return null
        }

        val title =
            link
                .text()
                .trim()
                .ifBlank {
                    selectFirst("img.main-cover")
                        ?.attr("alt")
                        ?.trim()
                        .orEmpty()
                }

        if (title.isBlank()) {
            return null
        }

        val poster =
            selectFirst("img.main-cover")
                ?.let {
                    getImageUrl(it)
                }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    /*
     * ============================================================
     * RESİM
     * ============================================================
     */

    private fun getImageUrl(
        image: Element
    ): String? {

        val candidates =
            listOf(
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("src")
            )

        for (candidate in candidates) {

            val value =
                candidate.trim()

            if (
                value.isBlank() ||
                value.startsWith(
                    "data:image",
                    true
                )
            ) {
                continue
            }

            return fixUrlNull(value)
        }

        return null
    }

    /*
     * ============================================================
     * DİZİ DETAY
     * ============================================================
     */

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(
                url,
                headers = headers,
                referer = "$mainUrl/"
            ).document

        val title =
            getSeriesTitle(document)
                ?: return null

        val poster =
            getSeriesPoster(document)

        val plot =
            getSeriesPlot(document)

        val episodes =
            mutableListOf<Episode>()

        val seasonLinks =
            document
                .select("#seasons-list a[href]")
                .mapNotNull { seasonElement ->

                    val seasonUrl =
                        fixUrlNull(
                            seasonElement.attr("href")
                        )
                            ?: return@mapNotNull null

                    val seasonNumber =
                        extractSeasonNumber(
                            seasonElement.text(),
                            seasonUrl
                        )
                            ?: return@mapNotNull null

                    seasonNumber to seasonUrl
                }
                .distinctBy {
                    it.second
                }
                .sortedBy {
                    it.first
                }

        /*
         * Ana sayfadaki bölümleri al.
         */
        collectEpisodesFromSeasonDocument(
            document = document,
            defaultSeason =
                seasonLinks.firstOrNull()?.first,
            episodes = episodes
        )

        /*
         * Sezon sayfalarını tara.
         */
        for (
            (seasonNumber, seasonUrl)
            in seasonLinks
        ) {

            try {

                val seasonDocument =
                    app.get(
                        seasonUrl,
                        headers = headers,
                        referer = url
                    ).document

                collectEpisodesFromSeasonDocument(
                    document = seasonDocument,
                    defaultSeason = seasonNumber,
                    episodes = episodes
                )

            } catch (_: Exception) {
            }
        }

        if (
            seasonLinks.isEmpty() &&
            episodes.isEmpty()
        ) {

            val seasonNumber =
                extractSeasonNumber(
                    document.title(),
                    url
                )

            collectEpisodesFromSeasonDocument(
                document = document,
                defaultSeason = seasonNumber,
                episodes = episodes
            )
        }

        val finalEpisodes =
            episodes
                .distinctBy {
                    it.data
                }
                .sortedWith(
                    compareBy<Episode>(
                        {
                            it.season ?: 0
                        },
                        {
                            it.episode ?: 0
                        }
                    )
                )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            finalEpisodes
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    /*
     * ============================================================
     * BAŞLIK
     * ============================================================
     */

    private fun getSeriesTitle(
        document: Document
    ): String? {

        val ogTitle =
            document
                .selectFirst(
                    "meta[property=\"og:title\"]"
                )
                ?.attr("content")
                ?.trim()

        if (!ogTitle.isNullOrBlank()) {

            return ogTitle
                .removeSuffix(" izle")
                .trim()
        }

        return document
            .selectFirst(
                "h1, .tv-title, .post-title, .cat-title"
            )
            ?.text()
            ?.removeSuffix(" izle")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    /*
     * ============================================================
     * POSTER
     * ============================================================
     */

    private fun getSeriesPoster(
        document: Document
    ): String? {

        val ogImage =
            document
                .selectFirst(
                    "meta[property=\"og:image\"]"
                )
                ?.attr("content")
                ?.trim()

        if (!ogImage.isNullOrBlank()) {
            return fixUrlNull(ogImage)
        }

        val image =
            document
                .selectFirst(
                    """
                    .tv-cover img,
                    .tv-poster img,
                    img[itemprop="image"],
                    .figure img,
                    .archive-box img
                    """.trimIndent()
                )

        return image?.let {
            getImageUrl(it)
        }
    }

    /*
     * ============================================================
     * AÇIKLAMA
     * ============================================================
     */

    private fun getSeriesPlot(
        document: Document
    ): String? {

        val description =
            document
                .selectFirst(
                    """
                    .tv-story,
                    .tv-overview .description,
                    .tv-overview p,
                    .entry-content p,
                    meta[name="description"]
                    """.trimIndent()
                )
                ?: return null

        return if (
            description
                .tagName()
                .equals(
                    "meta",
                    ignoreCase = true
                )
        ) {

            description
                .attr("content")
                .trim()

        } else {

            description
                .text()
                .trim()
        }
    }

    /*
     * ============================================================
     * SEZON NUMARASI
     * ============================================================
     */

    private fun extractSeasonNumber(
        text: String?,
        url: String
    ): Int? {

        val fromText =
            text
                ?.let {

                    Regex(
                        """(\d+)\s*\.\s*Sezon""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(it)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                }

        if (fromText != null) {
            return fromText
        }

        return Regex(
            """/(\d+)-sezon-""",
            RegexOption.IGNORE_CASE
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    /*
     * ============================================================
     * BÖLÜMLER
     * ============================================================
     */

    private fun collectEpisodesFromSeasonDocument(
        document: Document,
        defaultSeason: Int?,
        episodes: MutableList<Episode>
    ) {

        val episodeElements =
            document.select(
                "#category-posts a.season-episode[href]"
            )

        for (element in episodeElements) {

            val episodeUrl =
                fixUrlNull(
                    element.attr("href")
                )
                    ?: continue

            val numbers =
                Regex(
                    """-(\d+)-sezon-(\d+)-bolum(?:-|/|$)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(
                        episodeUrl
                    )

            val seasonNumber =
                numbers
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: defaultSeason

            val episodeNumber =
                numbers
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()
                    ?: Regex(
                        """(\d+)\s*\.\s*Bölüm""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            element.text()
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

            val episodeName =
                if (
                    seasonNumber != null &&
                    episodeNumber != null
                ) {

                    "$seasonNumber. Sezon $episodeNumber. Bölüm"

                } else {

                    element
                        .text()
                        .trim()
                        .ifBlank {
                            "Bölüm"
                        }
                }

            episodes.add(
                newEpisode(
                    episodeUrl
                ) {
                    name = episodeName
                    season = seasonNumber
                    episode = episodeNumber
                }
            )
        }
    }

    /*
     * ============================================================
     * PLAYER
     * ============================================================
     *
     * DiziBox'ın bölüm sayfasında açıkça sunduğu player sayfaları
     * kullanılıyor.
     *
     * Ana bölüm:
     * DBX Pro
     *
     * /2/:
     * Moly+
     *
     * /3/:
     * Odnok
     *
     * Burada yalnızca HTML içinde açıkça verilen iframe URL'leri
     * CloudStream'ın mevcut extractor sistemine gönderiliyor.
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {

        val episodeBase =
            data.trimEnd('/')

        /*
         * Önce ana bölüm sayfasını açıyoruz.
         *
         * Buradaki select menüsünden gerçek player sayfalarını
         * okumayı deniyoruz.
         */
        val mainDocument =
            try {

                app.get(
                    "$episodeBase/",
                    headers = headers,
                    referer = "$mainUrl/"
                ).document

            } catch (_: Exception) {

                null
            }

        /*
         * Sabit fallback yolları.
         */
        val playerPages =
            mutableListOf(
                "$episodeBase/",
                "$episodeBase/2/",
                "$episodeBase/3/"
            )

        /*
         * DiziBox'ın select menüsünde verdiği gerçek player
         * sayfalarını da ekle.
         *
         * Böylece ileride /2/ veya /3/ değişirse mümkün olduğunca
         * HTML'deki gerçek bağlantıyı kullanırız.
         */
        mainDocument
            ?.select(
                "select.woca-linkpages-dd option"
            )
            ?.forEach { option ->

                val candidate =
                    option.attr("value")
                        .ifBlank {
                            option.attr("href")
                        }
                        .trim()

                if (candidate.isNotBlank()) {

                    fixUrlNull(candidate)
                        ?.let {
                            playerPages.add(it)
                        }
                }
            }

        /*
         * Aynı URL'yi tekrar denememek için temizle.
         */
        val uniquePlayerPages =
            playerPages
                .distinct()

        var foundAnyIframe = false

        /*
         * Her player sayfasındaki public iframe'i bul ve
         * CloudStream'ın mevcut extractor mekanizmasına gönder.
         */
        for (playerPage in uniquePlayerPages) {

            try {

                val document =
                    app.get(
                        playerPage,
                        headers = headers,
                        referer = "$episodeBase/"
                    ).document

                val iframeElements =
                    document.select(
                        "#video-area iframe[src]"
                    )

                for (iframe in iframeElements) {

                    val iframeUrl =
                        fixUrlNull(
                            iframe.attr("src")
                        )
                            ?: continue

                    foundAnyIframe = true

                    try {

                        loadExtractor(
                            iframeUrl,
                            playerPage,
                            subtitleCallback,
                            callback
                        )

                    } catch (_: Exception) {
                    }
                }

            } catch (_: Exception) {
            }
        }

        /*
         * true dönmek iframe bulunduğunu gösterir.
         * Extractor gerçek video üretmezse CloudStream yine
         * "bağlantı bulunamadı" diyebilir.
         */
        return foundAnyIframe
    }
}
