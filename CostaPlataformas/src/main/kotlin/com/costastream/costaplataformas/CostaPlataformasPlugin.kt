package com.costastream.costaplataformas

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CostaPlataformasPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CostaPlataformasProvider())
    }
}
