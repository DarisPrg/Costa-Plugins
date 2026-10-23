package com.costastream.clasicos90

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Clasicos90Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Clasicos90Provider())
    }
}
