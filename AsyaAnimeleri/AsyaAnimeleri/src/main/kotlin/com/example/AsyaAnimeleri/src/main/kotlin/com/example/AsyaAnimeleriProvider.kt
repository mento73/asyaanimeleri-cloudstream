package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

class AsyaAnimeleriProvider : MainAPI() {

    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override val hasMainPage = true
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )
}
