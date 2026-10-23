package com.costastream.modovacaciones

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ModoVacacionesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ModoVacacionesProvider())
    }
}
