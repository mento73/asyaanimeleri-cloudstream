package com.anihepsi.dizibox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
 * This provider only uses public HTML, openly exposed player/iframe URLs,
 * normal Base64 URL decoding, and CloudStream's standard extractor system.
 */

class DiziBoxProvider : MainAPI() {

    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBox"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

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

    // -------------------------------------------------------------------------
    // ANA SAYFA
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi/" to "Tüm Diziler"
    )

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

    // -------------------------------------------------------------------------
    // ARAMA
    // -------------------------------------------------------------------------

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val cleanQuery = query.trim()

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
                    link.text()
                        .trim()
                        .ifBlank {
                            link.attr("title")
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

    // -------------------------------------------------------------------------
    // DİZİ DETAY / SEZON / BÖLÜMLER
    // -------------------------------------------------------------------------

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

        collectEpisodesFromSeasonDocument(
            document = document,
            defaultSeason =
                seasonLinks
                    .firstOrNull()
                    ?.first,
            episodes = episodes
        )

        for ((seasonNumber, seasonUrl) in seasonLinks) {

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
                        { it.season ?: 0 },
                        { it.episode ?: 0 }
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

    // -------------------------------------------------------------------------
    // ODNOK / OK.RU
    // -------------------------------------------------------------------------

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

            val decoded =
                String(
                    Base64
                        .getDecoder()
                        .decode(
                            urlDecoded
                        ),
                    Charsets.UTF_8
                )
                    .trim()

            decoded.takeIf {
                it.startsWith("http://") ||
                    it.startsWith("https://")
            }

        } catch (_: Exception) {
            null
        }
    }

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

    // -------------------------------------------------------------------------
    // PLAYER
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeBase =
            data.trimEnd('/')

        var foundLink = false

        // ---------------------------------------------------------------------
        // 1) ODNOK / OK.RU
        // ---------------------------------------------------------------------

        /*
         * Bu yol daha önce çalışıyordu:
         *
         * bölüm /3/
         * -> /player/haydi.php?v=<base64>
         * -> Base64 decode
         * -> https://ok.ru/video/<id>
         * -> https://ok.ru/videoembed/<id>
         * -> loadExtractor()
         */
        try {

            val odnokPage =
                "$episodeBase/3/"

            val odnokDocument =
                app.get(
                    odnokPage,
                    headers = headers,
                    referer = "$episodeBase/"
                ).document

            val odnokIframe =
                odnokDocument
                    .selectFirst(
                        "#video-area iframe[src], " +
                            "iframe[src*=\"/player/haydi.php\"]"
                    )
                    ?.attr("src")
                    ?.trim()

            if (!odnokIframe.isNullOrBlank()) {

                val iframeUrl =
                    fixUrlNull(
                        odnokIframe
                    )

                if (iframeUrl != null) {

                    val decoded =
                        decodeOdnokUrl(
                            iframeUrl
                        )

                    if (decoded != null) {

                        val okUrl =
                            normalizeOkUrl(
                                decoded
                            )

                        try {

                            val loaded =
                                loadExtractor(
                                    okUrl,
                                    odnokPage,
                                    subtitleCallback,
                                    callback
                                )

                            if (loaded) {
                                foundLink = true
                            }

                        } catch (_: Exception) {
                        }
                    }

                    /*
                     * Fallback:
                     * CloudStream wrapper'ı doğrudan tanıyorsa onu da dene.
                     */
                    try {

                        val loaded =
                            loadExtractor(
                                iframeUrl,
                                odnokPage,
                                subtitleCallback,
                                callback
                            )

                        if (loaded) {
                            foundLink = true
                        }

                    } catch (_: Exception) {
                    }
                }
            }

        } catch (_: Exception) {
        }

        // ---------------------------------------------------------------------
        // 2) DBX PRO + MOLY + AÇIK PLAYERLAR
        // ---------------------------------------------------------------------

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

        val playerPages =
            mutableListOf(
                "$episodeBase/",
                "$episodeBase/2/"
            )

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

                    val fixed =
                        fixUrlNull(
                            candidate
                        )

                    if (
                        fixed != null &&
                        !fixed.endsWith("/3/")
                    ) {
                        playerPages.add(
                            fixed
                        )
                    }
                }
            }

        val uniquePlayerPages =
            playerPages.distinct()

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

                    try {

                        val loaded =
                            loadExtractor(
                                iframeUrl,
                                playerPage,
                                subtitleCallback,
                                callback
                            )

                        if (loaded) {
                            foundLink = true
                        }

                    } catch (_: Exception) {
                    }
                }

            } catch (_: Exception) {
            }
        }

        return foundLink
    }

    // -------------------------------------------------------------------------
    // ARŞİV KARTI
    // -------------------------------------------------------------------------

    private fun Element.toSearchResult():
        SearchResponse? {

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
            link.text()
                .trim()
                .ifBlank {

                    selectFirst(
                        "img.main-cover"
                    )
                        ?.attr("alt")
                        ?.trim()
                        .orEmpty()
                }

        if (title.isBlank()) {
            return null
        }

        val poster =
            selectFirst(
                "img.main-cover"
            )
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

    // -------------------------------------------------------------------------
    // RESİM
    // -------------------------------------------------------------------------

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
                    ignoreCase = true
                )
            ) {
                continue
            }

            return fixUrlNull(value)
        }

        return null
    }

    // -------------------------------------------------------------------------
    // DİZİ ADI
    // -------------------------------------------------------------------------

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
                ?.removeSuffix(
                    " - DiziBOX"
                )
                ?.trim()

        if (!ogTitle.isNullOrBlank()) {
            return ogTitle
        }

        val selectors =
            listOf(
                "h1",
                ".tv-title",
                ".post-title",
                ".cat-title"
            )

        for (selector in selectors) {

            val title =
                document
                    .selectFirst(selector)
                    ?.text()
                    ?.trim()

            if (!title.isNullOrBlank()) {
                return title
            }
        }

        return null
    }

    // -------------------------------------------------------------------------
    // POSTER
    // -------------------------------------------------------------------------

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
            return fixUrlNull(
                ogImage
            )
        }

        val image =
            document.selectFirst(
                ".tv-cover img, " +
                    ".poster img, " +
                    ".main-cover"
            )
                ?: return null

        return getImageUrl(image)
    }

    // -------------------------------------------------------------------------
    // AÇIKLAMA
    // -------------------------------------------------------------------------

    private fun getSeriesPlot(
        document: Document
    ): String? {

        val selectors =
            listOf(
                ".tv-story",
                ".tv-overview",
                ".series-story",
                ".post-content",
                ".entry-content"
            )

        for (selector in selectors) {

            val plot =
                document
                    .selectFirst(selector)
                    ?.text()
                    ?.trim()

            if (!plot.isNullOrBlank()) {
                return plot
            }
        }

        return null
    }

    // -------------------------------------------------------------------------
    // SEZON NUMARASI
    // -------------------------------------------------------------------------

    private fun extractSeasonNumber(
        text: String?,
        url: String?
    ): Int? {

        val textMatch =
            Regex(
                """(\d+)\s*\.\s*Sezon""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    text.orEmpty()
                )

        textMatch
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        val urlMatch =
            Regex(
                """/(\d+)-sezon-""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    url.orEmpty()
                )

        return urlMatch
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    // -------------------------------------------------------------------------
    // BÖLÜMLER
    // -------------------------------------------------------------------------

    private fun collectEpisodesFromSeasonDocument(
        document: Document,
        defaultSeason: Int?,
        episodes: MutableList<Episode>
    ) {

        val episodeLinks =
            document.select(
                "#category-posts a.season-episode[href]"
            )

        for (episodeElement in episodeLinks) {

            val episodeUrl =
                fixUrlNull(
                    episodeElement.attr(
                        "href"
                    )
                )
                    ?: continue

            val match =
                Regex(
                    """-(\d+)-sezon-(\d+)-bolum(?:-|/|$)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(
                        episodeUrl
                    )

            val seasonNumber =
                match
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: defaultSeason

            val episodeNumber =
                match
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()

            val textEpisodeNumber =
                Regex(
                    """(\d+)\s*\.\s*Bölüm""",
                    RegexOption.IGNORE_CASE
                )
                    .find(
                        episodeElement.text()
                    )
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val finalEpisodeNumber =
                episodeNumber
                    ?: textEpisodeNumber

            val episodeName =
                when {

                    seasonNumber != null &&
                        finalEpisodeNumber != null -> {

                        "$seasonNumber. Sezon " +
                            "$finalEpisodeNumber. Bölüm"
                    }

                    finalEpisodeNumber != null -> {

                        "$finalEpisodeNumber. Bölüm"
                    }

                    else -> {

                        episodeElement
                            .text()
                            .trim()
                            .ifBlank {
                                "Bölüm"
                            }
                    }
                }

            episodes.add(
                newEpisode(
                    episodeUrl
                ) {
                    this.name =
                        episodeName

                    this.season =
                        seasonNumber

                    this.episode =
                        finalEpisodeNumber
                }
            )
        }
    }
}
