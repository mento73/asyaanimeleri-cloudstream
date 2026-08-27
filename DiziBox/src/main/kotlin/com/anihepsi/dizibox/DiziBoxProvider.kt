package com.anihepsi.dizibox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64

/*
 * DiziBox provider adapted for the Anihepsi CloudStream repository.
 *
 * References / attribution:
 * keyiflerolsun / Kekik-cloudstream
 * nikyokki / nik-cloudstream
 *
 * This implementation only uses:
 * - public HTML
 * - openly exposed iframe URLs
 * - standard Base64 decoding
 * - CloudStream's standard extractor system
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

    /*
     * Tek ana dizin.
     * A-B-C gibi ayrı CloudStream dizinleri yok.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi/" to "Tüm Diziler"
    )

    /*
     * ÖNEMLİ:
     * DiziBox mobil User-Agent ile eksik HTML döndürüyor.
     * Masaüstü User-Agent kullanılması gerekiyor.
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
     * ANA SAYFA / TÜM DİZİLER
     * ============================================================
     *
     * Gerçek arşiv:
     *
     * /dizi-arsivi/
     * /dizi-arsivi/page/2/
     * /dizi-arsivi/page/3/
     * ...
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

        val document = app.get(
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

        /*
         * Gerçek sonraki sayfa bağlantısı varsa
         * CloudStream devamını yükleyebilir.
         */
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
     * Arama için doğrudan DiziBox'ın gerçek arama sayfasını
     * kullanıyoruz.
     *
     * Gerçek sonuç yapısı:
     *
     * section#search
     *   article.detailed-article
     */
    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.isBlank()) {
            return emptyList()
        }

        val encoded =
            java.net.URLEncoder.encode(
                query.trim(),
                "UTF-8"
            )

        val document =
            app.get(
                "$mainUrl/?s=$encoded",
                headers = headers,
                referer = "$mainUrl/"
            ).document

        return document
            .select("#search article.detailed-article")
            .mapNotNull { article ->
                article.toSearchResult()
            }
            .distinctBy {
                it.url
            }
    }

    /*
     * ============================================================
     * DİZİ KARTI
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

        val candidates = listOf(
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

        /*
         * Gerçek sezon yapısı:
         *
         * #seasons-list a
         */
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

        if (seasonLinks.isNotEmpty()) {

            /*
             * Ana dizi sayfasında bazen ilk sezonun bölümleri
             * doğrudan bulunuyor.
             */
            collectEpisodesFromSeasonDocument(
                document = document,
                defaultSeason =
                    seasonLinks.firstOrNull()?.first,
                episodes = episodes
            )

            /*
             * Bütün sezonları sırayla tara.
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

        } else {

            /*
             * Direkt sezon sayfası açılmışsa fallback.
             */
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
     * ODNOK URL
     * ============================================================
     */
    private fun decodeOdnokUrl(
        iframeUrl: String
    ): String? {

        val encoded =
            Regex(
                """[?&]v=([^&]+)"""
            )
                .find(
                    iframeUrl
                )
                ?.groupValues
                ?.getOrNull(1)
                ?: return null

        return try {

            val urlDecoded =
                URLDecoder.decode(
                    encoded,
                    "UTF-8"
                )

            val bytes =
                Base64
                    .getDecoder()
                    .decode(
                        urlDecoded
                    )

            val decoded =
                String(
                    bytes,
                    Charsets.UTF_8
                )
                    .trim()

            if (
                decoded.startsWith("http://") ||
                decoded.startsWith("https://")
            ) {
                decoded
            } else {
                null
            }

        } catch (_: Exception) {
            null
        }
    }

    /*
     * ============================================================
     * OK.RU NORMALIZE
     * ============================================================
     */
    private fun normalizeOkUrl(
        url: String
    ): String {

        val id =
            Regex(
                """ok\.ru/video/(\d+)""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    url
                )
                ?.groupValues
                ?.getOrNull(1)

        return if (id != null) {

            "https://ok.ru/videoembed/$id"

        } else {

            url
        }
    }

    /*
     * ============================================================
     * PLAYER
     * ============================================================
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {

        /*
         * ODNOK / OK.RU
         */
        val episodeBase =
            data.trimEnd('/')

        val odnokUrl =
            "$episodeBase/3/"

        try {

            val odnokDocument =
                app.get(
                    odnokUrl,
                    headers = headers,
                    referer = data
                ).document

            val iframe =
                odnokDocument
                    .selectFirst(
                        """
                        #video-area iframe[src],
                        iframe[src*="/player/haydi.php"]
                        """.trimIndent()
                    )
                    ?.attr("src")
                    ?.trim()

            if (!iframe.isNullOrBlank()) {

                val iframeUrl =
                    fixUrlNull(
                        iframe
                    )

                if (iframeUrl != null) {

                    val decodedUrl =
                        decodeOdnokUrl(
                            iframeUrl
                        )

                    if (decodedUrl != null) {

                        val okEmbed =
                            normalizeOkUrl(
                                decodedUrl
                            )

                        loadExtractor(
                            okEmbed,
                            odnokUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }

        } catch (_: Exception) {
        }

        /*
         * DBX PRO fallback
         */
        try {

            val document =
                app.get(
                    data,
                    headers = headers,
                    referer = "$mainUrl/"
                ).document

            val iframe =
                document
                    .selectFirst(
                        "#video-area iframe[src]"
                    )
                    ?.attr("src")
                    ?.trim()

            if (!iframe.isNullOrBlank()) {

                val iframeUrl =
                    fixUrlNull(
                        iframe
                    )

                if (iframeUrl != null) {

                    loadExtractor(
                        iframeUrl,
                        data,
                        subtitleCallback,
                        callback
                    )
                }
            }

        } catch (_: Exception) {
        }

        return true
    }
}
