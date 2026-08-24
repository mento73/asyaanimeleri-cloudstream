package com.anihepsi.dizigom

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/*
 * Implementation references:
 * Kekik / Kraptor ecosystem
 * ahadeniz / nik-cloudstream
 *
 * Modernized for Anihepsi.
 */

@CloudstreamPlugin
class DiziGomPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(DiziGomProvider())
    }
}
