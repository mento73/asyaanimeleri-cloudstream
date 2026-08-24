// Original Dizilla implementation lineage:
// @keyiflerolsun / @KekikAkademi
// Additional maintenance/reference: @nikyokki
//
// Adapted for the Anihepsi CloudStream repository
// while preserving original attribution.
//
// This port intentionally does not include protected-data
// decryption or Cloudflare challenge-bypass logic.

package com.anihepsi.dizilla

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.util.Calendar

class DizillaProvider : MainAPI() {

    override var mainUrl = "https://dizilla40.com"
    override var name = "Dizilla"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    override val mainPage = mainPageOf(
        "$mainUrl/tum-bolumler" to "Altyazılı Bölümler",
        "$mainUrl/arsiv" to "Yeni Eklenen Diziler",

        "$mainUrl/dizi-turu/aile" to "Aile",
        "$mainUrl/dizi-turu/aksiyon" to "Aksiyon",
        "$mainUrl/dizi-turu/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/dizi-turu/dram" to "Dram",
        "$mainUrl/dizi-turu/fantastik" to "Fantastik",
        "$mainUrl/dizi-turu/gerilim" to "Gerilim",
        "$mainUrl/dizi-turu/komedi" to "Komedi",
        "$mainUrl/dizi-turu/korku" to "Korku",
        "$mainUrl/dizi-turu/macera" to "Macera",
        "$mainUrl/dizi-turu/romantik" to "Romantik"
    )

    /*
     * ---------------------------------------------------------
     * POSTER HELPERS
     * ---------------------------------------------------------
     */

    private fun extractPosterUrl(
        element: Element
    ): String? {

        val img = if (
            element.tagName().equals(
                "img",
                ignoreCase = true
            )
        ) {
            element
        } else {
            element.selectFirst("img")
        } ?: return null

        return fixUrlNull(
            img.attr("src")
        )
            ?: fixUrlNull(
                img.attr("data-src")
            )
            ?: fixUrlNull(
                img.attr("data-lazy-src")
            )
            ?: fixUrlNull(
                img.attr("data-original")
            )
            ?: img
                .attr("data-srcset")
                .takeIf {
                    it.isNotBlank()
                }
                ?.split(" ")
                ?.firstOrNull()
                ?.let {
                    fixUrlNull(it)
                }
            ?: img
                .attr("srcset")
                .takeIf {
                    it.isNotBlank()
                }
                ?.split(" ")
                ?.firstOrNull()
                ?.let {
                    fixUrlNull(it)
                }
            ?: img
                .attr("data-nimg")
                .takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    "https://images.macellan.online/images/tv/brand/584/386/100/$it.jpg"
                }
    }

    private fun extractPosterUrlFromCategory(
        element: Element
    ): String? {

        val selectors = listOf(
            "img",
            "div img",
            "span img",
            "a img",
            "div.relative img",
            "div.overflow-hidden img"
        )

        for (selector in selectors) {

            val img = element
                .selectFirst(selector)
                ?: continue

            val poster =
                extractPosterUrl(img)

            if (poster != null) {
                return poster
            }
        }

        return extractPosterUrl(
            element
        )
    }

    private fun extractPosterUrlFromSonBolumler(
        element: Element
    ): String? {

        val selectors = listOf(
            "img",
            "div img",
            "span img",
            "a img",
            "div.col-span-3 img",
            "div.relative img"
        )

        for (selector in selectors) {

            val img = element
                .selectFirst(selector)
                ?: continue

            val poster =
                extractPosterUrl(img)

            if (poster != null) {
                return poster
            }
        }

        return extractPosterUrl(
            element
        )
    }

    private fun extractPosterUrlFromArsiv(
        element: Element
    ): String? {

        val selectors = listOf(
            "img",
            "div img",
            "span img",
            "a img",
            "div.w-full img",
            "div.relative img"
        )

        for (selector in selectors) {

            val img = element
                .selectFirst(selector)
                ?: continue

            val poster =
                extractPosterUrl(img)

            if (poster != null) {
                return poster
            }
        }

        return extractPosterUrl(
            element
        )
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

        val home = when {

            /*
             * Tür sayfaları
             */
            request.data.contains(
                "/dizi-turu/"
            ) -> {

                val document = app.get(
                    request.data
                ).document

                val items = document
                    .select(
                        "div.grid a[href*='/dizi/']"
                    )
                    .ifEmpty {

                        document.select(
                            "div.grid div.relative a[href*='/dizi/']"
                        )
                    }
                    .ifEmpty {

                        document
                            .select(
                                "a[href*='/dizi/']"
                            )
                            .filter {
                                it.selectFirst(
                                    "img"
                                ) != null
                            }
                    }

                items.mapNotNull { element ->

                    val title =
                        element
                            .selectFirst("h2")
                            ?.text()
                            ?.trim()
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: element
                                .attr("title")
                                .trim()
                                .takeIf {
                                    it.isNotBlank()
                                }
                            ?: element
                                .selectFirst("img")
                                ?.attr("alt")
                                ?.trim()
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                            ?: return@mapNotNull null

                    val href = fixUrlNull(
                        element.attr("href")
                    ) ?: return@mapNotNull null

                    val posterUrl =
                        extractPosterUrlFromCategory(
                            element
                        )

                    newTvSeriesSearchResponse(
                        title,
                        href,
                        TvType.TvSeries
                    ) {
                        this.posterUrl =
                            posterUrl
                    }
                }
                    .distinctBy {
                        it.url
                    }
            }

            /*
             * Yeni eklenen diziler / arşiv
             */
            request.data.contains(
                "/arsiv"
            ) -> {

                val currentYear =
                    Calendar
                        .getInstance()
                        .get(
                            Calendar.YEAR
                        )

                val query =
                    "?page=$page" +
                        "&tab=1" +
                        "&sort=date_desc" +
                        "&filterType=2" +
                        "&imdbMin=5" +
                        "&imdbMax=10" +
                        "&yearMin=1900" +
                        "&yearMax=$currentYear"

                val document = app.get(
                    "${request.data}$query"
                ).document

                document
                    .select("a.w-full")
                    .mapNotNull {
                        it.yeniEklenenler()
                    }
                    .distinctBy {
                        it.url
                    }
            }

            /*
             * Son/altyazılı bölümler
             */
            request.data.contains(
                "/tum-bolumler"
            ) -> {

                val document = app.get(
                    request.data
                ).document

                document
                    .select(
                        "div.col-span-3 a"
                    )
                    .mapNotNull { element ->

                        val name = element
                            .selectFirst("h2")
                            ?.text()
                            ?.trim()
                            ?: return@mapNotNull null

                        val episodeName = element
                            .selectFirst(
                                "div.opacity-80"
                            )
                            ?.text()
                            ?.replace(
                                ". Sezon ",
                                "x"
                            )
                            ?.replace(
                                ". Bölüm",
                                ""
                            )
                            ?.trim()
                            ?: return@mapNotNull null

                        val title =
                            "$name - $episodeName"

                        val href =
                            fixUrlNull(
                                element.attr(
                                    "href"
                                )
                            )
                                ?: return@mapNotNull null

                        val posterUrl =
                            extractPosterUrlFromSonBolumler(
                                element
                            )

                        newTvSeriesSearchResponse(
                            title,
                            href,
                            TvType.TvSeries
                        ) {
                            this.posterUrl =
                                posterUrl
                        }
                    }
                    .distinctBy {
                        it.url
                    }
            }

            /*
             * Güvenli fallback.
             */
            else -> {

                val document = app.get(
                    request.data
                ).document

                document
                    .select(
                        "a[href*='/dizi/']"
                    )
                    .mapNotNull { element ->

                        val title =
                            element
                                .selectFirst("h2")
                                ?.text()
                                ?.trim()
                                ?: element
                                    .attr("title")
                                    .trim()
                                    .takeIf {
                                        it.isNotBlank()
                                    }
                                ?: return@mapNotNull null

                        val href =
                            fixUrlNull(
                                element.attr(
                                    "href"
                                )
                            )
                                ?: return@mapNotNull null

                        newTvSeriesSearchResponse(
                            title,
                            href,
                            TvType.TvSeries
                        ) {
                            posterUrl =
                                extractPosterUrl(
                                    element
                                )
                        }
                    }
                    .distinctBy {
                        it.url
                    }
            }
        }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    /*
     * ---------------------------------------------------------
     * MAIN PAGE CARD HELPERS
     * ---------------------------------------------------------
     */

    private fun Element.yeniEklenenler():
        SearchResponse? {

        val title = selectFirst("h2")
            ?.text()
            ?.trim()
            ?: return null

        val href = fixUrlNull(
            attr("href")
        ) ?: return null

        val posterUrl =
            extractPosterUrlFromArsiv(
                this
            )

        val scoreText =
            selectFirst(
                "div.absolute.bottom-0 span"
            )
                ?.text()
                ?.trim()

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {

            this.posterUrl =
                posterUrl

            this.score =
                Score.from10(
                    scoreText
                )
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

        val searchReq = app.post(
            "$mainUrl/api/bg/searchcontent?searchterm=$query",
            headers = mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",

                "Accept" to
                    "application/json, text/plain, */*",

                "Accept-Language" to
                    "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",

                "X-Requested-With" to
                    "XMLHttpRequest",

                "Referer" to
                    "$mainUrl/"
            ),
            referer = "$mainUrl/"
        )

        val objectMapper =
            ObjectMapper()
                .registerModule(
                    KotlinModule
                        .Builder()
                        .build()
                )

        objectMapper.configure(
            DeserializationFeature
                .FAIL_ON_UNKNOWN_PROPERTIES,
            false
        )

        return try {

            val searchResult:
                SearchResult =
                objectMapper.readValue(
                    searchReq.text
                )

            val encodedResponse =
                searchResult.response
                    ?: return emptyList()

            val decodedSearch =
                base64Decode(
                    encodedResponse
                )

            val contentJson:
                SearchData =
                objectMapper.readValue(
                    decodedSearch
                )

            if (
                contentJson.state != true
            ) {
                return emptyList()
            }

            contentJson
                .result
                .orEmpty()
                .mapNotNull {
                    it.toSearchResponse()
                }
                .distinctBy {
                    it.url
                }

        } catch (e: Exception) {

            Log.e(
                "Dizilla",
                "Search error: ${e.message}"
            )

            emptyList()
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(query)
    }

    private fun SearchItem.toSearchResponse():
        SearchResponse? {

        val title = this.title
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return null

        val slug = this.slug
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return null

        val url = when {

            slug.startsWith(
                "http://"
            ) ||
                slug.startsWith(
                    "https://"
                ) ->
                slug

            slug.startsWith("/") ->
                fixUrl(slug)

            else ->
                fixUrl("/$slug")
        }

        val poster = this.poster
            ?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    it != "null"
            }
            ?.let {
                fixUrl(it)
            }

        return newTvSeriesSearchResponse(
            title,
            url,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    /*
     * ---------------------------------------------------------
     * DETAIL + EPISODES
     * ---------------------------------------------------------
     */

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url
        ).document

        val title = document
            .selectFirst(
                "div.poster h2, h1.text-2xl"
            )
            ?.text()
            ?.trim()
            ?: return null

        val posterElement =
            document.selectFirst(
                "div.w-full.page-top.relative img, div.poster img"
            )
                ?: document.selectFirst(
                    "img[src*='images.macellan.online']"
                )
                ?: document.selectFirst(
                    "img"
                )

        val poster =
            posterElement
                ?.let {
                    extractPosterUrl(it)
                }

        /*
         * Yapım yılı
         */
        val yearElement = document
            .select(
                "div.w-fit.min-w-fit, div.flex.items-center"
            )
            .find {
                it.text()
                    .contains(
                        "Yapım Yılı",
                        ignoreCase = true
                    )
            }

        val year =
            yearElement
                ?.selectFirst(
                    "span.text-sm.opacity-60, span.opacity-60"
                )
                ?.text()
                ?.split(" ")
                ?.lastOrNull()
                ?.toIntOrNull()

        /*
         * Açıklama
         */
        val description = document
            .selectFirst(
                "div.mt-2.text-sm, div.text-sm.opacity-80"
            )
            ?.text()
            ?.trim()

        /*
         * Türler
         */
        val tags = document
            .selectFirst(
                "div.poster h3, div.flex.items-center.flex-wrap.gap-1"
            )
            ?.text()
            ?.split(",")
            ?.map {
                it.trim()
            }
            ?.filter {
                it.isNotBlank()
            }

        /*
         * Puan
         */
        val ratingString = document
            .selectFirst(
                "div.flex.items-center span.text-white.text-sm, span.text-yellow-400"
            )
            ?.text()
            ?.trim()

        /*
         * Oyuncular
         */
        val actors = document
            .select(
                "div.global-box h5, div.cast-item span"
            )
            .map {
                Actor(
                    it.text()
                )
            }

        /*
         * Sezon + bölüm listesi.
         */
        val episodes =
            mutableListOf<Episode>()

        val seasonLinks = document
            .select(
                "div.flex.items-center.flex-wrap.gap-2.mb-4 a, div.seasons a"
            )

        for (
            seasonElement in seasonLinks
        ) {

            val seasonHref =
                fixUrlNull(
                    seasonElement.attr(
                        "href"
                    )
                )
                    ?: continue

            val seasonDocument =
                try {

                    app.get(
                        seasonHref
                    ).document

                } catch (_: Exception) {

                    continue
                }

            val seasonNumber =
                seasonHref
                    .split("-")
                    .lastOrNull {
                        it.toIntOrNull() != null
                    }
                    ?.toIntOrNull()
                    ?: seasonElement
                        .text()
                        .replace(
                            "Sezon",
                            "",
                            ignoreCase = true
                        )
                        .trim()
                        .toIntOrNull()

            val episodeElements =
                seasonDocument.select(
                    "div.episodes div.cursor-pointer, " +
                        "div.episodes-box div.episode-item"
                )

            for (
                episodeElement in episodeElements
            ) {

                val episodeLink =
                    episodeElement
                        .select("a")
                        .lastOrNull()
                        ?: continue

                val episodeName =
                    episodeLink
                        .text()
                        .trim()

                val episodeHref =
                    fixUrlNull(
                        episodeLink.attr(
                            "href"
                        )
                    )
                        ?: continue

                val episodeNumberText =
                    episodeElement
                        .selectFirst(
                            "a, span.episode-number"
                        )
                        ?.text()
                        ?.trim()
                        .orEmpty()

                val episodeNumber =
                    Regex("""\d+""")
                        .find(
                            episodeNumberText
                        )
                        ?.value
                        ?.toIntOrNull()

                episodes.add(
                    newEpisode(
                        episodeHref
                    ) {

                        name = if (
                            episodeName.isNotBlank()
                        ) {
                            episodeName
                        } else if (
                            episodeNumber != null
                        ) {
                            "Bölüm $episodeNumber"
                        } else {
                            "Bölüm"
                        }

                        season =
                            seasonNumber

                        episode =
                            episodeNumber
                    }
                )
            }
        }

        val cleanEpisodes =
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
            cleanEpisodes
        ) {

            posterUrl =
                poster

            this.year =
                year

            plot =
                description

            this.tags =
                tags

            score =
                Score.from10(
                    ratingString
                )

            addActors(
                actors
            )
        }
    }

    /*
     * ---------------------------------------------------------
     * PLAYER
     * ---------------------------------------------------------
     *
     * IMPORTANT:
     *
     * The original implementation also contains a path that
     * decrypts protected secureData using a fixed AES key.
     *
     * That path is intentionally NOT included here.
     *
     * We use only normally exposed iframe / data-src player URLs
     * and pass them to CloudStream's registered extractor system.
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            referer = "$mainUrl/"
        ).document

        val playerUrls =
            mutableListOf<String>()

        /*
         * Normal iframe src.
         */
        document
            .select(
                "iframe[src]"
            )
            .mapNotNull { iframe ->

                normalizePlayerUrl(
                    iframe.attr(
                        "src"
                    )
                )
            }
            .forEach {
                playerUrls.add(it)
            }

        /*
         * Lazy iframe / data-src fallback.
         */
        document
            .select(
                "iframe[data-src], [data-src]"
            )
            .mapNotNull { element ->

                val raw =
                    element.attr(
                        "data-src"
                    )

                /*
                 * Only treat it as a player candidate
                 * when it actually resembles a URL.
                 */
                if (
                    raw.isBlank() ||
                    !(
                        raw.startsWith("/") ||
                            raw.startsWith("//") ||
                            raw.startsWith("http")
                        )
                ) {
                    null
                } else {

                    normalizePlayerUrl(
                        raw
                    )
                }
            }
            .forEach {
                playerUrls.add(it)
            }

        /*
         * Some sites expose iframe HTML inside ordinary elements.
         * We only inspect plainly visible iframe markup;
         * no protected payload decryption is attempted.
         */
        document
            .select(
                "[data-embed], [data-iframe]"
            )
            .forEach { element ->

                val candidates =
                    listOf(
                        element.attr(
                            "data-embed"
                        ),
                        element.attr(
                            "data-iframe"
                        )
                    )

                candidates
                    .mapNotNull {
                        normalizePlayerUrl(
                            it
                        )
                    }
                    .forEach {
                        playerUrls.add(it)
                    }
            }

        var found = false

        playerUrls
            .distinct()
            .forEach { playerUrl ->

                try {

                    val success =
                        loadExtractor(
                            url = playerUrl,
                            referer = "$mainUrl/",
                            subtitleCallback =
                                subtitleCallback,
                            callback =
                                callback
                        )

                    if (success) {
                        found = true
                    }

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        "Dizilla",
                        "Extractor failed for $playerUrl: ${e.message}"
                    )
                }
            }

        return found
    }

    private fun normalizePlayerUrl(
        rawUrl: String?
    ): String? {

        val cleaned =
            rawUrl
                ?.trim()
                ?.replace(
                    "\\/",
                    "/"
                )
                ?.replace(
                    "&amp;",
                    "&"
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        return try {

            when {

                cleaned.startsWith(
                    "//"
                ) ->
                    "https:$cleaned"

                cleaned.startsWith(
                    "http://"
                ) ||
                    cleaned.startsWith(
                        "https://"
                    ) ->
                    cleaned

                cleaned.startsWith(
                    "/"
                ) ->
                    fixUrl(
                        cleaned
                    )

                else ->
                    null
            }

        } catch (
            _: Exception
        ) {

            null
        }
    }
}
