package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Original provider lineage:
 * Kekik / Kraptor ecosystem.
 *
 * Modernized for the Anihepsi CloudStream repository.
 *
 * Player handling uses CloudStream's standard extractor flow.
 * No DRM, anti-debug or access-control bypass is implemented.
 */

class AsyaAnimeleriProvider : MainAPI() {

    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    /*
     * Ana sayfa sırası:
     *
     * Son Eklenenler
     * Popüler Seriler
     * Tüm Animeler
     * Türler...
     */
    override val mainPage = mainPageOf(
        "$mainUrl/#latest" to "Son Eklenenler",
        "$mainUrl/#popular" to "Popüler Seriler",

        "$mainUrl/series/" to "Tüm Animeler",

        "$mainUrl/genres/aksiyon/page/sayfa/" to "Aksiyon",
        "$mainUrl/genres/fantastik/page/sayfa/" to "Fantastik",
        "$mainUrl/genres/macera/page/sayfa/" to "Macera",
        "$mainUrl/genres/komedi/page/sayfa/" to "Komedi",
        "$mainUrl/genres/romantik/page/sayfa/" to "Romantik",
        "$mainUrl/genres/isekai/page/sayfa/" to "İsekai",
        "$mainUrl/genres/dovus-sanatlari/page/sayfa/" to "Dövüş Sanatları",
        "$mainUrl/genres/bilim-kurgu/page/sayfa/" to "Bilim Kurgu",
        "$mainUrl/genres/dogaustu/page/sayfa/" to "Doğaüstü",
        "$mainUrl/genres/dram/page/sayfa/" to "Dram"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        /*
         * Son Eklenenler ve Popüler Seriler ana sayfadaki
         * sabit bloklar.
         *
         * Bunların ikinci/üçüncü sayfası yok.
         * CloudStream aynı bloğu tekrar istemesin diye
         * hasNext = false döndürüyoruz.
         */
        if (
            request.name == "Son Eklenenler" ||
            request.name == "Popüler Seriler"
        ) {

            val document =
                app.get(mainUrl).document

            val animeList =
                when (request.name) {

                    "Son Eklenenler" ->
                        parseHomeSection(
                            document = document,
                            titleTexts = listOf(
                                "Son Eklenenler"
                            )
                        )

                    "Popüler Seriler" ->
                        parseHomeSection(
                            document = document,
                            titleTexts = listOf(
                                "Popüler Serler",
                                "Popüler Seriler"
                            )
                        )

                    else ->
                        emptyList()
                }

            return newHomePageResponse(
                request.name,
                animeList,
                hasNext = false
            )
        }

        /*
         * Normal seri/tür sayfaları.
         *
         * Tüm Animeler:
         *
         * page 1 -> /series/
         * page 2 -> /series/page/2/
         * page 3 -> /series/page/3/
         *
         * Türler:
         *
         * page 1 -> /genres/aksiyon/
         * page 2 -> /genres/aksiyon/page/2/
         */
        val url =
            when {

                request.name == "Tüm Animeler" -> {

                    if (page <= 1) {
                        "$mainUrl/series/"
                    } else {
                        "$mainUrl/series/page/$page/"
                    }
                }

                request.data.contains("sayfa") &&
                    page <= 1 -> {

                    request.data
                        .replace(
                            "/page/sayfa/",
                            "/"
                        )
                }

                request.data.contains("sayfa") -> {

                    request.data.replace(
                        "sayfa",
                        page.toString()
                    )
                }

                else ->
                    request.data
            }

        val document =
            app.get(url).document

        val animeList =
            document
                .select("article.bs")
                .mapNotNull {
                    it.toAnimeCard()
                }
                .distinctBy {
                    it.url
                }

        /*
         * Gerçek sonraki sayfa bağlantısı varsa devam et.
         * Yoksa son sayfada dur.
         */
        val nextPageNumber =
            page + 1

        val hasNext =
            animeList.isNotEmpty() &&
                (
                    document.selectFirst(
                        "a[href*=\"/page/$nextPageNumber/\"]"
                    ) != null ||
                        document.selectFirst(
                            ".pagination a.next, " +
                                ".pagination .next a, " +
                                ".hpage a.r, " +
                                "a.next.page-numbers"
                        ) != null
                    )

        return newHomePageResponse(
            request.name,
            animeList,
            hasNext = hasNext
        )
    }

    /*
     * Ana sayfadaki belirli bir başlığın ait olduğu
     * article.bs bloğunu bulur.
     *
     * Site tasarımı değişirse doğrudan nth-child gibi
     * kırılgan selectorlara bağımlı kalmamak için
     * başlık metninden başlayıp yukarı doğru gider.
     */
    private fun parseHomeSection(
        document: Document,
        titleTexts: List<String>
    ): List<SearchResponse> {

        val heading = document
            .select(
                "h1, h2, h3, h4, " +
                    ".releases h1, " +
                    ".releases h2, " +
                    ".releases h3, " +
                    ".releases h4"
            )
            .firstOrNull { element ->

                val text = element
                    .text()
                    .trim()

                titleTexts.any {
                    text.equals(
                        it,
                        ignoreCase = true
                    ) ||
                        text.contains(
                            it,
                            ignoreCase = true
                        )
                }
            }
            ?: return emptyList()

        /*
         * Heading genellikle .releases gibi küçük bir div içinde.
         * Article kartları onun üst parent'ındaki listupd içinde.
         *
         * En yakın article.bs içeren parent'ı buluyoruz.
         */
        var container: Element? = heading

        repeat(6) {

            container = container?.parent()

            if (container == null) {
                return@repeat
            }

            val articles = container!!
                .select("article.bs")

            if (articles.isNotEmpty()) {

                return articles
                    .mapNotNull {
                        it.toHomeAnimeCard()
                    }
                    .distinctBy {
                        it.url
                    }
            }
        }

        return emptyList()
    }

    /*
     * Seri/tür sayfalarındaki standart kart.
     */
    private fun Element.toAnimeCard(): SearchResponse? {

        val title = selectFirst("div.tt.tts")
            ?.text()
            ?.trim()
            ?: selectFirst("a")
                ?.attr("title")
                ?.trim()
            ?: return null

        val href = selectFirst("a")
            ?.absUrl("href")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return null

        val img = selectFirst("img")

        val poster = img
            ?.absUrl("src")
            ?.takeIf {
                it.isNotBlank()
            }
            ?: img
                ?.absUrl("data-src")
                ?.takeIf {
                    it.isNotBlank()
                }
            ?: img
                ?.absUrl("data-lazy-src")
                ?.takeIf {
                    it.isNotBlank()
                }

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            posterUrl = poster
        }
    }

    /*
     * Ana sayfadaki Son Eklenenler / Popüler Seriler
     * kartlarının HTML'i biraz farklı:
     *
     * div.tt
     * span.epx
     * article.bs
     *
     * Burada ekranda görünen seri adını alıyoruz.
     */
    private fun Element.toHomeAnimeCard(): SearchResponse? {

        val anchor = selectFirst("a[href]")
            ?: return null

        val href = anchor
            .absUrl("href")
            .trim()
            .takeIf {
                it.isNotBlank()
            }
            ?: return null

        /*
         * div.tt içinde seri adı + h2 bölüm başlığı birlikte
         * bulunduğu için h2'yi kaldırarak yalnız seri adını
         * almaya çalışıyoruz.
         */
        val titleElement = selectFirst("div.tt")

        val title = if (titleElement != null) {

            val clone = titleElement.clone()

            clone
                .select("h1, h2, h3, h4")
                .remove()

            clone
                .text()
                .trim()
                .takeIf {
                    it.isNotBlank()
                }

        } else {
            null
        }
            ?: selectFirst("h2")
                ?.text()
                ?.replace(
                    Regex(
                        """(?i)\s+\d+\.\s*Bölüm.*$"""
                    ),
                    ""
                )
                ?.replace(
                    Regex(
                        """(?i)\s+\d+\.Bölüm.*$"""
                    ),
                    ""
                )
                ?.replace(
                    Regex(
                        """(?i)\s+\d+\s*Bölüm.*$"""
                    ),
                    ""
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
            ?: anchor
                .attr("oldtitle")
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
            ?: return null

        val img = selectFirst("img")

        val poster = img
            ?.absUrl("src")
            ?.takeIf {
                it.isNotBlank()
            }
            ?: img
                ?.absUrl("data-src")
                ?.takeIf {
                    it.isNotBlank()
                }
            ?: img
                ?.absUrl("data-lazy-src")
                ?.takeIf {
                    it.isNotBlank()
                }

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            posterUrl = poster
        }
    }

    /*
     * Arama.
     */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {

        val document = app.get(
            "$mainUrl/series/list-mode/"
        ).document

        val matches = document
            .select("div.soralist li a")
            .filter {

                it.text()
                    .contains(
                        query,
                        ignoreCase = true
                    )
            }
            .take(30)

        val results = matches
            .mapNotNull { element ->

                val title = element
                    .text()
                    .trim()

                val url = element
                    .absUrl("href")
                    .trim()

                if (
                    title.isBlank() ||
                    url.isBlank()
                ) {
                    return@mapNotNull null
                }

                val detailDocument = try {

                    app.get(url).document

                } catch (_: Exception) {

                    null
                }

                val poster = detailDocument
                    ?.selectFirst(
                        "div.thumb img"
                    )
                    ?.let { img ->

                        img
                            .absUrl("src")
                            .takeIf {
                                it.isNotBlank()
                            }
                            ?: img
                                .absUrl("data-src")
                                .takeIf {
                                    it.isNotBlank()
                                }
                    }

                newAnimeSearchResponse(
                    title,
                    url,
                    TvType.Anime
                ) {
                    posterUrl = poster
                }
            }

        return results
            .toNewSearchResponseList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst("div.infox h1")
            ?.text()
            ?.trim()
            ?: document
                .selectFirst("h1")
                ?.text()
                ?.trim()
            ?: return null

        val poster = document
            .selectFirst("div.thumb img")
            ?.let { img ->

                img
                    .absUrl("src")
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?: img
                        .absUrl("data-src")
                        .takeIf {
                            it.isNotBlank()
                        }
            }
            ?: document
                .selectFirst(
                    "meta[property='og:image']"
                )
                ?.attr("content")
                ?.trim()

        val description = document
            .selectFirst("div.entry-content b")
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "meta[property='og:description']"
                )
                ?.attr("content")
                ?.trim()

        val year = document
            .select(".spe span")
            .map {
                it.text().trim()
            }
            .firstOrNull {

                it.contains(
                    "Yayın Yılı:",
                    ignoreCase = true
                )
            }
            ?.let {

                Regex(
                    """(19|20)\d{2}"""
                )
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }

        /*
         * Türler.
         */
        val tags = document
            .select(
                "a[href*='/genres/']"
            )
            .map {
                it.text().trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()

        val duration = document
            .select(".spe span")
            .firstOrNull {

                it.text().contains(
                    "Dakika",
                    ignoreCase = true
                )
            }
            ?.text()
            ?.let {

                Regex("""\d+""")
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }

        /*
         * Bölümler.
         */
        val structuredEpisodes = document
            .select(
                "div.eplister ul li"
            )
            .mapNotNull { episodeElement ->

                val link = episodeElement
                    .selectFirst("a")
                    ?: return@mapNotNull null

                val episodeUrl = link
                    .absUrl("href")
                    .trim()

                if (episodeUrl.isBlank()) {
                    return@mapNotNull null
                }

                val numberText = episodeElement
                    .selectFirst(
                        "div.epl-num"
                    )
                    ?.text()
                    ?.trim()

                val rawTitle = episodeElement
                    .selectFirst(
                        "div.epl-title"
                    )
                    ?.text()
                    ?.trim()
                    .orEmpty()

                val episodePoster = episodeElement
                    .selectFirst("img")
                    ?.let { img ->

                        img
                            .absUrl("src")
                            .takeIf {
                                it.isNotBlank()
                            }
                            ?: img
                                .absUrl("data-src")
                                .takeIf {
                                    it.isNotBlank()
                                }
                            ?: img
                                .absUrl("data-lazy-src")
                                .takeIf {
                                    it.isNotBlank()
                                }
                    }

                val episodeNumber = numberText
                    ?.let {

                        Regex("""\d+""")
                            .find(it)
                            ?.value
                            ?.toIntOrNull()
                    }

                val cleanedTitle = rawTitle
                    .replace(
                        Regex(
                            "(?i)${Regex.escape(title)}[\\s.:-]*"
                        ),
                        ""
                    )
                    .replace(
                        Regex(
                            """(?i)\s*-\s*izle"""
                        ),
                        ""
                    )
                    .trim()
                    .replace(
                        Regex("""\s+"""),
                        " "
                    )

                newEpisode(
                    episodeUrl
                ) {

                    episode = episodeNumber
                    posterUrl = episodePoster

                    name = when {

                        cleanedTitle.isNotBlank() ->
                            cleanedTitle

                        episodeNumber != null ->
                            "Bölüm $episodeNumber"

                        else ->
                            numberText
                                ?: "Bölüm"
                    }
                }
            }
            .distinctBy {
                it.data
            }

        /*
         * eplister yoksa fallback.
         */
        val episodes = if (
            structuredEpisodes.isNotEmpty()
        ) {

            structuredEpisodes

        } else {

            document
                .select("a[href]")
                .mapNotNull { element ->

                    val episodeUrl = element
                        .absUrl("href")
                        .trim()

                    val text = element
                        .text()
                        .trim()

                    if (
                        episodeUrl.isBlank() ||
                        text.isBlank() ||
                        !text.contains(
                            "Bölüm",
                            ignoreCase = true
                        ) ||
                        episodeUrl.contains(
                            "/series/"
                        )
                    ) {
                        return@mapNotNull null
                    }

                    val number = Regex(
                        """\d+"""
                    )
                        .find(text)
                        ?.value
                        ?.toIntOrNull()

                    newEpisode(
                        episodeUrl
                    ) {
                        name = text
                        episode = number
                    }
                }
                .distinctBy {
                    it.data
                }
        }

        /*
         * Öneriler.
         */
        val recommendations = document
            .select("article.bs")
            .mapNotNull {
                it.toAnimeCard()
            }
            .filter {
                it.url != url
            }
            .distinctBy {
                it.url
            }
            .take(20)

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.Anime,
            episodes
        ) {

            posterUrl = poster
            plot = description

            this.year = year
            this.tags = tags

            if (duration != null) {
                this.duration = duration
            }

            this.recommendations =
                recommendations
        }
    }

    /*
     * PLAYER
     *
     * Mirror seçeneklerinin value alanındaki Base64 HTML'i
     * çözüyoruz ve iframe URL'sini CloudStream'e veriyoruz.
     *
     * Önceki sürümde:
     * - ok.ru
     * - odnoklassniki
     *
     * özel olarak engelleniyordu.
     *
     * Artık blacklist yok.
     */
    @OptIn(
        ExperimentalEncodingApi::class
    )
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

        val playerUrls =
            mutableListOf<String>()

        /*
         * Mirror dropdown.
         */
        document
            .select(
                "select.mirror option[value], " +
                    "select option[value]"
            )
            .forEach { option ->

                try {

                    val encodedHtml = option
                        .attr("value")
                        .trim()

                    if (
                        encodedHtml.isBlank()
                    ) {
                        return@forEach
                    }

                    val decodedHtml = Base64
                        .decode(
                            encodedHtml
                        )
                        .toString(
                            Charsets.UTF_8
                        )

                    val iframeSrc = Regex(
                        """src\s*=\s*["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            decodedHtml
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?: return@forEach

                    val cleanUrl =
                        normalizePlayerUrl(
                            iframeSrc
                        )

                    if (
                        cleanUrl.isNotBlank()
                    ) {
                        playerUrls.add(
                            cleanUrl
                        )
                    }

                } catch (_: Exception) {
                }
            }

        /*
         * Doğrudan iframe fallback.
         */
        document
            .select(
                "iframe[src]"
            )
            .mapNotNull { iframe ->

                iframe
                    .attr("src")
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
            }
            .mapNotNull { iframeSrc ->

                try {

                    normalizePlayerUrl(
                        iframeSrc
                    )

                } catch (_: Exception) {

                    null
                }
            }
            .filter {
                it.isNotBlank()
            }
            .forEach {

                playerUrls.add(it)
            }

        var found = false

        /*
         * Host ayrımı yapmıyoruz.
         *
         * Sibnet
         * Voe
         * Dailymotion
         * OK.ru / Odnoklassniki
         * Mail.ru
         * vb.
         *
         * CloudStream'in kayıtlı extractor sistemine gönderilir.
         */
        playerUrls
            .distinct()
            .forEach { playerUrl ->

                try {

                    val success =
                        loadExtractor(
                            url = playerUrl,
                            referer = data,
                            subtitleCallback =
                                subtitleCallback,
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

    /*
     * Player URL normalize.
     */
    private fun normalizePlayerUrl(
        rawUrl: String
    ): String {

        val cleaned = rawUrl
            .trim()
            .replace(
                "\\/",
                "/"
            )
            .replace(
                "&amp;",
                "&"
            )

        return when {

            cleaned.startsWith("//") ->
                "https:$cleaned"

            cleaned.startsWith(
                "http://"
            ) ||
                cleaned.startsWith(
                    "https://"
                ) ->
                cleaned

            else ->
                fixUrl(cleaned)
        }
    }
}
