package com.anihepsi.dizipal

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

// Original DiziPal provider by @keyiflerolsun / KekikAkademi.
// Modernized for the Anihepsi CloudStream repository.

class DiziPalProvider : MainAPI() {

    override var mainUrl = "https://dizipal1577.com"
    override var name = "DiziPal"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
    "$mainUrl/yeni-eklenen-dizi-bolumler" to "Son Bölümler",
    "$mainUrl/" to "Ana Sayfa"
)

    override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    val document = app.get(request.data).document

    val home = document
        .select("a[href*='/series/']")
        .mapNotNull { element ->

            val href = fixUrlNull(
                element.attr("href")
            ) ?: return@mapNotNull null

            val title = element
                .text()
                .trim()
                .takeIf { it.isNotBlank() }
                ?: element
                    .selectFirst("img")
                    ?.attr("alt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = element
                .selectFirst("img")
                ?.let { img ->
                    img.attr("src")
                        .takeIf { it.isNotBlank() }
                        ?: img.attr("data-src")
                            .takeIf { it.isNotBlank() }
                }
                ?.let { fixUrlNull(it) }

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
        .distinctBy { it.url }

    return newHomePageResponse(
        request.name,
        home,
        hasNext = false
    )
}

    private fun Element.toLatestEpisode(): SearchResponse? {

        val name =
            selectFirst("div.name")
                ?.text()
                ?.trim()
                ?: return null

        val episodeText =
            selectFirst("div.episode")
                ?.text()
                ?.trim()
                ?: return null

        val episode =
            episodeText
                .replace(". Sezon ", "x")
                .replace(". Bölüm", "")

        val href =
            fixUrlNull(
                selectFirst("a")?.attr("href")
            ) ?: return null

        val poster =
            fixUrlNull(
                selectFirst("img")?.attr("src")
            )

        return newTvSeriesSearchResponse(
            "$name $episode",
            href.substringBefore("/sezon"),
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title =
            selectFirst("span.title")
                ?.text()
                ?.trim()
                ?: return null

        val href =
            fixUrlNull(
                selectFirst("a")?.attr("href")
            ) ?: return null

        val poster =
            fixUrlNull(
                selectFirst("img")?.attr("src")
            )

        return if (href.contains("/dizi/")) {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response = app.post(
            "$mainUrl/api/search-autocomplete",
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/",
            data = mapOf(
                "query" to query
            )
        )

        val items =
            jacksonObjectMapper()
                .readValue<Map<String, SearchItem>>(response.text)

        return items.values.map { item ->

            val href = "$mainUrl${item.url}"

            if (item.type == "series") {

                newTvSeriesSearchResponse(
                    item.title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = item.poster
                }

            } else {

                newMovieSearchResponse(
                    item.title,
                    href,
                    TvType.Movie
                ) {
                    posterUrl = item.poster
                }
            }
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> =
        search(query)

    override suspend fun load(
    url: String
): LoadResponse? {

    val cleanUrl = if (url.startsWith("http")) {
        url
    } else {
        fixUrl(url)
    }

    val document = try {
        app.get(
            cleanUrl,
            headers = mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Safari/537.36",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            ),
            referer = "$mainUrl/"
        ).document
    } catch (_: Exception) {
        return null
    }

    val title = document
        .selectFirst("h1")
        ?.text()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: document
            .selectFirst("[property='og:title']")
            ?.attr("content")
            ?.substringBefore("1080P")
            ?.trim()
        ?: return null

    val poster = document
        .selectFirst("[property='og:image']")
        ?.attr("content")
        ?.takeIf { it.isNotBlank() }
        ?.let { fixUrlNull(it) }
        ?: document
            .selectFirst("img")
            ?.let { img ->
                img.attr("src")
                    .takeIf { it.isNotBlank() }
                    ?: img.attr("data-src")
                        .takeIf { it.isNotBlank() }
            }
            ?.let { fixUrlNull(it) }

    val description = document
        .selectFirst("[property='og:description']")
        ?.attr("content")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: document
            .selectFirst("div.summary p")
            ?.text()
            ?.trim()

    val year = Regex("""\b(19|20)\d{2}\b""")
        .find(document.text())
        ?.value
        ?.toIntOrNull()

    /*
     * Current DiziPal series pages use /series/ URLs.
     */
    if (cleanUrl.contains("/series/")) {

        val episodes = document
            .select("a[href*='/bolum/']")
            .mapNotNull { element ->

                val episodeUrl = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
                    ?: return@mapNotNull null

                val text = element
                    .text()
                    .trim()
                    .ifBlank {
                        element.attr("title").trim()
                    }

                val seasonEpisode = Regex(
                    """(\d+)\.?\s*Sezon\s+(\d+)\.?\s*Bölüm""",
                    RegexOption.IGNORE_CASE
                ).find(text)

                val seasonNumber = seasonEpisode
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                val episodeNumber = seasonEpisode
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()

                newEpisode(episodeUrl) {
                    name = text
                        .takeIf { it.isNotBlank() }
                        ?: buildString {
                            if (seasonNumber != null) {
                                append("$seasonNumber. Sezon ")
                            }

                            if (episodeNumber != null) {
                                append("$episodeNumber. Bölüm")
                            }
                        }
                        .ifBlank { "Bölüm" }

                    this.season = seasonNumber
                    this.episode = episodeNumber
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode>(
                    { it.season ?: 0 },
                    { it.episode ?: 0 }
                )
            )

        return newTvSeriesLoadResponse(
            title,
            cleanUrl,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot = description
            this.year = year
        }
    }

    /*
     * Everything that is not a series is treated as a movie for now.
     */
    return newMovieLoadResponse(
        title,
        cleanUrl,
        TvType.Movie,
        cleanUrl
    ) {
        posterUrl = poster
        plot = description
        this.year = year
    }
}

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

    val iframeUrl = document
        .selectFirst("iframe[src]")
        ?.attr("src")
        ?.takeIf { it.isNotBlank() }
        ?.let { fixUrl(it) }

    if (iframeUrl != null) {
        return loadExtractor(
            iframeUrl,
            data,
            subtitleCallback,
            callback
        )
    }

    return false
}
 }   
