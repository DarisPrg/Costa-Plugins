package com.costastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class Clasicos80Provider : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org"
    override var name = "Clásicos de los 80"
    override var lang = "es-AR"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        // API Key de TMDB
        private const val TMDB_API_KEY = "89a0e5fdc4112639091671c042604699"

        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"

        // Rango de años (0 = sin límite). Se usa para filtrar la búsqueda.
        private const val ANIO_DESDE = 1980
        private const val ANIO_HASTA = 1989

        // Filtro que se agrega a todas las secciones "discover" (vacío = ninguno)
        private const val FILTRO_BASE = "primary_release_date.gte=1980-01-01&primary_release_date.lte=1989-12-31"

        // Idiomas de los proveedores donde se busca (español / latino)
        private val IDIOMAS = setOf("es", "mx", "es-mx", "es-419", "es-ar")

        // Plan B: si ningún proveedor en español la tiene, se busca en estos (otros idiomas)
        private val PLAN_B = listOf("yts", "streamplay", "internet archive", "internetarchive")

        // Tiempo máximo de espera por proveedor (milisegundos)
        private const val TIMEOUT_MS = 30_000L

        // El plan B (StreamPlay junta muchos servidores) necesita más tiempo
        private const val TIMEOUT_PLAN_B_MS = 90_000L

        // Títulos alternativos: países preferidos (en orden) y cantidad máxima de títulos a probar
        private val PAISES = listOf("MX", "AR", "CO", "CL", "PE", "VE", "UY", "ES", "US")
        private const val MAX_TITULOS = 5
    }

    // Secciones del Home: "ruta|parámetros" to "Título"
    // Géneros TMDB: 12 aventura, 14 fantasía, 16 animación, 18 drama, 27 terror, 28 acción,
    // 35 comedia, 53 suspenso, 80 policial, 99 documental, 878 ciencia ficción,
    // 10402 música, 10749 romance, 10751 familia
    override val mainPage = mainPageOf(
        "discover/movie|sort_by=popularity.desc" to "🎬 Los más populares de los 80",
        "discover/movie|sort_by=popularity.desc&with_genres=35" to "😂 Comedias ochentosas",
        "discover/movie|sort_by=popularity.desc&with_genres=28" to "💥 Acción de los 80",
        "discover/movie|sort_by=popularity.desc&with_genres=878" to "👽 Ciencia ficción de los 80",
        "discover/movie|sort_by=popularity.desc&with_genres=12" to "🗺️ Aventuras de los 80",
        "discover/movie|sort_by=popularity.desc&with_genres=27" to "👻 Terror de los 80",
        "discover/movie|sort_by=popularity.desc&with_genres=16" to "📺 Animación de los 80",
        "discover/movie|sort_by=popularity.desc&with_genres=10751" to "👨‍👩‍👧 Para ver en familia",
        "discover/movie|sort_by=vote_average.desc&vote_count.gte=2000" to "🏆 Los mejor puntuados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val partes = request.data.split("|", limit = 2)
        val ruta = partes[0]
        val params = partes.getOrElse(1) { "" }
        val filtro = if (ruta.startsWith("discover") && FILTRO_BASE.isNotEmpty()) "&$FILTRO_BASE" else ""
        val url = "$API/$ruta?api_key=$TMDB_API_KEY&language=es-MX&include_adult=false" +
                "$filtro&$params&page=$page"
        val res = app.get(url).parsedSafe<TmdbPage>()
        val items = res?.results.orEmpty().mapNotNull { it.toSearch() }
        val hasNext = (res?.page ?: 0) < (res?.totalPages ?: 0)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = URLEncoder.encode(query, "UTF-8")
        val res = app.get("$API/search/movie?api_key=$TMDB_API_KEY&language=es-MX&include_adult=false&query=$q")
            .parsedSafe<TmdbPage>()
        return res?.results.orEmpty()
            .filter { m ->
                val y = m.anio() ?: 0
                (ANIO_DESDE == 0 || y >= ANIO_DESDE) && (ANIO_HASTA == 0 || y <= ANIO_HASTA)
            }
            .mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val datos = parseJson<Datos>(url)
        val det = app.get("$API/movie/${datos.id}?api_key=$TMDB_API_KEY&language=es-MX&append_to_response=credits")
            .parsedSafe<TmdbDetalle>()
        val titulo = det?.title ?: datos.titulo
        return newMovieLoadResponse(titulo, url, TvType.Movie, url) {
            this.posterUrl = det?.posterPath?.let { "$IMG/w500$it" }
            this.backgroundPosterUrl = det?.backdropPath?.let { "$IMG/w1280$it" }
            this.year = datos.anio
            this.plot = det?.overview?.takeIf { it.isNotBlank() }
            this.tags = det?.genres?.mapNotNull { it.name }
            this.duration = det?.runtime
            addActors(det?.credits?.cast?.take(10)?.mapNotNull { it.name })
        }
    }

    // Busca la película en tus proveedores en español y junta todos sus servidores
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val datos = parseJson<Datos>(data)
        val titulos = titulosAlternativos(datos)
        val buscados = titulos.map { normalizar(it) }.filter { it.isNotEmpty() }.toSet()

        val proveedores = APIHolder.apis.toList().filter { api ->
            api.name != name &&
                    !api.name.contains("Catalogo", ignoreCase = true) &&
                    api.mainUrl != mainUrl &&
                    api.lang.lowercase() in IDIOMAS &&
                    api.supportedTypes.contains(TvType.Movie)
        }

        // Contamos los enlaces que llegan de verdad: algunos proveedores dicen "encontré"
        // aunque no entreguen ningún enlace
        val enlaces = AtomicInteger(0)
        val contar: (ExtractorLink) -> Unit = { link ->
            enlaces.incrementAndGet()
            callback(link)
        }

        buscarEnTodos(proveedores, titulos, buscados, datos.anio, isCasting, subtitleCallback, contar, TIMEOUT_MS)
        if (enlaces.get() > 0) return true

        // Plan B: nadie la tiene en español -> probar fuentes en otros idiomas, primero con el título original
        val planB = APIHolder.apis.toList().filter { api ->
            api.mainUrl != mainUrl &&
                    api.supportedTypes.contains(TvType.Movie) &&
                    PLAN_B.any { api.name.lowercase().contains(it) }
        }
        val titulosB = (listOfNotNull(datos.original) + titulos).distinct()
        buscarEnTodos(planB, titulosB, buscados, datos.anio, isCasting, subtitleCallback, contar, TIMEOUT_PLAN_B_MS)
        return enlaces.get() > 0
    }

    private suspend fun buscarEnTodos(
        proveedores: List<MainAPI>,
        titulos: List<String>,
        buscados: Set<String>,
        anio: Int?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        timeout: Long
    ): Boolean {
        if (proveedores.isEmpty()) return false
        val resultados = coroutineScope {
            proveedores.map { api ->
                async {
                    withTimeoutOrNull(timeout) {
                        try {
                            buscarEn(api, titulos, buscados, anio, isCasting, subtitleCallback, callback)
                        } catch (e: Throwable) {
                            false
                        }
                    } ?: false
                }
            }.awaitAll()
        }
        return resultados.any { it }
    }

    private suspend fun buscarEn(
        api: MainAPI,
        titulos: List<String>,
        buscados: Set<String>,
        anio: Int?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (t in titulos) {
            val res = api.search(t) ?: continue
            // Cada resultado se compara contra TODOS los títulos conocidos, no solo el buscado
            val match = res.firstOrNull { r ->
                coincide(r.name, (r as? MovieSearchResponse)?.year, buscados, anio)
            } ?: continue
            val lr = api.load(match.url) as? MovieLoadResponse ?: continue
            return api.loadLinks(lr.dataUrl, isCasting, subtitleCallback, callback)
        }
        return false
    }

    // Junta el título en español, el original y los títulos alternativos de TMDB
    // (traducciones al español y nombres por país: México, Argentina, España, etc.)
    private suspend fun titulosAlternativos(datos: Datos): List<String> {
        val lista = mutableListOf(datos.titulo)
        datos.original?.let { lista.add(it) }
        try {
            val alt = app.get("$API/movie/${datos.id}?api_key=$TMDB_API_KEY&append_to_response=alternative_titles,translations")
                .parsedSafe<TmdbAlternativos>()
            alt?.translations?.translations
                ?.filter { it.lang == "es" }
                ?.sortedBy { tr -> PAISES.indexOf(tr.pais).let { i -> if (i < 0) 99 else i } }
                ?.mapNotNull { tr -> tr.data?.title?.takeIf { s -> s.isNotBlank() } }
                ?.let { lista.addAll(it) }
            alt?.alternativeTitles?.titles
                ?.filter { it.pais in PAISES }
                ?.sortedBy { PAISES.indexOf(it.pais) }
                ?.mapNotNull { at -> at.title?.takeIf { s -> s.isNotBlank() } }
                ?.let { lista.addAll(it) }
        } catch (e: Throwable) {
            // Si TMDB no responde, seguimos con los dos títulos básicos
        }
        val vistos = mutableSetOf<String>()
        return lista.filter { normalizar(it).let { n -> n.isNotEmpty() && vistos.add(n) } }.take(MAX_TITULOS)
    }

    private fun coincide(nombre: String, y: Int?, buscados: Set<String>, anio: Int?): Boolean {
        val n = normalizar(nombre)
        if (n.isEmpty()) return false
        val anioOk = y == null || anio == null || abs(y - anio) <= 1
        if (n in buscados && anioOk) return true
        // Coincidencia parcial ("Volver al futuro" / "Volver al futuro parte I") solo con el año exacto,
        // para no confundir remakes o secuelas
        if (y != null && anio != null && y == anio) {
            return buscados.any { b ->
                b.length >= 6 && n.length >= 6 && (n.startsWith(b) || b.startsWith(n))
            }
        }
        return false
    }

    private fun normalizar(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        t = t.replace(Regex("\\((19|20)\\d{2}\\)"), " ")   // "(1985)"
            .replace(Regex("\\[[^\\]]*\\]"), " ")          // "[Latino]"
            .replace(Regex("\\b(latino|castellano|espanol|subtitulada|subtitulado|hd|4k|1080p|720p)\\b"), " ")
            .trim()
        t = t.replace(Regex("^(el|la|los|las|the|un|una)\\s+"), "")
        return t.replace(Regex("[^a-z0-9]"), "")
    }

    private fun TmdbMovie.toSearch(): SearchResponse? {
        val titulo = title ?: originalTitle ?: return null
        val data = Datos(id, titulo, originalTitle, anio()).toJson()
        return newMovieSearchResponse(titulo, data, TvType.Movie) {
            this.posterUrl = posterPath?.let { "$IMG/w500$it" }
            this.year = anio()
        }
    }

    // ---- Datos ----
    data class Datos(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("titulo") val titulo: String = "",
        @JsonProperty("original") val original: String? = null,
        @JsonProperty("anio") val anio: Int? = null,
    )

    data class TmdbPage(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
        @JsonProperty("results") val results: List<TmdbMovie>? = null,
    )

    data class TmdbMovie(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
    ) {
        fun anio(): Int? = releaseDate?.take(4)?.toIntOrNull()
    }

    data class TmdbDetalle(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("genres") val genres: List<Genero>? = null,
        @JsonProperty("credits") val credits: Creditos? = null,
    )

    data class TmdbAlternativos(
        @JsonProperty("alternative_titles") val alternativeTitles: AltTitulos? = null,
        @JsonProperty("translations") val translations: Traducciones? = null,
    )

    data class AltTitulos(@JsonProperty("titles") val titles: List<AltTitulo>? = null)
    data class AltTitulo(
        @JsonProperty("iso_3166_1") val pais: String? = null,
        @JsonProperty("title") val title: String? = null,
    )

    data class Traducciones(@JsonProperty("translations") val translations: List<Traduccion>? = null)
    data class Traduccion(
        @JsonProperty("iso_3166_1") val pais: String? = null,
        @JsonProperty("iso_639_1") val lang: String? = null,
        @JsonProperty("data") val data: TradData? = null,
    )

    data class TradData(@JsonProperty("title") val title: String? = null)

    data class Genero(@JsonProperty("name") val name: String? = null)
    data class Creditos(@JsonProperty("cast") val cast: List<Actor>? = null)
    data class Actor(@JsonProperty("name") val name: String? = null)
}
