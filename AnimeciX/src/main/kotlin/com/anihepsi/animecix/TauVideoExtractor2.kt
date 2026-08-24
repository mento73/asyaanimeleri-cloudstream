package com.anihepsi.animecix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink

// Original TauVideo2 extractor by the Kekik/Kraptor ecosystem.
// Modernized for the Anihepsi CloudStream repository.

class TauVideo2 : ExtractorApi() {

    override val name = "TauVideo2"
    override val mainUrl = "https://fang-heshan.store"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast("/").substringBefore("?")

        val response = app.get(
            "$mainUrl/file/tau-video/$videoId",
            referer = referer ?: url
        )

        val finalUrl = response.url

        if (finalUrl.isNotBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = referer ?: url
                }
            )
        }
    }
}
