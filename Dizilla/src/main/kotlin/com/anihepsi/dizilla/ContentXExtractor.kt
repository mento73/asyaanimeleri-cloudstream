// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.
// Adapted for Anihepsi while preserving original attribution.

package com.anihepsi.dizilla

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class ContentX : ExtractorApi() {

    override val name = "ContentX"
    override val mainUrl = "https://contentx.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val extRef = referer ?: ""

        Log.d(
            "Dizilla",
            "ContentX url » $url"
        )

        val iSource = app.get(
            url,
            referer = extRef
        ).text

        val iExtract = Regex(
            """window\.openPlayer\('([^']+)'"""
        )
            .find(iSource)
            ?.groups
            ?.get(1)
            ?.value
            ?: throw ErrorLoadingException(
                "ContentX player id bulunamadı"
            )

        /*
         * Altyazılar
         */
        val subUrls =
            mutableSetOf<String>()

        Regex(
            """"file":"((?:\\\"|[^"])+)","label":"((?:\\\"|[^"])+)""""
        )
            .findAll(iSource)
            .forEach { match ->

                val (
                    subUrlRaw,
                    subLangRaw
                ) = match.destructured

                val subUrl = subUrlRaw
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\", "")

                val subLang =
                    decodeSubtitleLanguage(
                        subLangRaw
                    )

                if (
                    subUrl.isBlank() ||
                    subUrl in subUrls
                ) {
                    return@forEach
                }

                subUrls.add(subUrl)

                val cleanSubtitleUrl =
                    normalizeUrl(subUrl)

                if (cleanSubtitleUrl != null) {

                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = subLang,
                            url = cleanSubtitleUrl
                        )
                    )
                }
            }

        /*
         * Ana video
         */
        val vidSource = app.get(
            "$mainUrl/source2.php?v=$iExtract",
            referer = extRef
        ).text

        val vidExtract = Regex(
            """"file":"([^"]+)""""
        )
            .find(vidSource)
            ?.groups
            ?.get(1)
            ?.value
            ?: throw ErrorLoadingException(
                "ContentX video bağlantısı bulunamadı"
            )

        val m3uLink = vidExtract
            .replace("\\", "")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3uLink,
                type = ExtractorLinkType.M3U8
            ) {
                headers =
                    playerHeaders(url)

                quality =
                    Qualities.Unknown.value
            }
        )

        /*
         * Türkçe dublaj varsa ikinci kaynak.
         */
        val iDublaj = Regex(
            ""","([^']+)","Türkçe""""
        )
            .find(iSource)
            ?.groups
            ?.get(1)
            ?.value

        if (iDublaj != null) {

            val dublajSource = app.get(
                "$mainUrl/source2.php?v=$iDublaj",
                referer = extRef
            ).text

            val dublajExtract = Regex(
                """"file":"([^"]+)""""
            )
                .find(dublajSource)
                ?.groups
                ?.get(1)
                ?.value

            if (dublajExtract != null) {

                val dublajLink =
                    dublajExtract
                        .replace("\\", "")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name =
                            "$name Türkçe Dublaj",
                        url = dublajLink,
                        type =
                            ExtractorLinkType.M3U8
                    ) {
                        headers =
                            playerHeaders(url)

                        quality =
                            Qualities.Unknown.value
                    }
                )
            }
        }
    }

    private fun normalizeUrl(
        rawUrl: String
    ): String? {

        val cleaned = rawUrl
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (cleaned.isBlank()) {
            return null
        }

        return when {

            cleaned.startsWith(
                "https://"
            ) ||
                cleaned.startsWith(
                    "http://"
                ) ->
                cleaned

            cleaned.startsWith(
                "//"
            ) ->
                "https:$cleaned"

            cleaned.startsWith(
                "/"
            ) ->
                "$mainUrl$cleaned"

            else ->
                "$mainUrl/$cleaned"
        }
    }

    private fun playerHeaders(
        refererUrl: String
    ): Map<String, String> {

        return mapOf(
            "Referer" to refererUrl,

            "User-Agent" to
                "Mozilla/5.0 " +
                "(Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 " +
                "(KHTML, like Gecko) " +
                "Chrome/124.0.0.0 " +
                "Safari/537.36"
        )
    }

    private fun decodeSubtitleLanguage(
        raw: String
    ): String {

        return raw
            .replace("\\u0131", "ı")
            .replace("\\u0130", "İ")
            .replace("\\u00fc", "ü")
            .replace("\\u00e7", "ç")
            .replace("\\u011f", "ğ")
            .replace("\\u015f", "ş")
            .replace("\\\"", "\"")
            .trim()
    }
}
