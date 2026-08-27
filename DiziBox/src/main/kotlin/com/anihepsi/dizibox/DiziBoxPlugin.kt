package com.anihepsi.dizibox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/*
 * DiziBox provider adapted for the Anihepsi CloudStream repository.
 *
 * References / attribution:
 * keyiflerolsun / Kekik-cloudstream
 * nikyokki / nik-cloudstream
 *
 * Player handling here only follows publicly exposed iframe URLs
 * and standard Base64 URL encoding.
 */

@CloudstreamPlugin
class DiziBoxPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(DiziBoxProvider())
    }
}
