package com.costastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Clasicos80Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Clasicos80Provider())
    }
}
