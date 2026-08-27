package com.anihepsi.dizibox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/*
 * DiziBox provider structure adapted for Anihepsi.
 *
 * Original Turkish CloudStream ecosystem references:
 * keyiflerolsun / Kekik-cloudstream
 * nikyokki / nik-cloudstream
 *
 * Player handling in this adaptation only follows openly exposed
 * iframe URLs and standard Base64 URL encoding from the public page.
 */

@CloudstreamPlugin
class DiziBoxPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(
            DiziBoxProvider()
        )
    }
}
