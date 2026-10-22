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
import kotlin.math.abs

class Clasicos80Provider : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org"
    override var name = "Clásicos de los 80"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        // ⚠️ Pegá acá tu API Key de TMDB (la "API Key", no el token largo)
        private const val TMDB_API_KEY = "89a0e5fdc4112639091671c042604699"

        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"
        private const val DESDE = "1980-01-01"
        private const val HASTA = "1989-12-31"

        // Idiomas de los proveedores donde se busca (español / latino)
        private val IDIOMAS = setOf("es", "mx", "es-mx", "es-419")

        // Tiempo máximo de espera por proveedor (milisegundos)
        private const val TIMEOUT_MS = 25_000L
    }

    // Secciones del Home. Géneros TMDB: 28 acción, 12 aventura, 16 animación,
    // 35 comedia, 27 terror, 878 ciencia ficción, 10751 familia
    override val mainPage = mainPageOf(
        "sort_by=popularity.desc" to "🎬 Los más populares de los 80",
        "sort_by=popularity.desc&with_genres=35" to "😂 Comedias ochentosas",
        "sort_by=popularity.desc&with_genres=28" to "💥 Acción de los 80",
        "sort_by=popularity.desc&with_genres=878" to "👽 Ciencia ficción de los 80",
        "sort_by=popularity.desc&with_genres=12" to "🗺️ Aventuras de los 80",
        "sort_by=popularity.desc&with_genres=27" to "👻 Terror de los 80",
        "sort_by=popularity.desc&with_genres=16" to "📺 Animación de los 80",
        "sort_by=popularity.desc&with_genres=10751" to "👨‍👩‍👧 Para ver en familia",
        "sort_by=vote_average.desc&vote_count.gte=2000" to "🏆 Los mejor puntuados",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$API/discover/movie?api_key=$TMDB_API_KEY&language=es-MX&include_adult=false" +
                "&primary_release_date.gte=$DESDE&primary_release_date.lte=$HASTA" +
                "&${request.data}&page=$page"
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
            .filter { (it.anio() ?: 0) in 1980..1989 }
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
