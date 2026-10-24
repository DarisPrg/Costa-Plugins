package com.costastream.costavivo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.text.Normalizer

class CostaVivoProvider : MainAPI() {
    override var mainUrl = "https://iptv-org.github.io"
    override var name = "Costa Vivo"
    override var lang = "es-AR"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        // Listas públicas de iptv-org (solo señales de acceso libre)
        private const val LISTA_ESPANOL = "https://iptv-org.github.io/iptv/languages/spa.m3u"
        private const val LISTA_ARGENTINA = "https://iptv-org.github.io/iptv/countries/ar.m3u"

        // Canales por página en cada sección
        private const val POR_PAGINA = 60
    }

    // Secciones: "lista|palabras clave del grupo o del nombre (separadas por coma)" to "Título"
    // Si las palabras clave están vacías, entra toda la lista.
    override val mainPage = mainPageOf(
        "ar|" to "🇦🇷 Canales argentinos",
        "es|classic" to "📼 Clásicos 24/7",
        "es|series" to "📺 Series 24/7",
        "es|animation,kids" to "🧸 Dibujos e infantiles",
        "es|movies" to "🎬 Películas 24/7",
        "es|pluto" to "🪐 Pluto TV",
        "es|news" to "📰 Noticias",
        "es|sports" to "⚽ Deportes",
        "es|music" to "🎵 Música",
        "es|documentary,science,travel" to "🌍 Documentales",
        "es|comedy,entertainment" to "😂 Entretenimiento",
    )

    // Caché en memoria para no descargar las listas en cada sección
    private var cache: Map<String, List<Canal>> = emptyMap()
    private var cacheHora = 0L

    private suspend fun listas(): Map<String, List<Canal>> {
        val ahora = System.currentTimeMillis()
        if (cache.isNotEmpty() && ahora - cacheHora < 30 * 60 * 1000) return cache
        val nuevo = mapOf(
            "es" to descargar(LISTA_ESPANOL),
            "ar" to descargar(LISTA_ARGENTINA),
        )
        if (nuevo.values.any { it.isNotEmpty() }) {
            cache = nuevo
            cacheHora = ahora
        }
        return nuevo
    }

    private suspend fun descargar(url: String): List<Canal> =
        try { parsearM3u(app.get(url).text) } catch (e: Throwable) { emptyList() }

    // Lee un archivo M3U: #EXTINF con logo, grupo y nombre, opciones de VLC y la URL del stream
    private fun parsearM3u(texto: String): List<Canal> {
        val canales = mutableListOf<Canal>()
        var nombre: String? = null
        var logo: String? = null
        var grupo = ""
        var agente: String? = null
        var referer: String? = null
        for (crudo in texto.lineSequence()) {
            val linea = crudo.trim()
            when {
                linea.startsWith("#EXTINF") -> {
                    nombre = linea.substringAfterLast(",").trim().ifBlank { null }
                    logo = atributo(linea, "tvg-logo")
                    grupo = atributo(linea, "group-title") ?: ""
                    agente = atributo(linea, "http-user-agent")
                    referer = atributo(linea, "http-referrer")
                }
                linea.startsWith("#EXTVLCOPT:http-user-agent=") ->
                    agente = linea.substringAfter("=").trim()
                linea.startsWith("#EXTVLCOPT:http-referrer=") ->
                    referer = linea.substringAfter("=").trim()
                linea.isNotEmpty() && !linea.startsWith("#") -> {
                    val n = nombre
                    if (n != null && linea.startsWith("http")) {
                        canales.add(Canal(n, linea, logo, grupo, agente, referer))
                    }
                    nombre = null; logo = null; grupo = ""; agente = null; referer = null
                }
            }
        }
        return canales.distinctBy { it.url }
    }

    private fun atributo(linea: String, clave: String): String? =
        Regex("$clave=\"([^\"]*)\"").find(linea)?.groupValues?.get(1)?.trim()?.ifBlank { null }

    private fun filtrar(canales: List<Canal>, claves: List<String>): List<Canal> {
        if (claves.isEmpty()) return canales
        return canales.filter { c ->
            val g = c.grupo.lowercase()
            val n = c.nombre.lowercase()
            claves.any { k -> g.contains(k) || n.contains(k) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (lista, filtro) = request.data.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val claves = filtro.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val todos = filtrar(listas()[lista].orEmpty(), claves).sortedBy { normalizar(it.nombre) }
        val desde = (page - 1) * POR_PAGINA
        val items = todos.drop(desde).take(POR_PAGINA).map { it.toSearch() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = desde + POR_PAGINA < todos.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = normalizar(query)
        if (q.isEmpty()) return emptyList()
        return listas().values.flatten()
            .distinctBy { it.url }
            .filter { normalizar(it.nombre).contains(q) }
            .take(80)
            .map { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val c = parseJson<Canal>(url)
        return newLiveStreamLoadResponse(c.nombre, url, url) {
            this.posterUrl = c.logo
            this.plot = c.grupo.replace(";", " · ").ifBlank { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val c = parseJson<Canal>(data)
        val tipo = if (c.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(name, c.nombre, c.url, tipo) {
                this.quality = Qualities.Unknown.value
                c.referer?.let { this.referer = it }
                c.agente?.let { this.headers = mapOf("User-Agent" to it) }
            }
        )
        return true
    }

    private fun Canal.toSearch(): SearchResponse =
        newLiveSearchResponse(nombre, this.toJson(), TvType.Live) {
            this.posterUrl = logo
        }

    private fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    data class Canal(
        @JsonProperty("nombre") val nombre: String = "",
        @JsonProperty("url") val url: String = "",
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("grupo") val grupo: String = "",
        @JsonProperty("agente") val agente: String? = null,
        @JsonProperty("referer") val referer: String? = null,
    )
}
