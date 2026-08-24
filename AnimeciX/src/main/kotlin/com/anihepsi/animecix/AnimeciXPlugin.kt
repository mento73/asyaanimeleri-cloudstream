package com.anihepsi.animecix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeciXPlugin : BasePlugin() {

    override fun load() {
    registerMainAPI(AnimeciXProvider())
    registerExtractorAPI(TauVideo())
    registerExtractorAPI(TauVideo2())
}
}
