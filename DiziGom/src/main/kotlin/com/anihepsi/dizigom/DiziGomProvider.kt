package com.anihepsi.dizigom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/*
 * Original provider lineage / implementation references:
 * Kekik / Kraptor ecosystem
 * ahadeniz / nik-cloudstream
 *
 * Modernized for the Anihepsi CloudStream repository.
 *
 * The current DiziGom DOM and player flow differ from older implementations,
 * therefore this provider is adapted for the current site rather than copied
 * verbatim from older sources.
 */

class DiziGomProvider : MainAPI() {

    override var mainUrl = "https://www.dizigom.love"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Diziler",
        "$mainUrl/dizi-izle/" to "Tüm Diziler"
    )

    private val episodeRegex =
        Regex("""-(\d+)-sezon-(\d+)-bolum/?(?:\?.*)?$""", RegexOption.IGNORE_CASE)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (request.name == "Tüm Diziler" && page > 1) {
            "$mainUrl/dizi-izle/page/$page/"
        } else {
            request.data
        }

        val document = app.get(url).document

        val results = if (request.name == "Tüm Diziler") {
            parseAllSeries(document)
        } else {
            parseLatestSeries(document)
        }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = request.name == "Tüm Diziler"
        )
    }

    private fun parseLatestSeries(document: org.jsoup.nodes.Document): List<SearchResponse> {

        return document
            .select("a[href*='/diziler/'] > img[src]")
            .mapNotNull { img ->

                val anchor = img.parent() ?: return@mapNotNull null

                val href = fixUrlNull(anchor.attr("href"))
                    ?: return@mapNotNull null

                val title = img.attr("title")
                    .ifBlank { img.attr("alt") }
                    .trim()

                if (title.isBlank()) return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = fixUrlNull(img.attr("src"))
                }
            }
            .distinctBy { it.url }
    }

    private fun parseAllSeries(document: org.jsoup.nodes.Document): List<SearchResponse> {

        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { link ->

                val href = fixUrlNull(link.attr("href"))
                    ?: return@mapNotNull null

                val title = link.text()
                    .ifBlank { link.attr("title") }
                    .trim()

                if (title.isBlank()) return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                )
            }
            .distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val encoded = URLEncoder.encode(query, "UTF-8")

        val document = app.get(
            "$mainUrl/?s=$encoded"
        ).document

        val posterResults = parseLatestSeries(document)

        if (posterResults.isNotEmpty()) {
            return posterResults
        }

        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { link ->

                val href = fixUrlNull(link.attr("href"))
                    ?: return@mapNotNull null

                val title = link.text()
                    .ifBlank { link.attr("title") }
                    .trim()

                if (title.isBlank()) return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                )
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title =
            document.selectFirst("h1")?.text()?.trim()
                ?: document
                    .selectFirst("meta[property='og:title']")
                    ?.attr("content")
                    ?.trim()
                ?: "DiziGom"

        val poster =
            document
                .selectFirst("meta[property='og:image']")
                ?.attr("content")
                ?.let(::fixUrlNull)
                ?: document
                    .selectFirst("img[src]")
                    ?.attr("src")
                    ?.let(::fixUrlNull)

        val description =
            document
                .selectFirst("meta[property='og:description']")
                ?.attr("content")
                ?.trim()
                ?: document
                    .selectFirst("meta[name='description']")
                    ?.attr("content")
                    ?.trim()

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(document.text())
            ?.value
            ?.toIntOrNull()

        val genres = document
            .select(
                "a[href*='/tur/'], " +
                    "a[href*='/kategori/'], " +
                    "a[href*='/genre/']"
            )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        /*
         * Episode construction intentionally follows the clean newEpisode()
         * structure used by nik-cloudstream:
         *
         * URL -> newEpisode()
         * season -> separate field
         * episode -> separate field
         *
         * This gives CloudStream proper episode metadata instead of treating
         * "1. Sezon 3. Bölüm" as one opaque string.
         */
        val episodes = document
            .select(
                ".otherepisodes > a[href], " +
                    "#genel .otherepisodes > a[href], " +
                    "a[href*='-sezon-'][href*='-bolum']"
            )
            .mapNotNull { link ->

                val episodeUrl = fixUrlNull(link.attr("href"))
                    ?: return@mapNotNull null

                val match = episodeRegex.find(episodeUrl)
                    ?: return@mapNotNull null

                val seasonNumber =
                    match.groupValues[1].toIntOrNull()

                val episodeNumber =
                    match.groupValues[2].toIntOrNull()

                val visibleName = link
                    .selectFirst(".epidosename")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                newEpisode(episodeUrl) {

                    /*
                     * Keep a short episode name.
                     * Season/episode numbers are already stored separately.
                     */
                    name = if (visibleName.isNotBlank()) {
                        visibleName
                    } else {
                        "Bölüm ${episodeNumber ?: ""}".trim()
                    }

                    season = seasonNumber
                    episode = episodeNumber
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
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot = description
            this.year = year
            tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        /*
         * Step 1:
         * Get the actual DiziGom episode page.
         */
        val episodeDocument = app.get(
            data,
            referer = "$mainUrl/"
        ).document

        /*
         * Current DiziGom embeds Pilavyer inside the video container.
         */
        val iframeUrls = episodeDocument
            .select(
                ".video-container iframe[src], " +
                    ".video iframe[src], " +
                    "iframe[src*='pilavyer']"
            )
            .mapNotNull {
                fixUrlNull(it.attr("src").trim())
            }
            .distinct()

        if (iframeUrls.isEmpty()) {
            return false
        }

        var foundLink = false

        for (iframeUrl in iframeUrls) {

            /*
             * Step 2:
             * Read the player normally with the real DiziGom episode as
             * Referer. We do NOT disable anti-debugging or fabricate tokens.
             */
            runCatching {

                val playerResponse = app.get(
                    iframeUrl,
                    referer = data
                )

                /*
                 * Some players expose an ordinary HLS URL directly in their
                 * HTML/JS configuration. If Pilavyer ever does so, use it.
                 *
                 * "\\/" is normalized because JS configs commonly escape URLs.
                 */
                val playerHtml =
                    playerResponse.text.replace("\\/", "/")

                val directM3u8 = Regex(
                    """https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""",
                    RegexOption.IGNORE_CASE
                )
                    .find(playerHtml)
                    ?.value

                if (directM3u8 != null) {

                    callback.invoke(
                        newExtractorLink(
                            source = "DiziGom",
                            name = "DiziGom",
                            url = directM3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = iframeUrl
                            quality = getQualityFromName("Auto")
                        }
                    )

                    foundLink = true
                }

            }

            /*
             * Step 3:
             * Standard CloudStream extractor fallback.
             *
             * This is kept because Pilavyer support may later be added to
             * CloudStream itself or provided by another registered extractor.
             */
            runCatching {

                loadExtractor(
                    url = iframeUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

            }
        }

        /*
         * Returning true tells CloudStream that a player page was found.
         * It does NOT mean we bypassed Pilavyer's protected player.
         */
        return foundLink || iframeUrls.isNotEmpty()
    }
}
