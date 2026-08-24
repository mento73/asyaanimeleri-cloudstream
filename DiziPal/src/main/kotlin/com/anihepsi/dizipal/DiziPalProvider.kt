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

        val document = app.get(url).document

        val poster =
            fixUrlNull(
                document
                    .selectFirst("[property='og:image']")
                    ?.attr("content")
            )

        val year =
            document
                .selectXpath(
                    "//div[text()='Yapım Yılı']//following-sibling::div"
                )
                .text()
                .trim()
                .toIntOrNull()

        val description =
            document
                .selectFirst("div.summary p")
                ?.text()
                ?.trim()

        val tags =
            document
                .selectXpath(
                    "//div[text()='Türler']//following-sibling::div"
                )
                .text()
                .trim()
                .split(" ")
                .map { it.trim() }
                .filter { it.isNotBlank() }

        val duration =
            Regex("""\d+""")
                .find(
                    document
                        .selectXpath(
                            "//div[text()='Ortalama Süre']//following-sibling::div"
                        )
                        .text()
                )
                ?.value
                ?.toIntOrNull()

        return if (url.contains("/dizi/")) {

            val title =
                document
                    .selectFirst("div.cover h5")
                    ?.text()
                    ?.trim()
                    ?: return null

            val episodes =
                document
                    .select("div.episode-item")
                    .mapNotNull { element ->

                        val episodeName =
                            element
                                .selectFirst("div.name")
                                ?.text()
                                ?.trim()
                                ?: return@mapNotNull null

                        val episodeUrl =
                            fixUrlNull(
                                element
                                    .selectFirst("a")
                                    ?.attr("href")
                            ) ?: return@mapNotNull null

                        val info =
                            element
                                .selectFirst("div.episode")
                                ?.text()
                                ?.trim()
                                ?.split(" ")

                        val season =
                            info
                                ?.getOrNull(0)
                                ?.replace(".", "")
                                ?.toIntOrNull()

                        val episode =
                            info
                                ?.getOrNull(2)
                                ?.replace(".", "")
                                ?.toIntOrNull()

                        newEpisode(episodeUrl) {
                            name = episodeName
                            this.season = season
                            this.episode = episode
                        }
                    }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration
            }

        } else {

            val title =
                document
                    .selectXpath(
                        "//div[@class='g-title'][2]/div"
                    )
                    .text()
                    .trim()

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val iframe =
            document
                .selectFirst(".series-player-container iframe")
                ?.attr("src")
                ?: document
                    .selectFirst("div#vast_new iframe")
                    ?.attr("src")
                ?: return false

        val iframeUrl = fixUrl(iframe)

        val source =
            app.get(
                iframeUrl,
                referer = "$mainUrl/"
            ).text

        val m3u8 =
            Regex("""file:"([^"]+)"""")
                .find(source)
                ?.groupValues
                ?.getOrNull(1)

        if (m3u8 == null) {
            return loadExtractor(
                iframeUrl,
                "$mainUrl/",
                subtitleCallback,
                callback
            )
        }

        val subtitles =
            Regex(""""subtitle":"([^"]+)"""")
                .find(source)
                ?.groupValues
                ?.getOrNull(1)

        subtitles
            ?.split(",")
            ?.forEach { subtitle ->

                val language =
                    subtitle
                        .substringAfter("[")
                        .substringBefore("]")

                val subtitleUrl =
                    subtitle.replace(
                        "[$language]",
                        ""
                    )

                if (subtitleUrl.isNotBlank()) {
                    subtitleCallback(
                        SubtitleFile(
                            language,
                            fixUrl(subtitleUrl)
                        )
                    )
                }
            }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = INFER_TYPE
            ) {
                referer = "$mainUrl/"
            }
        )

        return true
    }
}
