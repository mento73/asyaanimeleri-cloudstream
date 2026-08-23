package com.example
import java.nio.charset.Charset
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class AsyaAnimeleriProvider : MainAPI() {

    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val hasMainPage = true

    override val mainPage = mainPageOf(
    "$mainUrl/series/" to "Tüm Animeler"
)

    override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    val document = app.get(request.data).document

    val animeList = document
        .select("article.bs")
        .mapNotNull { element ->

            val title = element
                .selectFirst("div.tt.tts")
                ?.text()
                ?.trim()
                ?: return@mapNotNull null

            val url = element
                .selectFirst("a")
                ?.absUrl("href")
                ?.trim()
                ?: return@mapNotNull null

            val poster = element
                .selectFirst("img")
                ?.absUrl("src")

            newAnimeSearchResponse(
                title,
                url,
                TvType.Anime
            ) {
                posterUrl = poster
            }
        }

    return newHomePageResponse(
        listOf(
            HomePageList(
                "Tüm Animeler",
                animeList
            )
        )
    )
}

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {

        val document = app.get("$mainUrl/series/").document

        val results = document
            .select("a[href*='/series/']")
            .mapNotNull { element ->

                val title = element.text().trim()
                val url = element.absUrl("href")

                if (
                    title.isBlank() ||
                    url.isBlank() ||
                    url.contains("list-mode") ||
                    !title.contains(query, ignoreCase = true)
                ) {
                    null
                } else {
                    newAnimeSearchResponse(
                        title,
                        url,
                        TvType.Anime
                    )
                }
            }
            .distinctBy { it.url }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")

        val year = document
    .selectFirst("span.split:nth-child(3)")
    ?.text()
    ?.trim()
    ?.toIntOrNull()

        val tags = document
    .select(".spe > span:nth-child(7) a")
    .map { it.text().trim() }
    .filter { it.isNotBlank() }

       val rating = document
    .selectFirst("div.rating")
    ?.text()
    ?.trim()
    ?.toRatingInt()

        val episodes = document
            .select("a")
            .mapNotNull { element ->

                val episodeUrl = element.absUrl("href")
                val text = element.text().trim()

                if (
                    episodeUrl.isBlank() ||
                    text.isBlank() ||
                    !text.contains("Bölüm", ignoreCase = true) ||
                    episodeUrl.contains("/series/")
                ) {
                    null
                } else {

                    val number = Regex("""(\d+)""")
                        .find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                    newEpisode(episodeUrl) {
                        name = text
                        episode = number
                    }
                }
            }
            .distinctBy { it.data }

        return newAnimeLoadResponse(
    title,
    url,
    TvType.Anime
) {
    posterUrl = poster
    plot = description
    this.year = year
    this.tags = tags
    this.rating = rating

    addEpisodes(
        DubStatus.Subbed,
        episodes
    )
}
    }

@OptIn(ExperimentalEncodingApi::class)
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val document = app.get(data).document

    val options = document.select(
        "select option[value]"
    )

    var found = false

    options.forEach { option ->

        try {
            val encodedHtml = option.attr("value")

            if (encodedHtml.isBlank()) {
                return@forEach
            }

            val decodedHtml = Base64
                .decode(encodedHtml)
                .toString(Charset.defaultCharset())

            val iframeSrc = Regex(
                """src=["'](.*?)["']"""
            )
                .find(decodedHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: return@forEach

            val cleanUrl = iframeSrc
                .replace(Regex("""^//"""), "https://")
                .replace("""\/""", "/")
                .let { fixUrl(it) }

            // OK.ru şu anda siyah ekran verdiği için atlıyoruz.
            if (
                cleanUrl.contains("ok.ru", ignoreCase = true) ||
                cleanUrl.contains("odnoklassniki", ignoreCase = true)
            ) {
                return@forEach
            }

            val success = loadExtractor(
                url = cleanUrl,
                referer = "$mainUrl/",
                subtitleCallback = subtitleCallback,
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
}
