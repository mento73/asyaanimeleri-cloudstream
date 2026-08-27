package com.anihepsi.dizibox

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/*
 * DiziBox provider adapted for the Anihepsi CloudStream repository.
 *
 * References / attribution:
 * keyiflerolsun / Kekik-cloudstream
 * nikyokki / nik-cloudstream
 *
 * DEBUG VERSION
 */

class DiziBoxProvider : MainAPI() {

    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBox"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi/" to "Tüm Diziler"
    )

    private val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to
            "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    /*
     * ============================================================
     * DEBUG ANA SAYFA
     * ============================================================
     *
     * Şimdilik gerçek dizileri göstermiyoruz.
     * CloudStream'ın DiziBox'tan ne aldığını ekranda gösteriyoruz.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return try {

            val url =
                if (page <= 1) {
                    request.data
                } else {
                    "${request.data.trimEnd('/')}/page/$page/"
                }

            val response = app.get(
                url,
                headers = headers,
                referer = "$mainUrl/"
            )

            val document = response.document

            val cardCount =
                document.select(
                    ".col-1 article.detailed-article"
                ).size

            val archiveCount =
                document.select(
                    "ul.alphabetical-category-list a[href*=\"/diziler/\"]"
                ).size

            val genericCards =
                document.select(
                    "article.detailed-article"
                ).size

            val debugResults = listOf(

                newTvSeriesSearchResponse(
                    "DEBUG HTTP: ${response.code}",
                    "$mainUrl/dizi-arsivi/",
                    TvType.TvSeries
                ),

                newTvSeriesSearchResponse(
                    "DEBUG Başlık: ${document.title()}",
                    "$mainUrl/dizi-arsivi/",
                    TvType.TvSeries
                ),

                newTvSeriesSearchResponse(
                    "DEBUG Kart: $cardCount",
                    "$mainUrl/dizi-arsivi/",
                    TvType.TvSeries
                ),

                newTvSeriesSearchResponse(
                    "DEBUG Tüm Kartlar: $genericCards",
                    "$mainUrl/dizi-arsivi/",
                    TvType.TvSeries
                ),

                newTvSeriesSearchResponse(
                    "DEBUG Arşiv: $archiveCount",
                    "$mainUrl/dizi-arsivi/",
                    TvType.TvSeries
                )
            )

            newHomePageResponse(
                "Tüm Diziler",
                debugResults,
                hasNext = false
            )

        } catch (e: Exception) {

            newHomePageResponse(
                "Tüm Diziler",
                listOf(

                    newTvSeriesSearchResponse(
                        "DEBUG HATA: ${e.javaClass.simpleName}",
                        "$mainUrl/",
                        TvType.TvSeries
                    ),

                    newTvSeriesSearchResponse(
                        "MESAJ: ${e.message ?: "boş"}",
                        "$mainUrl/",
                        TvType.TvSeries
                    )
                ),
                hasNext = false
            )
        }
    }

    /*
     * ============================================================
     * DEBUG ARAMA
     * ============================================================
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

        return try {

            val response = app.get(
                "$mainUrl/dizi-arsivi/",
                headers = headers,
                referer = "$mainUrl/"
            )

            val document = response.document

            val archiveLinks =
                document.select(
                    "ul.alphabetical-category-list a[href*=\"/diziler/\"]"
                )

            /*
             * Önce gerçek aramayı deneyelim.
             */
            val normalizedQuery =
                query
                    .trim()
                    .lowercase()

            val realResults =
                archiveLinks
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

            /*
             * Sonuç bulduysa normal sonucu dön.
             */
            if (realResults.isNotEmpty()) {
                return realResults
            }

            /*
             * Sonuç YOKSA debug kartlarını göster.
             *
             * Böylece DiziBox sekmesi boş kalmak yerine
             * CloudStream'ın ne gördüğünü anlayacağız.
             */
            listOf(

                newTvSeriesSearchResponse(
                    "DEBUG SEARCH HTTP: ${response.code}",
                    "$mainUrl/",
                    TvType.TvSeries
                ),

                newTvSeriesSearchResponse(
                    "DEBUG SEARCH Başlık: ${document.title()}",
                    "$mainUrl/",
                    TvType.TvSeries
                ),

                newTvSeriesSearchResponse(
                    "DEBUG SEARCH Arşiv: ${archiveLinks.size}",
                    "$mainUrl/",
                    TvType.TvSeries
                )
            )

        } catch (e: Exception) {

            listOf(

                newTvSeriesSearchResponse(
                    "DEBUG SEARCH HATA: ${e.javaClass.simpleName}",
                    "$mainUrl/",
                    TvType.TvSeries
                ),

                newTvSeriesSearchResponse(
                    "MESAJ: ${e.message ?: "boş"}",
                    "$mainUrl/",
                    TvType.TvSeries
                )
            )
        }
    }

    /*
     * ============================================================
     * NORMAL KART DÖNÜŞTÜRÜCÜ
     * ============================================================
     *
     * Sonraki aşamada kullanacağız.
     */
    private fun Element.toDetailedSearchResult(): SearchResponse? {

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

    private fun getImageUrl(
        image: Element
    ): String? {

        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("src")
        )

        for (candidate in candidates) {

            val value = candidate.trim()

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
     * DETAY SAYFASI - DEBUG İÇİN YETERLİ
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

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            emptyList()
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
}
