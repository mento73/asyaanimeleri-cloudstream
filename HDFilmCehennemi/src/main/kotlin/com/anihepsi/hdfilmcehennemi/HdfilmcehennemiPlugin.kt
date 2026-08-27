package com.anihepsi.hdfilmcehennemi

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/*
 * Original Hdfilmcehennemi implementation:
 * Hexated / cloudstream-extensions-multilingual
 *
 * Adapted for the Anihepsi CloudStream repository.
 */

@CloudstreamPlugin
class HdfilmcehennemiPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(
            HdfilmcehennemiProvider()
        )
    }
}
