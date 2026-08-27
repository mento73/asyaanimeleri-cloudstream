package com.anihepsi.dizibox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
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

    private val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to
            "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/tum-bolumler/?tip=populer" to "Popüler Bölümler",
        "$mainUrl/tum-bolumler/" to "Son Bölümler",
        "$mainUrl/efsane-diziler/" to "Efsane Diziler",
        "$mainUrl/arsiv/?&imdb=7" to "IMDb 7+ Diziler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data,
            headers = headers,
            referer = "$mainUrl/"
        ).document

        val results = document
            .select(
                """
                a[href*="/diziler/"],
                a[href*="/dizi/"]
                """.trimIndent()
            )
            .mapNotNull { element ->
                element.toDiziResult()
            }
            .distinctBy { result ->
                result.url
            }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    private fun Element.toDiziResult(): SearchResponse? {

        val hrefRaw = attr("href").trim()

        if (hrefRaw.isBlank()) {
            return null
        }

        val href = fixUrlNull(hrefRaw)
            ?: return null

        if (
            !href.contains("/diziler/") &&
            !href.contains("/dizi/")
        ) {
            return null
        }

        val container =
            closest(
                "article, li, .article-series-small-grid, .grid-four, .archive-box"
            )
                ?: parent()
                ?: this

        val image =
            selectFirst("img")
                ?: container.selectFirst("img")

        val titleFromAttribute =
            attr("title")
                .removeSuffix(" izle")
                .trim()

        val titleFromImage =
            image
                ?.attr("alt")
                ?.removeSuffix(" izle")
                ?.trim()
                .orEmpty()

        val titleFromContainer =
            container
                .selectFirst(
                    ".tv-title, .post-title, .series-details, h2, h3, h4"
                )
                ?.text()
                ?.removeSuffix(" izle")
                ?.trim()
                .orEmpty()

        val titleFromText =
            text()
                .removeSuffix(" izle")
                .trim()

        val title =
            when {
                titleFromAttribute.isNotBlank() ->
                    titleFromAttribute

                titleFromImage.isNotBlank() ->
                    titleFromImage

                titleFromContainer.isNotBlank() ->
                    titleFromContainer

                titleFromText.isNotBlank() ->
                    titleFromText

                else ->
                    return null
            }

        val poster =
            image?.let {
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
                value.startsWith("data:image", true)
            ) {
                continue
            }

            return fixUrlNull(value)
        }

        return null
    }

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
            URLEncoder.encode(
                query.trim(),
                "UTF-8"
            )

        val document = app.get(
            "$mainUrl/?s=$encoded",
            headers = headers,
            referer = "$mainUrl/"
        ).document

        return document
            .select(
                """
                a[href*="/diziler/"],
                a[href*="/dizi/"]
                """.trimIndent()
            )
            .mapNotNull { element ->
                element.toDiziResult()
            }
            .distinctBy { result ->
                result.url
            }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            headers = headers,
            referer = "$mainUrl/"
        ).document

        val title =
            document
                .selectFirst(
                    """
                    h1,
                    .tv-title,
                    .post-title,
                    meta[property="og:title"]
                    """.trimIndent()
                )
                ?.let { element ->

                    if (element.tagName() == "meta") {
                        element.attr("content")
                    } else {
                        element.text()
                    }
                }
                ?.removeSuffix(" izle")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val poster =
            document
                .selectFirst(
                    """
                    meta[property="og:image"],
                    img[itemprop="image"],
                    #archive-box img,
                    .figure img
                    """.trimIndent()
                )
                ?.let { element ->

                    if (element.tagName() == "meta") {
                        fixUrlNull(
                            element.attr("content")
                        )
                    } else {
                        getImageUrl(element)
                    }
                }

        val plot =
            document
                .selectFirst(
                    """
                    meta[property="og:description"],
                    .description,
                    .tv-story,
                    .entry-content p
                    """.trimIndent()
                )
                ?.let { element ->

                    if (element.tagName() == "meta") {
                        element.attr("content")
                    } else {
                        element.text()
                    }
                }
                ?.trim()

        val episodes =
            mutableListOf<Episode>()

        collectEpisodesFromDocument(
            document,
            episodes
        )

        if (episodes.isEmpty()) {

            val seasonUrls =
                document
                    .select(
                        """
                        a[href*="-sezon-"],
                        a[href*="/sezon-"]
                        """.trimIndent()
                    )
                    .mapNotNull { element ->
                        fixUrlNull(
                            element.attr("href")
                        )
                    }
                    .filter { seasonUrl ->
                        !seasonUrl.contains("-bolum-izle")
                    }
                    .distinct()

            for (seasonUrl in seasonUrls) {

                try {

                    val seasonDocument =
                        app.get(
                            seasonUrl,
                            headers = headers,
                            referer = url
                        ).document

                    collectEpisodesFromDocument(
                        seasonDocument,
                        episodes
                    )

                } catch (_: Exception) {
                }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
                .distinctBy { episode ->
                    episode.data
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
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private fun collectEpisodesFromDocument(
        document: Document,
        episodes: MutableList<Episode>
    ) {

        document
            .select(
                """
                a[href*="-sezon-"][href*="-bolum-izle"]
                """.trimIndent()
            )
            .forEach { element ->

                val episodeUrl =
                    fixUrlNull(
                        element.attr("href")
                    )
                        ?: return@forEach

                val numbers =
                    Regex(
                        """-(\d+)-sezon-(\d+)-bolum-izle""",
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

                val episodeNumber =
                    numbers
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.toIntOrNull()

                val episodeName =
                    element
                        .attr("title")
                        .trim()
                        .ifBlank {
                            element
                                .text()
                                .trim()
                        }
                        .ifBlank {

                            if (
                                seasonNumber != null &&
                                episodeNumber != null
                            ) {
                                "$seasonNumber. Sezon $episodeNumber. Bölüm"
                            } else {
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
