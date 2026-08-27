package com.anihepsi.hdfilmcehennemi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif," +
            "image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
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

        val pageUrl = when {
            page <= 1 -> request.data

            request.data == "$mainUrl/" ->
                "${request.data}page/$page/"

            else ->
                request.data.trimEnd('/') + "/page/$page/"
        }

        val document = app.get(
            pageUrl,
            headers = browserHeaders,
            referer = "$mainUrl/"
        ).document

        val requestedType =
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
         * Güncel sitede kart class'ları zaman zaman değişiyor.
         * Bu nedenle yalnızca "poster-container" gibi tek bir selector'a
         * bağlı kalmıyoruz.
         *
         * Önce olası kart yapıları deneniyor.
         * Sonuç alınamazsa resim içeren linklerden içerik kartı çıkarılıyor.
         */

        val strictCards = document.select(
            """
            div.poster-container,
            div.poster,
            div.movie,
            div.movie-item,
            div.card-list-item,
            div[class*=poster],
            article
            """.trimIndent()
        )

        var results = strictCards
            .mapNotNull {
                it.toCatalogResult(requestedType)
            }
            .distinctBy {
                it.url
            }

        if (results.isEmpty()) {

            results = document
                .select("a[href]")
                .mapNotNull {
                    it.anchorToCatalogResult(requestedType)
                }
                .distinctBy {
                    it.url
                }
        }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    private fun Element.toCatalogResult(
        defaultType: TvType
    ): SearchResponse? {

        val anchor =
            if (tagName() == "a") {
                this
            } else {
                selectFirst("a[href]")
            }
                ?: return null

        return anchor.anchorToCatalogResult(
            defaultType
        )
    }

    private fun Element.anchorToCatalogResult(
        defaultType: TvType
    ): SearchResponse? {

        val rawHref =
            attr("href")
                .trim()

        if (
            rawHref.isBlank() ||
            rawHref.startsWith("#") ||
            rawHref.startsWith("javascript:", true)
        ) {
            return null
        }

        val href =
            fixUrlNull(
                rawHref
            )
                ?: return null

        if (!href.startsWith(mainUrl)) {
            return null
        }

        val lowerHref =
            href.lowercase()

        if (
            lowerHref == mainUrl.lowercase() ||
            lowerHref == "$mainUrl/".lowercase() ||
            lowerHref.contains("/category/") ||
            lowerHref.contains("/tag/") ||
            lowerHref.contains("/author/") ||
            lowerHref.contains("/page/") ||
            lowerHref.contains("/iletisim") ||
            lowerHref.contains("/yardim") ||
            lowerHref.contains("/film-istek") ||
            lowerHref.contains("/film-robot")
        ) {
            return null
        }

        val image =
            selectFirst("img")
                ?: parent()
                    ?.selectFirst("img")
                ?: return null

        val poster =
            getImageUrl(
                image
            )

        if (poster.isNullOrBlank()) {
            return null
        }

        val imageAlt =
            image.attr("alt")
                .trim()

        val anchorTitle =
            attr("title")
                .trim()

        val childTitle =
            selectFirst(
                "h1, h2, h3, h4, .title, .name"
            )
                ?.text()
                ?.trim()
                .orEmpty()

        val parentTitle =
            parent()
                ?.selectFirst(
                    "h1, h2, h3, h4, .title, .name"
                )
                ?.text()
                ?.trim()
                .orEmpty()

        val linkText =
            text()
                .trim()

        val title =
            sequenceOf(
                imageAlt,
                anchorTitle,
                childTitle,
                parentTitle,
                linkText
            )
                .firstOrNull {
                    it.isNotBlank()
                }
                ?.replace(
                    Regex("""\s+"""),
                    " "
                )
                ?.trim()
                ?: return null

        if (
            title.length < 2 ||
            title.equals(
                "HDFilmCehennemi",
                ignoreCase = true
            ) ||
            title.contains(
                "logo",
                ignoreCase = true
            )
        ) {
            return null
        }

        val detectedType =
            when {

                lowerHref.contains("/dizi/") ||
                    title.contains(
                        "Yabancı Dizi",
                        ignoreCase = true
                    ) ->
                    TvType.TvSeries

                else ->
                    defaultType
            }

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

    private fun getImageUrl(
        image: Element?
    ): String? {

        if (image == null) {
            return null
        }

        val candidates =
            listOf(
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("src")
            )

        for (
            candidate in candidates
        ) {

            val clean =
                candidate
                    .trim()

            if (
                clean.isBlank() ||
                clean.startsWith(
                    "data:image",
                    ignoreCase = true
                )
            ) {
                continue
            }

            return fixUrlNull(
                clean
            )
        }

        val srcSet =
            image.attr(
                "srcset"
            )
                .trim()

        if (srcSet.isNotBlank()) {

            val first =
                srcSet
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    ?.substringBefore(" ")
                    ?.trim()

            if (!first.isNullOrBlank()) {
                return fixUrlNull(
                    first
                )
            }
        }

        return null
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

        return when (type) {

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

        if (jsonResults.isNotEmpty()) {
            return jsonResults
        }

        /*
         * Arama endpoint'i değişmişse en azından HTML fallback deniyoruz.
         */

        val htmlDocument =
            app.get(
                "$mainUrl/?s=$query",
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

        return htmlDocument
            .select("a[href]")
            .mapNotNull {
                it.anchorToCatalogResult(
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
                    "h1, div.card-header > h1, div.card-header > h2"
                )
                ?.text()
                ?.trim()
                ?: return null

        val poster =
            getImageUrl(
                document.selectFirst(
                    "img.img-fluid, .poster img, article img"
                )
            )

        val description =
            document
                .selectFirst(
                    """
                    article.text-white > p,
                    article p,
                    .description,
                    .film-ozeti,
                    .summary
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
                        getImageUrl(
                            it.selectFirst(
                                "img"
                            )
                        )
                    )
                }

        val isSeries =
            url.contains(
                "/dizi/",
                ignoreCase = true
            ) ||
                document
                    .select(
                        "#seasonsTabs, nav#seasonsTabs, [id*=season], [id*=sezon]"
                    )
                    .isNotEmpty() ||
                document.text()
                    .contains(
                        "Sezon",
                        ignoreCase = true
                    ) &&
                document.text()
                    .contains(
                        "Bölüm",
                        ignoreCase = true
                    )

        if (isSeries) {

            val episodes =
                mutableListOf<Episode>()

            val episodeLinks =
                document
                    .select(
                        """
                        a[href*="/sezon-"],
                        a[href*="/bolum-"],
                        a[href*="/dizi/"][href*="/sezon-"]
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

                val rawName =
                    element.text()
                        .trim()
                        .ifBlank {
                            element.parent()
                                ?.text()
                                ?.trim()
                                .orEmpty()
                        }

                val season =
                    Regex(
                        """sezon[-\s]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            href
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            """(\d+)\.?\s*Sezon""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(
                                rawName
                            )
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                val episode =
                    Regex(
                        """bolum[-\s]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            href
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            """(\d+)\.?\s*Bölüm""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(
                                rawName
                            )
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                episodes.add(
                    newEpisode(
                        href
                    ) {
                        name =
                            rawName
                                .ifBlank {
                                    buildString {

                                        if (season != null) {
                                            append(
                                                "$season. Sezon "
                                            )
                                        }

                                        if (episode != null) {
                                            append(
                                                "$episode. Bölüm"
                                            )
                                        }
                                    }
                                        .trim()
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
                    ?.takeIf {
                        it.isNotBlank()
                    }

            return newTvSeriesLoadResponse(
                title,
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

        val sourceElements =
            document
                .select(
                    """
                    nav.nav.card-nav.nav-slider a[href],
                    .card-video iframe,
                    iframe[src],
                    iframe[data-src]
                    """.trimIndent()
                )

        var foundSomething =
            false

        for (
            element in sourceElements
        ) {

            val candidate =
                when {

                    element.tagName()
                        .equals(
                            "iframe",
                            ignoreCase = true
                        ) -> {

                        element
                            .attr(
                                "data-src"
                            )
                            .ifBlank {
                                element.attr(
                                    "src"
                                )
                            }
                    }

                    else ->
                        element.attr(
                            "href"
                        )
                }
                    .trim()

            if (
                candidate.isBlank()
            ) {
                continue
            }

            val sourceUrl =
                fixUrlNull(
                    candidate
                )
                    ?: continue

            val sourceName =
                element.text()
                    .trim()
                    .ifBlank {
                        name
                    }

            safeApiCall {

                /*
                 * Önce CloudStream'in standart extractor sistemine veriyoruz.
                 * Bu, desteklenen iframe hostlarında en temiz yöntem.
                 */

                loadExtractor(
                    sourceUrl,
                    data,
                    subtitleCallback,
                    callback
                )

                /*
                 * Kaynak sayfası ise içindeki iframe'i de kontrol ediyoruz.
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

                    if (
                        invokeLocalSource(
                            sourceName,
                            iframeUrl,
                            callback
                        )
                    ) {
                        foundSomething =
                            true
                    }
                }

                if (
                    invokeLocalSource(
                        sourceName,
                        sourceUrl,
                        callback
                    )
                ) {
                    foundSomething =
                        true
                }
            }
        }

        /*
         * Bazı film sayfalarında iframe doğrudan sayfanın kendisinde olabilir.
         */

        val directIframes =
            document
                .select(
                    "iframe[data-src], iframe[src]"
                )

        for (
            iframe in directIframes
        ) {

            val iframeUrl =
                iframe
                    .attr(
                        "data-src"
                    )
                    .ifBlank {
                        iframe.attr(
                            "src"
                        )
                    }
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        fixUrlNull(
                            it
                        )
                    }
                    ?: continue

            safeApiCall {

                loadExtractor(
                    iframeUrl,
                    data,
                    subtitleCallback,
                    callback
                )

                if (
                    invokeLocalSource(
                        name,
                        iframeUrl,
                        callback
                    )
                ) {
                    foundSomething =
                        true
                }
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
