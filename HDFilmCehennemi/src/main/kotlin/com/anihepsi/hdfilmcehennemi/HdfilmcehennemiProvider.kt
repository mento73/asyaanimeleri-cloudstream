package com.anihepsi.hdfilmcehennemi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * Original Hdfilmcehennemi implementation:
 * Hexated / cloudstream-extensions-multilingual
 *
 * Adapted for the Anihepsi CloudStream repository.
 */

class HdfilmcehennemiProvider : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"

    override val hasMainPage = true
    override var lang = "tr"

    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val browserHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",

        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,*/*;q=0.8",

        "Accept-Language" to
            "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenenler",
        "$mainUrl/category/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "$mainUrl/yabancidiziizle-5/" to "Yabancı Diziler",
        "$mainUrl/category/populer-diziler-2/" to "Popüler Diziler",
        "$mainUrl/imdb-7-puan-uzeri-filmler-2/" to "IMDb 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar-2/" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle-4/" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageUrl =
            if (page <= 1) {
                request.data
            } else {
                request.data.trimEnd('/') + "/page/$page/"
            }

        val document =
            app.get(
                pageUrl,
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

        val defaultType =
            if (
                request.name.contains("Dizi", true) ||
                request.name.contains("Diziler", true)
            ) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

        val results =
            document
                .select("a[href]")
                .mapNotNull {
                    it.toCatalogResult(defaultType)
                }
                .distinctBy {
                    it.url
                }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    private fun Element.toCatalogResult(
        defaultType: TvType
    ): SearchResponse? {

        val rawHref =
            attr("href").trim()

        if (
            rawHref.isBlank() ||
            rawHref == "/" ||
            rawHref == "#" ||
            rawHref.startsWith("javascript:", true) ||
            rawHref.startsWith("mailto:", true) ||
            rawHref.startsWith("tel:", true)
        ) {
            return null
        }

        val href =
            fixUrlNull(rawHref)
                ?: return null

        if (!href.startsWith(mainUrl)) {
            return null
        }

        if (isIgnoredUrl(href)) {
            return null
        }

        val likelyContent =
            href.contains("/dizi/", true) ||
                (
                    !href.contains("/category/", true) &&
                        !href.contains("/tur/", true) &&
                        !href.contains("/apk/", true)
                    )

        if (!likelyContent) {
            return null
        }

        val image =
            selectFirst("img")
                ?: parent()?.selectFirst("img")
                ?: parent()?.parent()?.selectFirst("img")
                ?: return null

        val poster =
            extractImage(image)
                ?: return null

        val title =
            extractTitle(this, image)
                ?: return null

        if (isIgnoredTitle(title)) {
            return null
        }

        val detectedType =
            when {

                href.contains("/dizi/", true) ->
                    TvType.TvSeries

                title.contains("Yabancı Dizi", true) ->
                    TvType.TvSeries

                defaultType == TvType.TvSeries ->
                    TvType.TvSeries

                else ->
                    TvType.Movie
            }

        return if (detectedType == TvType.TvSeries) {

            newTvSeriesSearchResponse(
                cleanTitle(title),
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                cleanTitle(title),
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    private fun cleanTitle(
        title: String
    ): String {

        return title
            .replace(
                Regex(
                    """\s+Yabancı Dizi\s*$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
    }

    private fun extractTitle(
        anchor: Element,
        image: Element
    ): String? {

        val candidates =
            listOf(
                image.attr("alt"),
                image.attr("title"),
                anchor.attr("title"),

                anchor
                    .selectFirst(
                        "h1, h2, h3, h4, .title, .name"
                    )
                    ?.text(),

                anchor
                    .parent()
                    ?.selectFirst(
                        "h1, h2, h3, h4, .title, .name"
                    )
                    ?.text(),

                anchor
                    .parent()
                    ?.parent()
                    ?.selectFirst(
                        "h1, h2, h3, h4, .title, .name"
                    )
                    ?.text(),

                anchor.text()
            )

        return candidates
            .mapNotNull {
                it
                    ?.trim()
                    ?.replace(
                        Regex("""\s+"""),
                        " "
                    )
                    ?.takeIf { text ->
                        text.isNotBlank()
                    }
            }
            .firstOrNull()
    }

    private fun extractImage(
        image: Element
    ): String? {

        val candidates =
            listOf(
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
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

            val clean =
                value
                    .substringBefore(",")
                    .trim()
                    .substringBefore(" ")
                    .trim()

            val fixed =
                fixUrlNull(clean)

            if (!fixed.isNullOrBlank()) {
                return fixed
            }
        }

        val srcset =
            image.attr("srcset").trim()

        if (srcset.isNotBlank()) {

            val first =
                srcset
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    ?.substringBefore(" ")
                    ?.trim()

            if (!first.isNullOrBlank()) {
                return fixUrlNull(first)
            }
        }

        return null
    }

    private fun isIgnoredUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()
                .substringBefore("#")

        if (
            lower == mainUrl.lowercase() ||
            lower == "$mainUrl/".lowercase()
        ) {
            return true
        }

        val ignored =
            listOf(
                "/category/",
                "/kategori/",
                "/tag/",
                "/etiket/",
                "/author/",
                "/page/",
                "/feed/",
                "/wp-content/",
                "/wp-admin/",
                "/wp-login",
                "/search/",
                "/ara/",
                "/iletisim",
                "/hakkimizda",
                "/gizlilik",
                "/dmca",
                "/sss",
                "/yardim",
                "/film-istek",
                "/film-robot",
                "/reklam",
                "/apk/",
                "/facebook",
                "/twitter",
                "/instagram",
                "/telegram"
            )

        return ignored.any {
            lower.contains(it)
        }
    }

    private fun isIgnoredTitle(
        title: String
    ): Boolean {

        val clean =
            title.trim().lowercase()

        if (clean.length < 2) {
            return true
        }

        val ignored =
            listOf(
                "hdfilmcehennemi",
                "ana sayfa",
                "anasayfa",
                "filmler",
                "diziler",
                "kategoriler",
                "türler",
                "turler",
                "arama",
                "ara",
                "giriş",
                "giris",
                "üye ol",
                "uye ol",
                "facebook",
                "twitter",
                "instagram",
                "telegram",
                "reklam",
                "apk"
            )

        return ignored.any {
            clean == it
        } ||
            clean.contains("logo")
    }

    /*
     * SEARCH
     *
     * Güncel HDFilmCehennemi araması:
     * GET /search/?q=QUERY
     *
     * Sunucu JSON içinde HTML kart parçaları döndürüyor.
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

        val encodedQuery =
            URLEncoder.encode(
                query.trim(),
                "UTF-8"
            )

        val response =
            app.get(
                "$mainUrl/search/?q=$encodedQuery",
                headers =
                    browserHeaders +
                        mapOf(
                            "X-Requested-With" to "fetch",
                            "Accept" to "application/json"
                        ),
                referer = "$mainUrl/"
            )

        val snippets =
            response
                .parsedSafe<SearchAjaxResponse>()
                ?.results
                .orEmpty()

        return snippets
            .mapNotNull {
                parseSearchSnippet(it)
            }
            .distinctBy {
                it.url
            }
    }

    private fun parseSearchSnippet(
        html: String
    ): SearchResponse? {

        if (html.isBlank()) {
            return null
        }

        val document =
            Jsoup.parseBodyFragment(
                html,
                mainUrl
            )

        val container =
            document.body()

        val anchor =
            container
                .selectFirst("a[href]")
                ?: return null

        val href =
            fixUrlNull(
                anchor.attr("href")
            )
                ?: return null

        val image =
            container.selectFirst("img")

        val title =
            container
                .selectFirst("h4.title")
                ?.text()
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
                ?: anchor
                    .text()
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                ?: return null

        val poster =
            image?.let {
                extractImage(it)
            }

        val typeText =
            container
                .selectFirst(".type")
                ?.text()
                ?.trim()
                .orEmpty()

        val isSeries =
            typeText.equals(
                "dizi",
                ignoreCase = true
            ) ||
                href.contains(
                    "/dizi/",
                    ignoreCase = true
                )

        return if (isSeries) {

            newTvSeriesSearchResponse(
                cleanTitle(title),
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                cleanTitle(title),
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
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
                    div.card-header > h1,
                    div.card-header > h2,
                    .film-title,
                    .movie-title,
                    .entry-title
                    """.trimIndent()
                )
                ?.text()
                ?.trim()
                ?: return null

        val poster =
            document
                .selectFirst(
                    """
                    img.img-fluid,
                    .poster img,
                    article img,
                    .film-poster img,
                    .movie-poster img
                    """.trimIndent()
                )
                ?.let {
                    extractImage(it)
                }

        val description =
            document
                .selectFirst(
                    """
                    article.text-white > p,
                    article p,
                    .description,
                    .film-ozeti,
                    .summary,
                    .entry-content p
                    """.trimIndent()
                )
                ?.text()
                ?.trim()

        val tags =
            document
                .select(
                    """
                    div.mb-0.lh-lg a,
                    a[href*=kategori],
                    a[href*=category],
                    a[href*=tur]
                    """.trimIndent()
                )
                .map {
                    it.text().trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        val year =
            Regex(
                """\b(19|20)\d{2}\b"""
            )
                .find(
                    document.text()
                )
                ?.value
                ?.toIntOrNull()

        val actors =
            document
                .select("a.chip")
                .mapNotNull {

                    val actorName =
                        it.text().trim()

                    if (actorName.isBlank()) {
                        return@mapNotNull null
                    }

                    Actor(
                        actorName,
                        it.selectFirst("img")
                            ?.let { img ->
                                extractImage(img)
                            }
                    )
                }

        val isSeries =
            url.contains(
                "/dizi/",
                ignoreCase = true
            )

        if (isSeries) {

            val episodes =
                mutableListOf<Episode>()

            val episodeLinks =
                document
                    .select(
                        """
                        a[href*="/sezon-"][href*="/bolum-"],
                        #seasonsTabs-tabContent a[href]
                        """.trimIndent()
                    )
                    .distinctBy {
                        it.attr("href")
                    }

            for (element in episodeLinks) {

                val href =
                    fixUrlNull(
                        element.attr("href")
                    )
                        ?: continue

                if (
                    !href.contains("/dizi/", true) ||
                    !href.contains("/bolum-", true)
                ) {
                    continue
                }

                val rawName =
                    element
                        .selectFirst(
                            "h1, h2, h3, h4, .title"
                        )
                        ?.text()
                        ?.trim()
                        .orEmpty()
                        .ifBlank {
                            element.text().trim()
                        }
                        .ifBlank {
                            element.parent()
                                ?.text()
                                ?.trim()
                                .orEmpty()
                        }

                val season =
                    Regex(
                        """sezon-(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                val episode =
                    Regex(
                        """bolum-(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                episodes.add(
                    newEpisode(href) {

                        name =
                            when {

                                season != null &&
                                    episode != null ->
                                    "$season. Sezon $episode. Bölüm"

                                rawName.isNotBlank() ->
                                    rawName

                                else ->
                                    "Bölüm"
                            }

                        this.season =
                            season

                        this.episode =
                            episode
                    }
                )
            }

            val trailer =
                document
                    .selectFirst("[data-trailer]")
                    ?.attr("data-trailer")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

            return newTvSeriesLoadResponse(
                cleanTitle(title),
                url,
                TvType.TvSeries,
                episodes.distinctBy {
                    it.data
                }
            ) {

                posterUrl =
                    poster

                this.year =
                    year

                plot =
                    description

                this.tags =
                    tags

                addActors(
                    actors
                )

                addTrailer(
                    trailer
                )
            }
        }

        return newMovieLoadResponse(
            cleanTitle(title),
            url,
            TvType.Movie,
            url
        ) {

            posterUrl =
                poster

            this.year =
                year

            plot =
                description

            this.tags =
                tags

            addActors(
                actors
            )
        }
    }

    /*
     * PLAYER
     *
     * Bu bölüm yalnızca sayfada açıkça bulunan iframe / Rapidrame
     * bağlantılarını ve normal CloudStream extractor akışını kullanır.
     * Şifre çözme, token üretme veya erişim kontrolü aşma yoktur.
     */

    private fun cleanPlayerUrl(
        raw: String
    ): String? {

        val cleaned =
            raw
                .trim()
                .trim('"', '\'', ' ')
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")

        if (
            cleaned.isBlank() ||
            cleaned == "#" ||
            cleaned.startsWith("javascript:", true)
        ) {
            return null
        }

        return fixUrlNull(cleaned)
    }

    private fun Element.playerAttributeUrls():
        List<String> {

        val attributes =
            listOf(
                "href",
                "src",
                "data-src",
                "data-url",
                "data-href",
                "data-link",
                "data-player",
                "data-iframe"
            )

        val urls =
            mutableListOf<String>()

        for (attribute in attributes) {

            val value =
                attr(attribute)
                    .trim()

            if (value.isBlank()) {
                continue
            }

            cleanPlayerUrl(value)
                ?.let {
                    urls.add(it)
                }
        }

        /*
         * Bazı player düğmeleri URL'yi onclick içine koyabiliyor.
         * Yalnızca açıkça yazılmış http(s) / protokol-relative URL'leri alıyoruz.
         */
        val onclick =
            attr("onclick")

        Regex(
            """(?i)(https?:)?//[^\s"'<>\\]+"""
        )
            .findAll(onclick)
            .mapNotNull {
                cleanPlayerUrl(
                    it.value
                )
            }
            .forEach {
                urls.add(it)
            }

        return urls.distinct()
    }

    private fun collectPlayerCandidates(
        document: org.jsoup.nodes.Document
    ): List<Pair<String, String>> {

        val candidates =
            mutableListOf<Pair<String, String>>()

        /*
         * Önce doğrudan iframe'ler.
         */
        document
            .select(
                "iframe[src], iframe[data-src]"
            )
            .forEach { iframe ->

                iframe
                    .playerAttributeUrls()
                    .forEach { url ->

                        candidates.add(
                            "HDFilmCehennemi" to url
                        )
                    }
            }

        /*
         * Rapidrame yazan düğme/anchor ve player veri attribute'ları.
         * Sayfada görünür "Rapidrame" butonunu özellikle hedefliyoruz.
         */
        document
            .select(
                """
                a[href],
                button,
                [data-player],
                [data-src],
                [data-url],
                [data-href],
                [data-link],
                [data-iframe]
                """.trimIndent()
            )
            .forEach { element ->

                val text =
                    element
                        .text()
                        .trim()

                val attrs =
                    listOf(
                        element.attr("href"),
                        element.attr("src"),
                        element.attr("data-src"),
                        element.attr("data-url"),
                        element.attr("data-href"),
                        element.attr("data-link"),
                        element.attr("data-player"),
                        element.attr("data-iframe"),
                        element.attr("onclick")
                    )
                        .joinToString(" ")

                val looksLikePlayer =
                    text.contains(
                        "rapidrame",
                        ignoreCase = true
                    ) ||
                        attrs.contains(
                            "rapidrame",
                            ignoreCase = true
                        ) ||
                        element.hasAttr("data-player") ||
                        element.hasAttr("data-iframe")

                if (!looksLikePlayer) {
                    return@forEach
                }

                val sourceName =
                    text
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: "Rapidrame"

                element
                    .playerAttributeUrls()
                    .forEach { url ->

                        candidates.add(
                            sourceName to url
                        )
                    }
            }

        /*
         * Açık HTML/script içinde doğrudan Rapidrame URL'si varsa onu da al.
         * Burada herhangi bir şifre çözme yapılmıyor; yalnızca düz metin URL.
         */
        val html =
            document.html()

        Regex(
            """(?i)(https?:)?//[^"'<>\\\s]*rapidrame[^"'<>\\\s]*"""
        )
            .findAll(html)
            .mapNotNull {
                cleanPlayerUrl(
                    it.value
                )
            }
            .forEach { url ->

                candidates.add(
                    "Rapidrame" to url
                )
            }

        return candidates
            .distinctBy {
                it.second
            }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        referer: String,
        sourceCallback:
            (ExtractorLink) -> Unit
    ): Boolean {

        if (url.isBlank()) {
            return false
        }

        val response =
            app.get(
                url,
                headers = browserHeaders,
                referer = referer
            )

        val scriptText =
            response
                .document
                .select("script")
                .joinToString("\n") {
                    it.data()
                }

        val patterns =
            listOf(
                Regex(
                    """file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """"file"\s*:\s*"([^"]+\.m3u8[^"]*)"""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """["'](https?://[^"']+\.m3u8[^"']*)["']""",
                    RegexOption.IGNORE_CASE
                )
            )

        var found =
            false

        for (pattern in patterns) {

            pattern
                .findAll(scriptText)
                .mapNotNull {
                    it.groupValues
                        .getOrNull(1)
                        ?.replace("\\/", "/")
                        ?.replace("&amp;", "&")
                        ?.takeIf { link ->
                            link.isNotBlank()
                        }
                }
                .distinct()
                .forEach { m3uLink ->

                    M3u8Helper
                        .generateM3u8(
                            source,
                            m3uLink,
                            url
                        )
                        .forEach {
                            sourceCallback(it)
                            found = true
                        }
                }
        }

        return found
    }

    private suspend fun tryPlayerUrl(
        sourceName: String,
        sourceUrl: String,
        referer: String,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ) {

        /*
         * 1) CloudStream'in standart extractor sistemi.
         */
        safeApiCall {
            loadExtractor(
                sourceUrl,
                referer,
                subtitleCallback,
                callback
            )
        }

        /*
         * 2) Sayfa düz m3u8 içeriyorsa normal parser.
         */
        safeApiCall {
            invokeLocalSource(
                sourceName,
                sourceUrl,
                referer,
                callback
            )
        }

        /*
         * 3) Kaynak sayfasının içinde açık iframe varsa bir seviye daha izle.
         */
        safeApiCall {

            val sourceDocument =
                app.get(
                    sourceUrl,
                    headers = browserHeaders,
                    referer = referer
                ).document

            val nestedCandidates =
                collectPlayerCandidates(
                    sourceDocument
                )

            for (
                (nestedName, nestedUrl)
                in nestedCandidates
            ) {

                if (nestedUrl == sourceUrl) {
                    continue
                }

                safeApiCall {
                    loadExtractor(
                        nestedUrl,
                        sourceUrl,
                        subtitleCallback,
                        callback
                    )
                }

                safeApiCall {
                    invokeLocalSource(
                        nestedName.ifBlank {
                            sourceName
                        },
                        nestedUrl,
                        sourceUrl,
                        callback
                    )
                }
            }
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

        val document =
            app.get(
                data,
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

        val candidates =
            collectPlayerCandidates(
                document
            )
                .toMutableList()

        /*
         * Eski HDFilmCehennemi yapısındaki kaynak sekmelerini de koruyoruz.
         * Bunlar Rapidrame URL'sine giden normal bir ara sayfa olabilir.
         */
        document
            .select(
                "nav.nav.card-nav.nav-slider a[href]"
            )
            .forEach { element ->

                val sourceName =
                    element
                        .text()
                        .trim()
                        .ifBlank {
                            "HDFilmCehennemi"
                        }

                element
                    .playerAttributeUrls()
                    .forEach { url ->

                        candidates.add(
                            sourceName to url
                        )
                    }
            }

        for (
            (sourceName, sourceUrl)
            in candidates.distinctBy {
                it.second
            }
        ) {

            tryPlayerUrl(
                sourceName,
                sourceUrl,
                data,
                subtitleCallback,
                callback
            )
        }

        return true
    }

    data class SearchAjaxResponse(
        @JsonProperty("results")
        val results:
            ArrayList<String>? =
            arrayListOf()
    )
}
