package com.costastream.modovacaciones

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
import kotlin.math.abs

class ModoVacacionesProvider : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org"
    override var name = "Modo Vacaciones"
    override var lang = "es-AR"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        // API Key de TMDB
        private const val TMDB_API_KEY = "89a0e5fdc4112639091671c042604699"

        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"

        // Rango de años (0 = sin límite). Se usa para filtrar la búsqueda.
        private const val ANIO_DESDE = 0
        private const val ANIO_HASTA = 0

        // Filtro que se agrega a todas las secciones "discover" (vacío = ninguno)
        private const val FILTRO_BASE = ""

        // Idiomas de los proveedores donde se busca (español / latino)
        private val IDIOMAS = setOf("es", "mx", "es-mx", "es-419", "es-ar")

        // Tiempo máximo de espera por proveedor (milisegundos)
        private const val TIMEOUT_MS = 25_000L
    }

    // Secciones del Home: "ruta|parámetros" to "Título"
    // Géneros TMDB: 12 aventura, 14 fantasía, 16 animación, 18 drama, 27 terror, 28 acción,
    // 35 comedia, 53 suspenso, 80 policial, 99 documental, 878 ciencia ficción,
    // 10402 música, 10749 romance, 10751 familia
    override val mainPage = mainPageOf(
        "trending/movie/week|" to "🔥 Tendencias de la semana",
        "movie/now_playing|region=AR" to "🎟️ Recién estrenadas",
        "discover/movie|sort_by=popularity.desc" to "🍿 Las más populares",
        "discover/movie|sort_by=popularity.desc&with_genres=16" to "🧸 Infantiles y dibujos",
        "discover/movie|sort_by=popularity.desc&with_genres=10751" to "👨‍👩‍👧 Para ver en familia",
        "discover/movie|sort_by=popularity.desc&with_genres=35" to "😂 Comedia",
        "discover/movie|sort_by=popularity.desc&with_genres=28" to "💥 Acción",
        "discover/movie|sort_by=popularity.desc&with_genres=12" to "🗺️ Aventura",
        "discover/movie|sort_by=popularity.desc&with_genres=878" to "👽 Ciencia ficción",
        "discover/movie|sort_by=popularity.desc&with_genres=14" to "🧙 Fantasía",
        "discover/movie|sort_by=popularity.desc&with_genres=27" to "👻 Terror",
        "discover/movie|sort_by=popularity.desc&with_genres=53" to "😱 Suspenso",
        "discover/movie|sort_by=popularity.desc&with_genres=18" to "🎭 Drama",
        "discover/movie|sort_by=popularity.desc&with_genres=10749" to "💕 Romance",
        "discover/movie|sort_by=popularity.desc&with_genres=80" to "🕵️ Policiales",
        "discover/movie|sort_by=popularity.desc&with_genres=99" to "🎥 Documentales",
        "discover/movie|sort_by=popularity.desc&with_genres=10402" to "🎵 Música y musicales",
        "discover/movie|sort_by=popularity.desc&with_origin_country=AR" to "🇦🇷 Cine argentino",
        "discover/movie|sort_by=popularity.desc&with_original_language=es" to "🌎 Cine en español",
        "discover/movie|sort_by=vote_average.desc&vote_count.gte=3000&primary_release_date.lte=1989-12-31" to "📼 Clásicos de siempre",
        "discover/movie|sort_by=vote_average.desc&vote_count.gte=5000" to "🏆 Las mejor puntuadas"
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
        val titulos = listOfNotNull(datos.titulo, datos.original).distinct()
        val buscados = titulos.map { normalizar(it) }.toSet()

        val proveedores = APIHolder.apis.toList().filter { api ->
            api.name != name &&
                    !api.name.contains("Catalogo", ignoreCase = true) &&
                    api.mainUrl != mainUrl &&
                    api.lang.lowercase() in IDIOMAS &&
                    api.supportedTypes.contains(TvType.Movie)
        }

        val resultados = coroutineScope {
            proveedores.map { api ->
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        try {
                            buscarEn(api, titulos, buscados, datos.anio, isCasting, subtitleCallback, callback)
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
            val match = res.firstOrNull { r ->
                val y = (r as? MovieSearchResponse)?.year
                normalizar(r.name) in buscados && (y == null || anio == null || abs(y - anio) <= 1)
            } ?: continue
            val lr = api.load(match.url) as? MovieLoadResponse ?: continue
            return api.loadLinks(lr.dataUrl, isCasting, subtitleCallback, callback)
        }
        return false
    }

    private fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

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

    data class Genero(@JsonProperty("name") val name: String? = null)
    data class Creditos(@JsonProperty("cast") val cast: List<Actor>? = null)
    data class Actor(@JsonProperty("name") val name: String? = null)
}
