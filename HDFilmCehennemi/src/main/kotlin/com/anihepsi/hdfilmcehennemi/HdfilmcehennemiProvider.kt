package com.anihepsi.hdfilmcehennemi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
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
        "$mainUrl/hd-yabanci-dizi-izle/" to "Yabancı Diziler",
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
                request.data
                    .trimEnd('/') + "/page/$page/"
            }

        val document =
            app.get(
                pageUrl,
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

        val defaultType =
            if (
                request.name.contains(
                    "Dizi",
                    ignoreCase = true
                )
            ) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

        /*
         * Artık önce "strict card" aramıyoruz.
         *
         * Sayfadaki tüm resimli içerik linklerini tarıyoruz.
         * Ardından kategori, menü, reklam, sosyal medya vb.
         * bağlantıları filtreliyoruz.
         */

        val results =
            document
                .select("a[href]")
                .mapNotNull {
                    it.toCatalogResult(
                        defaultType
                    )
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
            attr("href")
                .trim()

        if (
            rawHref.isBlank() ||
            rawHref == "/" ||
            rawHref == "#" ||
            rawHref.startsWith(
                "javascript:",
                ignoreCase = true
            ) ||
            rawHref.startsWith(
                "mailto:",
                ignoreCase = true
            ) ||
            rawHref.startsWith(
                "tel:",
                ignoreCase = true
            )
        ) {
            return null
        }

        val href =
            fixUrlNull(
                rawHref
            )
                ?: return null

        if (
            !href.startsWith(
                mainUrl
            )
        ) {
            return null
        }

        if (
            isIgnoredUrl(
                href
            )
        ) {
            return null
        }

        /*
         * Kartın resmi bazen <a> içinde,
         * bazen parent/container tarafında olabiliyor.
         */

        val image =
            selectFirst("img")
                ?: parent()
                    ?.selectFirst("img")
                ?: parent()
                    ?.parent()
                    ?.selectFirst("img")
                ?: return null

        val poster =
            extractImage(
                image
            )
                ?: return null

        val title =
            extractTitle(
                this,
                image
            )
                ?: return null

        if (
            isIgnoredTitle(
                title
            )
        ) {
            return null
        }

        val detectedType =
            detectType(
                href,
                title,
                defaultType
            )

        return when (
            detectedType
        ) {

            TvType.TvSeries ->
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl =
                        poster
                }

            else ->
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    posterUrl =
                        poster
                }
        }
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
                    ?.takeIf {
                        text ->
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
                image.attr(
                    "data-src"
                ),
                image.attr(
                    "data-lazy-src"
                ),
                image.attr(
                    "data-original"
                ),
                image.attr(
                    "data-srcset"
                )
                    .substringBefore(" "),
                image.attr(
                    "src"
                )
            )

        for (
            candidate in candidates
        ) {

            val value =
                candidate
                    .trim()

            if (
                value.isBlank() ||
                value.startsWith(
                    "data:image",
                    ignoreCase = true
                )
            ) {
                continue
            }

            val cleanValue =
                value
                    .substringBefore(",")
                    .trim()
                    .substringBefore(" ")
                    .trim()

            val fixed =
                fixUrlNull(
                    cleanValue
                )

            if (
                !fixed.isNullOrBlank()
            ) {
                return fixed
            }
        }

        val srcset =
            image
                .attr(
                    "srcset"
                )
                .trim()

        if (
            srcset.isNotBlank()
        ) {

            val first =
                srcset
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    ?.substringBefore(" ")
                    ?.trim()

            if (
                !first.isNullOrBlank()
            ) {

                return fixUrlNull(
                    first
                )
            }
        }

        return null
    }

    private fun isIgnoredUrl(
        url: String
    ): Boolean {

        val lower =
            url
                .lowercase()
                .substringBefore("#")

        if (
            lower == mainUrl.lowercase() ||
            lower == "$mainUrl/".lowercase()
        ) {
            return true
        }

        val ignoredParts =
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
                "/facebook",
                "/twitter",
                "/instagram",
                "/telegram"
            )

        return ignoredParts
            .any {
                lower.contains(
                    it
                )
            }
    }

    private fun isIgnoredTitle(
        title: String
    ): Boolean {

        val clean =
            title
                .trim()
                .lowercase()

        if (
            clean.length < 2
        ) {
            return true
        }

        val ignoredTitles =
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
                "reklam"
            )

        if (
            ignoredTitles.any {
                clean == it
            }
        ) {
            return true
        }

        if (
            clean.contains(
                "logo"
            )
        ) {
            return true
        }

        return false
    }

    private fun detectType(
        url: String,
        title: String,
        defaultType: TvType
    ): TvType {

        val lowerUrl =
            url.lowercase()

        val lowerTitle =
            title.lowercase()

        if (
            lowerUrl.contains(
                "/dizi/"
            ) ||
            lowerUrl.contains(
                "dizi-izle"
            ) ||
            lowerTitle.contains(
                "dizi"
            )
        ) {
            return TvType.TvSeries
        }

        return defaultType
    }

    private fun Media.toSearchResponse():
        SearchResponse? {

        val mediaTitle =
            title
                ?: return null

        val mediaSlug =
            slug
                ?: return null

        val prefix =
            slugPrefix.orEmpty()

        val url =
            fixUrlNull(
                "/$prefix$mediaSlug"
            )
                ?: return null

        val posterUrl =
            poster
                ?.let {
                    fixUrlNull(
                        "/uploads/poster/$it"
                    )
                }

        val type =
            if (
                url.contains(
                    "/dizi/",
                    ignoreCase = true
                )
            ) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

        return when (
            type
        ) {

            TvType.TvSeries ->
                newTvSeriesSearchResponse(
                    mediaTitle,
                    url,
                    TvType.TvSeries
                ) {
                    this.posterUrl =
                        posterUrl
                }

            else ->
                newMovieSearchResponse(
                    mediaTitle,
                    url,
                    TvType.Movie
                ) {
                    this.posterUrl =
                        posterUrl
                }
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response =
            app.post(
                "$mainUrl/search/",
                data = mapOf(
                    "query" to query
                ),
                headers =
                    browserHeaders +
                        mapOf(
                            "Accept" to
                                "application/json, text/javascript, */*; q=0.01",

                            "X-Requested-With" to
                                "XMLHttpRequest"
                        ),
                referer = "$mainUrl/"
            )

        val jsonResults =
            response
                .parsedSafe<Result>()
                ?.result
                ?.mapNotNull {
                    it.toSearchResponse()
                }
                .orEmpty()

        if (
            jsonResults.isNotEmpty()
        ) {
            return jsonResults
        }

        val encodedQuery =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        val document =
            app.get(
                "$mainUrl/?s=$encodedQuery",
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

        return document
            .select(
                "a[href]"
            )
            .mapNotNull {
                it.toCatalogResult(
                    TvType.Movie
                )
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
                    extractImage(
                        it
                    )
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
                    it.text()
                        .trim()
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
                .select(
                    "a.chip"
                )
                .mapNotNull {

                    val actorName =
                        it.text()
                            .trim()

                    if (
                        actorName.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    Actor(
                        actorName,
                        it.selectFirst(
                            "img"
                        )
                            ?.let {
                                img ->
                                extractImage(
                                    img
                                )
                            }
                    )
                }

        val isSeries =
            url.contains(
                "/dizi/",
                ignoreCase = true
            ) ||
                document
                    .select(
                        """
                        #seasonsTabs,
                        nav#seasonsTabs,
                        [id*=season],
                        [id*=sezon]
                        """.trimIndent()
                    )
                    .isNotEmpty() ||
                (
                    document
                        .text()
                        .contains(
                            "Sezon",
                            ignoreCase = true
                        ) &&
                        document
                            .text()
                            .contains(
                                "Bölüm",
                                ignoreCase = true
                            )
                    )

        if (
            isSeries
        ) {

            val episodes =
                mutableListOf<Episode>()

            val episodeLinks =
                document
                    .select(
                        """
                        #seasonsTabs-tabContent a[href],
                        a[href*="/sezon-"],
                        a[href*="/bolum-"],
                        a[href*="sezon"],
                        a[href*="bolum"]
                        """.trimIndent()
                    )
                    .distinctBy {
                        it.attr(
                            "href"
                        )
                    }

            for (
                element in episodeLinks
            ) {

                val href =
                    fixUrlNull(
                        element.attr(
                            "href"
                        )
                    )
                        ?: continue

                if (
                    isIgnoredUrl(
                        href
                    )
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
                            element.text()
                                .trim()
                        }
                        .ifBlank {
                            element.parent()
                                ?.text()
                                ?.trim()
                                .orEmpty()
                        }

                val season =
                    Regex(
                        """sezon[-\s_/]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            "$href $rawName"
                        )
                        ?.groupValues
                        ?.getOrNull(
                            1
                        )
                        ?.toIntOrNull()
                        ?: Regex(
                            """(\d+)\.?\s*Sezon""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(
                                rawName
                            )
                            ?.groupValues
                            ?.getOrNull(
                                1
                            )
                            ?.toIntOrNull()

                val episode =
                    Regex(
                        """(?:bolum|bölüm)[-\s_/]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            "$href $rawName"
                        )
                        ?.groupValues
                        ?.getOrNull(
                            1
                        )
                        ?.toIntOrNull()
                        ?: Regex(
                            """(\d+)\.?\s*Bölüm""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(
                                rawName
                            )
                            ?.groupValues
                            ?.getOrNull(
                                1
                            )
                            ?.toIntOrNull()

                episodes.add(
                    newEpisode(
                        href
                    ) {

                        name =
                            rawName
                                .ifBlank {

                                    when {
                                        season != null &&
                                            episode != null ->
                                            "$season. Sezon $episode. Bölüm"

                                        episode != null ->
                                            "$episode. Bölüm"

                                        else ->
                                            title
                                    }
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
                    .selectFirst(
                        "[data-trailer]"
                    )
                    ?.attr(
                        "data-trailer"
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
                    .distinctBy {
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
            title,
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

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        sourceCallback:
            (ExtractorLink) -> Unit
    ): Boolean {

        if (
            url.isBlank()
        ) {
            return false
        }

        val response =
            app.get(
                url,
                headers = browserHeaders,
                referer = "$mainUrl/"
            )

        val scriptText =
            response
                .document
                .select(
                    "script"
                )
                .joinToString(
                    "\n"
                ) {
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

        var m3uLink:
            String? = null

        for (
            pattern in patterns
        ) {

            val match =
                pattern
                    .find(
                        scriptText
                    )
                    ?.groupValues
                    ?.getOrNull(
                        1
                    )

            if (
                !match.isNullOrBlank()
            ) {
                m3uLink =
                    match
                break
            }
        }

        if (
            m3uLink.isNullOrBlank()
        ) {
            return false
        }

        M3u8Helper
            .generateM3u8(
                source,
                m3uLink,
                url
            )
            .forEach(
                sourceCallback
            )

        return true
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

        /*
         * Önce oynatıcı sekmelerini ve iframe'leri topluyoruz.
         */

        val candidates =
            mutableListOf<Pair<String, String>>()

        document
            .select(
                """
                nav.nav.card-nav.nav-slider a[href],
                a[data-player],
                a[data-src],
                iframe[src],
                iframe[data-src],
                .card-video iframe
                """.trimIndent()
            )
            .forEach {
                element ->

                val raw =
                    when {

                        element
                            .tagName()
                            .equals(
                                "iframe",
                                ignoreCase = true
                            ) ->

                            element
                                .attr(
                                    "data-src"
                                )
                                .ifBlank {
                                    element.attr(
                                        "src"
                                    )
                                }

                        element.hasAttr(
                            "data-player"
                        ) ->
                            element.attr(
                                "data-player"
                            )

                        element.hasAttr(
                            "data-src"
                        ) ->
                            element.attr(
                                "data-src"
                            )

                        else ->
                            element.attr(
                                "href"
                            )
                    }
                        .trim()

                if (
                    raw.isBlank()
                ) {
                    return@forEach
                }

                val fixed =
                    fixUrlNull(
                        raw
                    )
                        ?: return@forEach

                val sourceName =
                    element
                        .text()
                        .trim()
                        .ifBlank {
                            name
                        }

                candidates.add(
                    sourceName to fixed
                )
            }

        for (
            (
                sourceName,
                sourceUrl
            ) in candidates.distinctBy {
                it.second
            }
        ) {

            safeApiCall {

                /*
                 * Önce CloudStream'in kendi extractor sistemi.
                 */

                loadExtractor(
                    sourceUrl,
                    data,
                    subtitleCallback,
                    callback
                )

                /*
                 * Kaynak sayfasının içindeki iframe'e de bak.
                 */

                val sourcePage =
                    app.get(
                        sourceUrl,
                        headers = browserHeaders,
                        referer = data
                    ).document

                val iframe =
                    sourcePage
                        .selectFirst(
                            "iframe[data-src], iframe[src]"
                        )

                val iframeUrl =
                    iframe
                        ?.attr(
                            "data-src"
                        )
                        ?.ifBlank {
                            iframe.attr(
                                "src"
                            )
                        }
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            fixUrlNull(
                                it
                            )
                        }

                if (
                    iframeUrl != null
                ) {

                    loadExtractor(
                        iframeUrl,
                        sourceUrl,
                        subtitleCallback,
                        callback
                    )

                    invokeLocalSource(
                        sourceName,
                        iframeUrl,
                        callback
                    )
                }

                invokeLocalSource(
                    sourceName,
                    sourceUrl,
                    callback
                )
            }
        }

        return true
    }

    data class Result(

        @JsonProperty("result")
        val result:
            ArrayList<Media>? =
            arrayListOf()
    )

    data class Media(

        @JsonProperty("title")
        val title:
            String? = null,

        @JsonProperty("poster")
        val poster:
            String? = null,

        @JsonProperty("slug")
        val slug:
            String? = null,

        @JsonProperty("slug_prefix")
        val slugPrefix:
            String? = null
    )
}
