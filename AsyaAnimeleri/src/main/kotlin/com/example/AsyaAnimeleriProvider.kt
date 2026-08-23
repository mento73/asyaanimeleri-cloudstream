package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.nio.charset.Charset
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
     * Kraptor'un eski yapısındaki tür sayfalarını koruyoruz.
     * Böylece CloudStream ana sayfasında tek dev liste yerine
     * türlere göre satırlar görebiliriz.
     */
    override val mainPage = mainPageOf(
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

        val url = when {
            request.data.contains("sayfa") && page == 1 ->
                request.data
                    .replace("/page/sayfa/", "/")
                    .replace("sayfa", "1")

            request.data.contains("sayfa") ->
                request.data.replace("sayfa", page.toString())

            else -> request.data
        }

        val document = app.get(url).document

        val animeList = document
            .select("article.bs")
            .mapNotNull { it.toAnimeCard() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            animeList
        )
    }

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
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val img = selectFirst("img")

        val poster = img?.absUrl("src")
            ?.takeIf { it.isNotBlank() }
            ?: img?.absUrl("data-src")
                ?.takeIf { it.isNotBlank() }
            ?: img?.absUrl("data-lazy-src")
                ?.takeIf { it.isNotBlank() }

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            posterUrl = poster
        }
    }

    /*
     * Arama için Kraptor'un list-mode yaklaşımını koruyoruz.
     * Eşleşen animenin detay sayfasından poster de alıyoruz.
     */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {

        val document = app.get("$mainUrl/series/list-mode/").document

        val matches = document
            .select("div.soralist li a")
            .filter {
                it.text()
                    .contains(query, ignoreCase = true)
            }
            .take(30)

        val results = matches.mapNotNull { element ->

            val title = element.text().trim()
            val url = element.absUrl("href").trim()

            if (title.isBlank() || url.isBlank()) {
                return@mapNotNull null
            }

            val detailDocument = try {
                app.get(url).document
            } catch (_: Exception) {
                null
            }

            val poster = detailDocument
                ?.selectFirst("div.thumb img")
                ?.let { img ->
                    img.absUrl("src")
                        .takeIf { it.isNotBlank() }
                        ?: img.absUrl("data-src")
                            .takeIf { it.isNotBlank() }
                }

            newAnimeSearchResponse(
                title,
                url,
                TvType.Anime
            ) {
                posterUrl = poster
            }
        }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst("div.infox h1")
            ?.text()
            ?.trim()
            ?: document.selectFirst("h1")
                ?.text()
                ?.trim()
            ?: return null

        val poster = document
            .selectFirst("div.thumb img")
            ?.let { img ->
                img.absUrl("src")
                    .takeIf { it.isNotBlank() }
                    ?: img.absUrl("data-src")
                        .takeIf { it.isNotBlank() }
            }
            ?: document
                .selectFirst("meta[property='og:image']")
                ?.attr("content")
                ?.trim()

        val description = document
            .selectFirst("div.entry-content b")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("meta[property='og:description']")
                ?.attr("content")
                ?.trim()

        val year = document
    .select(".spe span")
    .map { it.text().trim() }
    .firstOrNull { it.contains("Yayın Yılı:", ignoreCase = true) }
    ?.let {
        Regex("""(19|20)\d{2}""")
            .find(it)
            ?.value
            ?.toIntOrNull()
    }

        /*
         * Genre linklerini doğrudan alıyoruz.
         * nth-child yapısına göre daha dayanıklı.
         */
        val tags = document
            .select("a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val duration = document
            .select(".spe span")
            .firstOrNull {
                it.text().contains("Dakika", ignoreCase = true)
            }
            ?.text()
            ?.let {
                Regex("""\d+""")
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }

        /*
         * Kraptor'un eplister bölüm yapısını önce kullanıyoruz.
         */
        val structuredEpisodes = document
            .select("div.eplister ul li")
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
                    .selectFirst("div.epl-num")
                    ?.text()
                    ?.trim()

                val rawTitle = episodeElement
                    .selectFirst("div.epl-title")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                 val episodePoster = episodeElement
                    .selectFirst("img")
                    ?.let { img ->
                    img.absUrl("src")
                   .takeIf { it.isNotBlank() }
                    ?: img.absUrl("data-src")
                   .takeIf { it.isNotBlank() }
                   ?: img.absUrl("data-lazy-src")
                   .takeIf { it.isNotBlank() }
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
                        Regex("""(?i)\s*-\s*izle"""),
                        ""
                    )
                    .trim()
                    .replace(
                        Regex("""\s+"""),
                        " "
                    )

                newEpisode(episodeUrl) {
    episode = episodeNumber
    posterUrl = episodePoster

    name = when {
        cleanedTitle.isNotBlank() ->
            cleanedTitle

        episodeNumber != null ->
            "Bölüm $episodeNumber"

        else ->
            numberText ?: "Bölüm"
    }
}
            }
            .distinctBy { it.data }

        /*
         * Bazı seri sayfalarında eplister yapısı olmayabilir.
         * O durumda daha önce bizde çalışan genel Bölüm linki
         * yöntemine geri düşüyoruz.
         */
        val episodes = if (structuredEpisodes.isNotEmpty()) {
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
                        episodeUrl.contains("/series/")
                    ) {
                        return@mapNotNull null
                    }

                    val number = Regex("""\d+""")
                        .find(text)
                        ?.value
                        ?.toIntOrNull()

                    newEpisode(episodeUrl) {
                        name = text
                        episode = number
                    }
                }
                .distinctBy { it.data }
        }

        val recommendations = document
            .select("article.bs")
            .mapNotNull { it.toAnimeCard() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(20)

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags

            if (duration != null) {
                this.duration = duration
            }

            this.recommendations = recommendations

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )
        }
    }

    /*
     * Kraptor'un mirror mantığını güncel sayfalar için
     * biraz genişletiyoruz. OK.ru bizde siyah ekran verdiği
     * için şimdilik bilinçli olarak atlanıyor.
     */
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val options = document.select(
            "select.mirror option[value], select option[value]"
        )

        var found = false

        options.forEach { option ->

            try {
                val encodedHtml = option
                    .attr("value")
                    .trim()

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
                    .replace(
                        Regex("""^//"""),
                        "https://"
                    )
                    .replace(
                        """\/""",
                        "/"
                    )
                    .let { fixUrl(it) }

                if (
                    cleanUrl.contains(
                        "ok.ru",
                        ignoreCase = true
                    ) ||
                    cleanUrl.contains(
                        "odnoklassniki",
                        ignoreCase = true
                    )
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

        /*
         * Bazı eski sayfalarda doğrudan iframe bulunabilir.
         * Mirror listesinde bulunmayan destekli hostları da
         * kaybetmeyelim.
         */
        document
            .select("iframe[src]")
            .map { it.absUrl("src") }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot {
                it.contains("ok.ru", true) ||
                it.contains("odnoklassniki", true)
            }
            .forEach { iframeUrl ->

                try {
                    val success = loadExtractor(
                        url = iframeUrl,
                        referer = data,
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
