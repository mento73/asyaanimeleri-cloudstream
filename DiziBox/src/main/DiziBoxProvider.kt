package com.anihepsi.dizibox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64

/*
 * DiziBox provider structure adapted for Anihepsi.
 *
 * Original Turkish CloudStream ecosystem references:
 * keyiflerolsun / Kekik-cloudstream
 * nikyokki / nik-cloudstream
 *
 * This implementation only uses public HTML, openly exposed iframe URLs,
 * normal Base64 URL decoding, and CloudStream's standard extractor flow.
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

    private val browserHeaders = mapOf(
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

        val url =
            if (page <= 1) {
                request.data
            } else {
                val separator =
                    if (request.data.contains("?")) "&" else "?"

                request.data + separator + "page=$page"
            }

        val document =
            app.get(
                url,
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

        /*
         * DiziBox sayfalarında dizi sayfasına giden bağlantılar /diziler/ altında.
         * Aynı dizi bir sayfada birden fazla kez görünebildiği için URL ile tekilleştiriyoruz.
         */
        val results =
            document
                .select("""a[href*="/diziler/"]""")
                .mapNotNull {
                    it.toSeriesSearchResult()
                }
                .distinctBy {
                    it.url
                }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    private fun Element.toSeriesSearchResult():
        SearchResponse? {

        val href =
            fixUrlNull(
                attr("href")
            )
                ?: return null

        if (!href.contains("/diziler/")) {
            return null
        }

        val container =
            closest(
                "article, li, .article-series-small-grid, .grid-four"
            )
                ?: parent()

        val image =
            selectFirst("img")
                ?: container
                    ?.selectFirst("img")

        val title =
            attr("title")
                .removeSuffix(" izle")
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
                ?: container
                    ?.selectFirst(
                        ".tv-title, .post-title, .series-details, h2, h3, h4"
                    )
                    ?.text()
                    ?.removeSuffix(" izle")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: image
                    ?.attr("alt")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: return null

        val poster =
            image
                ?.let {
                    extractImage(it)
                }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    private fun extractImage(
        image: Element
    ): String? {

        val candidates =
            listOf(
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("src")
            )

        for (candidate in candidates) {

            val raw =
                candidate.trim()

            if (
                raw.isBlank() ||
                raw.startsWith("data:image", true)
            ) {
                continue
            }

            return fixUrlNull(raw)
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

        /*
         * Sitenin kendi arama formu GET /?s=... kullanıyor.
         */
        val document =
            app.get(
                "$mainUrl/?s=${query.trim()}",
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

        return document
            .select("""a[href*="/diziler/"]""")
            .mapNotNull {
                it.toSeriesSearchResult()
            }
            .distinctBy {
                it.url
            }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(
                url,
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

        val title =
            document
                .selectFirst(
                    """
                    h1,
                    .tv-title,
                    .post-title,
                    .archive-title,
                    meta[property=og:title]
                    """.trimIndent()
                )
                ?.let {
                    if (it.tagName() == "meta") {
                        it.attr("content")
                    } else {
                        it.text()
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
                    meta[property=og:image],
                    #archive-box img,
                    .figure img,
                    .poster img,
                    img[itemprop=image]
                    """.trimIndent()
                )
                ?.let {
                    if (it.tagName() == "meta") {
                        fixUrlNull(
                            it.attr("content")
                        )
                    } else {
                        extractImage(it)
                    }
                }

        val plot =
            document
                .selectFirst(
                    """
                    meta[property=og:description],
                    .description,
                    .tv-story,
                    .entry-content p
                    """.trimIndent()
                )
                ?.let {
                    if (it.tagName() == "meta") {
                        it.attr("content")
                    } else {
                        it.text()
                    }
                }
                ?.trim()

        val episodes =
            mutableListOf<Episode>()

        /*
         * Dizi sayfası / sezon alanı / ilgili bölümler gibi tüm açık bölüm linklerini topluyoruz.
         * Örnek:
         * /the-shards-1-sezon-6-bolum-izle/
         */
        document
            .select(
                """a[href*="-sezon-"][href*="-bolum-izle"]"""
            )
            .forEach { element ->

                val episodeUrl =
                    fixUrlNull(
                        element.attr("href")
                    )
                        ?: return@forEach

                val match =
                    Regex(
                        """-(\d+)-sezon-(\d+)-bolum-izle/?$""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            episodeUrl
                        )

                val season =
                    match
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                val episode =
                    match
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.toIntOrNull()

                val name =
                    element
                        .attr("title")
                        .trim()
                        .ifBlank {
                            element.text().trim()
                        }
                        .ifBlank {
                            if (
                                season != null &&
                                episode != null
                            ) {
                                "$season. Sezon $episode. Bölüm"
                            } else {
                                "Bölüm"
                            }
                        }

                episodes.add(
                    newEpisode(
                        episodeUrl
                    ) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                    }
                )
            }

        /*
         * Bazı dizi sayfaları sezon linkleri üzerinden bölüm listesine gidiyor.
         * O nedenle doğrudan bölüm bulunamazsa sezon sayfalarını da okuyup bölüm topluyoruz.
         */
        if (episodes.isEmpty()) {

            val seasonPages =
                document
                    .select(
                        """a[href*="/sezon-"], a[href*="-sezon-"]"""
                    )
                    .mapNotNull {
                        fixUrlNull(
                            it.attr("href")
                        )
                    }
                    .filter {
                        it.contains("/dizi") ||
                            it.contains("/diziler/")
                    }
                    .distinct()

            for (seasonUrl in seasonPages) {

                safeApiCall {

                    val seasonDocument =
                        app.get(
                            seasonUrl,
                            headers = browserHeaders,
                            referer = url
                        ).document

                    seasonDocument
                        .select(
                            """a[href*="-sezon-"][href*="-bolum-izle"]"""
                        )
                        .forEach { element ->

                            val episodeUrl =
                                fixUrlNull(
                                    element.attr("href")
                                )
                                    ?: return@forEach

                            val match =
                                Regex(
                                    """-(\d+)-sezon-(\d+)-bolum-izle/?$""",
                                    RegexOption.IGNORE_CASE
                                )
                                    .find(
                                        episodeUrl
                                    )

                            val season =
                                match
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()

                            val episode =
                                match
                                    ?.groupValues
                                    ?.getOrNull(2)
                                    ?.toIntOrNull()

                            episodes.add(
                                newEpisode(
                                    episodeUrl
                                ) {
                                    this.name =
                                        if (
                                            season != null &&
                                            episode != null
                                        ) {
                                            "$season. Sezon $episode. Bölüm"
                                        } else {
                                            element.text()
                                                .trim()
                                                .ifBlank {
                                                    "Bölüm"
                                                }
                                        }

                                    this.season =
                                        season

                                    this.episode =
                                        episode
                                }
                            )
                        }
                }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
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
        ) {
            posterUrl = poster
            this.plot = plot
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

            decoded
                .takeIf {
                    it.startsWith(
                        "http://"
                    ) ||
                        it.startsWith(
                            "https://"
                        )
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

        /*
         * Öncelik Odnok (/3/) çünkü bölüm HTML'sinde açık ve taşınabilir bir akış:
         *
         * episode/3/
         *   -> iframe /player/haydi.php?v=<base64>
         *   -> decode
         *   -> https://ok.ru/video/<id>
         *   -> standart OK embed / loadExtractor
         */
        val baseEpisode =
            data.trimEnd('/')

        val odnokPage =
            "$baseEpisode/3/"

        safeApiCall {

            val odnokDocument =
                app.get(
                    odnokPage,
                    headers = browserHeaders,
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

                    val decoded =
                        decodeOdnokUrl(
                            iframeUrl
                        )

                    if (decoded != null) {

                        val okUrl =
                            normalizeOkUrl(
                                decoded
                            )

                        loadExtractor(
                            okUrl,
                            odnokPage,
                            subtitleCallback,
                            callback
                        )
                    }

                    /*
                     * Eğer CloudStream haydi.php için doğrudan extractor tanıyorsa
                     * bunu da fallback olarak deniyoruz.
                     */
                    loadExtractor(
                        iframeUrl,
                        odnokPage,
                        subtitleCallback,
                        callback
                    )
                }
            }
        }

        /*
         * DBX Pro'yu ikinci yol olarak dene.
         * Burada herhangi bir token çözümü yapmıyoruz; sayfadaki açık iframe'i
         * standart extractor sistemine veriyoruz.
         */
        safeApiCall {

            val document =
                app.get(
                    data,
                    headers = browserHeaders,
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
        }

        return true
    }
}
