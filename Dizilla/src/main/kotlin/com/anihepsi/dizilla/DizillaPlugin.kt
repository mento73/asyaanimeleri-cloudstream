package com.anihepsi.dizilla

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/*
 * Original Dizilla implementation:
 * @keyiflerolsun / @KekikAkademi
 *
 * Additional maintenance/reference:
 * @nikyokki
 *
 * Adapted for the Anihepsi CloudStream repository
 * while preserving original attribution.
 */

@CloudstreamPlugin
class DizillaPlugin : BasePlugin() {

    override fun load() {

        registerMainAPI(
            DizillaProvider()
        )

        registerExtractorAPI(
            ContentX()
        )

        registerExtractorAPI(
            Hotlinger()
        )

        registerExtractorAPI(
            FourCX()
        )

        registerExtractorAPI(
            PlayRu()
        )

        registerExtractorAPI(
            FourPlayRu()
        )

        registerExtractorAPI(
            Pichive()
        )

        registerExtractorAPI(
            FourPichive()
        )
    }
}
