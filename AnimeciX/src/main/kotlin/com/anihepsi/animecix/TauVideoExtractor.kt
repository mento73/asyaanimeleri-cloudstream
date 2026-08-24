// Original TauVideo extractor by @keyiflerolsun / KekikAkademi.
// Modernized for the Anihepsi CloudStream repository.
package com.anihepsi.animecix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class TauVideo : ExtractorApi() {

    override val name = "TauVideo"
    override val mainUrl = "https://tau-video.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""
        val videoKey = url.substringAfterLast("/")
        val videoUrl = "$mainUrl/api/video/$videoKey"

        val api = app.get(videoUrl)
            .parsedSafe<TauVideoUrls>()
            ?: throw ErrorLoadingException("TauVideo")

        api.urls.forEach { video ->
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = video.url,
                    type = INFER_TYPE
                ) {
                    headers = mapOf("Referer" to extRef)
                    quality = getQualityFromName(video.label)
                }
            )
        }
    }

    data class TauVideoUrls(
        @JsonProperty("urls")
        val urls: List<TauVideoData>
    )

    data class TauVideoData(
        @JsonProperty("url")
        val url: String,

        @JsonProperty("label")
        val label: String
    )
}
