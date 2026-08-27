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

        val pageUrl =
            if (page <= 1) {
                request.data
            } else {
                if (request.data.contains("?")) {
                    "${request.data}&page=$page"
                } else {
                    "${request.data}?page=$page"
                }
            }

        val document = app.get(
            pageUrl,
            headers = headers,
            referer = "$mainUrl/"
        ).document

        /*
         * Alfabetik açılır menü sitenin hemen her sayfasında bulunduğu için
         * doğrudan tüm document içindeki /diziler/ linklerini toplamıyoruz.
         *
         * Öncelikle sayfanın gerçek içerik alanlarındaki kartları arıyoruz.
         */
        val contentLinks = document.select(
            """
            #category-posts article a[href],
            #best-series article a[href],
            #recommended-series article a[href],
            article.grid-box a[href],
            article.article-series-small-grid a[href],
            article.article-episode-card a[href]
            """.trimIndent()
        )

        val results = contentLinks
            .mapNotNull { element ->
                element.toHomeResult()
            }
            .distinctBy { result ->
                result.url
            }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    private fun Element.toHomeResult(): SearchResponse? {

        val rawHref = attr("href").trim()

        if (rawHref.isBlank()) {
            return null
        }

        val href = fixUrlNull(rawHref)
            ?: return null

        val container =
            closest(
                "article, .grid-box, .article-series-small-grid, .article-episode-card"
            )
                ?: parent()
                ?: this

        val image =
            container.selectFirst("img")
                ?: selectFirst("img")

        val titleFromElement =
            attr("title")
                .removeSuffix(" izle")
                .trim()

        val titleFromContainer =
            container.selectFirst(
                ".post-title, .tv-title, .series-title, h2, h3, h4"
            )
                ?.text()
                ?.removeSuffix(" izle")
                ?.trim()
                .orEmpty()

        val titleFromImage =
            image
                ?.attr("alt")
                ?.removeSuffix(" izle")
                ?.trim()
                .orEmpty()

        val titleFromText =
            text()
                .removeSuffix(" izle")
                .trim()

        val title =
            when {
                titleFromElement.isNotBlank() ->
                    titleFromElement

                titleFromContainer.isNotBlank() ->
                    titleFromContainer

                titleFromImage.isNotBlank() ->
                    titleFromImage

                titleFromText.isNotBlank() ->
                    titleFromText

                else ->
                    return null
            }

        val poster =
            image?.let {
                getImageUrl(it)
            }
                ?: extractBackgroundImage(container)

        /*
         * Bazı ana sayfa kartları doğrudan diziye,
         * bazıları ise son yayınlanan bölüme gidebilir.
         *
         * Dizi linkiyse normal seri sonucu oluşturuyoruz.
         * Bölüm linkiyse yine CloudStream içinde açılabilmesi için
         * TvSeries sonucu olarak bırakıyoruz.
         */
        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    private fun extractBackgroundImage(
        element: Element
    ): String? {

        val style =
            element
                .selectFirst("[style*=background-image]")
                ?.attr("style")
                ?: element.attr("style")

        if (style.isBlank()) {
            return null
        }

        val imageUrl =
            Regex(
                """background-image\s*:\s*url\(['"]?([^'")]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(style)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?: return null

        return fixUrlNull(imageUrl)
    }

    private fun Element.toSeriesSearchResult(): SearchResponse? {

        val hrefRaw = attr("href").trim()

        if (hrefRaw.isBlank()) {
            return null
        }

        val href = fixUrlNull(hrefRaw)
            ?: return null

        if (!href.contains("/diziler/")) {
            return null
        }

        /*
         * Alfabetik site menüsündeki linkleri arama sonucuna
         * karıştırmamak için bu alanı dışarıda bırakıyoruz.
         */
        if (parents().any {
                it.hasClass("alphabetical-category-wrapper") ||
                it.hasClass("alphabetical-category-list")
            }
        ) {
            return null
        }

        val container =
            closest(
                "article, li, .grid-box, .article-series-small-grid, .archive-box"
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

        /*
         * Önce gerçek arama/içerik container'larını deniyoruz.
         * Alfabetik menüyü özellikle seçmiyoruz.
         */
        val directResults =
            document.select(
                """
                #content article a[href*="/diziler/"],
                #category-posts article a[href*="/diziler/"],
                main article a[href*="/diziler/"],
                .search-results article a[href*="/diziler/"],
                .archive-box a[href*="/diziler/"]
                """.trimIndent()
            )
                .mapNotNull { element ->
                    element.toSeriesSearchResult()
                }
                .distinctBy {
                    it.url
                }

        if (directResults.isNotEmpty()) {
            return directResults
        }

        /*
         * Site arama sonucunda farklı bir container kullanıyorsa
         * ikinci aşamada tüm /diziler/ linklerini kontrol ediyoruz,
         * ancak alfabetik kategori menüsünü yine dışarıda bırakıyoruz.
         */
        return document
            .select("a[href*=\"/diziler/\"]")
            .mapNotNull { element ->
                element.toSeriesSearchResult()
            }
            .filter { result ->
                result.name.contains(
                    query.trim(),
                    ignoreCase = true
                )
            }
            .distinctBy {
                it.url
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
            getSeriesTitle(document)
                ?: return null

        val poster =
            getSeriesPoster(document)

        val plot =
            getSeriesPlot(document)

        val episodes =
            mutableListOf<Episode>()

        /*
         * GERÇEK DIZIBOX SEZON YAPISI
         *
         * Ana dizi sayfasında:
         *
         * #seasons-list
         *   a -> 1. Sezon
         *   a -> 2. Sezon
         *   a -> 3. Sezon ...
         *
         * Her sezon sayfasında:
         *
         * #category-posts
         *   article
         *     a.season-episode
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
             * Bazı ana dizi sayfaları ilk sezonun bölümlerini
             * zaten kendi HTML'sinde gösteriyor.
             *
             * Önce mevcut document içindeki category-posts'u okuyalım.
             */
            collectEpisodesFromSeasonDocument(
                document = document,
                defaultSeason = seasonLinks.firstOrNull()?.first,
                episodes = episodes
            )

            /*
             * Sonra her sezonun kendi sayfasını açıp
             * o sezona ait bölümleri topluyoruz.
             */
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

        } else {

            /*
             * Eğer açılan URL zaten doğrudan bir sezon sayfasıysa,
             * seasons-list yine bulunabilir ama bulunmadığı eski
             * sayfalara karşı yedek olarak mevcut document'i okuyoruz.
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

        val normalTitle =
            document
                .selectFirst(
                    "h1, .tv-title, .post-title, .cat-title"
                )
                ?.text()
                ?.trim()

        if (!normalTitle.isNullOrBlank()) {
            return normalTitle
                .removeSuffix(" izle")
                .trim()
        }

        return null
    }

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
            description.tagName()
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

    private fun collectEpisodesFromSeasonDocument(
        document: Document,
        defaultSeason: Int?,
        episodes: MutableList<Episode>
    ) {

        /*
         * Sitenin gerçek bölüm linki:
         *
         * #category-posts
         * a.season-episode[href]
         */
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

            /*
             * Normal:
             * person-of-interest-1-sezon-6-bolum-izle
             *
             * Sezon finali gibi:
             * person-of-interest-1-sezon-23-bolum-sezon-finali-izle
             *
             * Bu nedenle regex'i "-bolum-izle" ile bitirmiyoruz.
             */
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

        /*
         * ODNOK / OK.RU
         *
         * Açık player seçeneği:
         *
         * bölüm URL
         * -> /3/
         * -> player/haydi.php?v=BASE64
         * -> normal Base64 URL decode
         * -> ok.ru/video/ID
         * -> ok.ru/videoembed/ID
         * -> CloudStream standard extractor
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
         * DBX PRO
         *
         * Burada sadece sayfanın açık iframe URL'sini
         * CloudStream'in standart extractor sistemine veriyoruz.
         * Herhangi bir token çözme/decryption/bypass yapılmıyor.
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
