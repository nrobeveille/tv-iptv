package fr.nrobeveille.tviptv.data

import org.json.JSONArray
import org.json.JSONObject

/** Nature d'une entrée de la playlist, déduite de son URL. */
enum class ContentType { LIVE, MOVIE, SERIES }

/** Une entrée de la playlist M3U : chaîne, film ou épisode. */
data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val tvgId: String?,
    val tvgName: String?,
    val type: ContentType,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Numéro affiché (chaînes en direct uniquement). */
    val number: Int = 0,
    /** Pour un épisode : nom de la série, saison et numéro. */
    val seriesName: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
) {
    /** Identifiant stable pour les favoris, l'historique et la reprise. */
    val key: String get() = url
}

/** Une série reconstituée à partir de ses épisodes. */
data class Series(
    val name: String,
    val group: String,
    val poster: String?,
    val episodes: List<Channel>,
) {
    val key: String get() = "series:$group:$name"
    val seasons: List<Int> by lazy { episodes.map { it.season }.distinct().sorted() }
    fun episodesOf(season: Int): List<Channel> =
        episodes.filter { it.season == season }.sortedBy { it.episode }
}

/** Une catégorie (group-title) et son contenu. */
data class Category<T>(val name: String, val items: List<T>)

/** Contenu complet d'une playlist, prêt à afficher. */
class PlaylistContent(
    val live: List<Category<Channel>>,
    val movies: List<Category<Channel>>,
    val series: List<Category<Series>>,
    /** URL du guide TV annoncée dans l'en-tête #EXTM3U (url-tvg / x-tvg-url). */
    val headerEpgUrl: String?,
) {
    val allLive: List<Channel> = live.flatMap { it.items }
    val allMovies: List<Channel> = movies.flatMap { it.items }
    val allSeries: List<Series> = series.flatMap { it.items }

    private val byKey: Map<String, Channel> by lazy {
        HashMap<String, Channel>(allLive.size + allMovies.size).apply {
            allMovies.forEach { put(it.key, it) }
            allLive.forEach { put(it.key, it) }
        }
    }
    private val seriesByKey: Map<String, Series> by lazy { allSeries.associateBy { it.key } }

    fun channel(key: String): Channel? = byKey[key]
    fun series(key: String): Series? = seriesByKey[key]

    val isEmpty: Boolean get() = allLive.isEmpty() && allMovies.isEmpty() && allSeries.isEmpty()
}

/** Une playlist enregistrée (compte IPTV). */
data class Playlist(
    val id: String,
    val name: String,
    val url: String,
    /** Guide TV forcé ; vide = celui de la playlist ou celui déduit d'un compte Xtream. */
    val epgUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("epgUrl", epgUrl)

    companion object {
        fun fromJson(o: JSONObject) = Playlist(
            id = o.getString("id"),
            name = o.optString("name"),
            url = o.optString("url"),
            epgUrl = o.optString("epgUrl"),
        )

        fun listFromJson(json: String?): List<Playlist> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                List(array.length()) { fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun listToJson(list: List<Playlist>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}

/** Un programme du guide TV. */
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
) {
    fun isOnAt(time: Long) = time in start until stop
    fun progressAt(time: Long): Float =
        if (stop <= start) 0f else ((time - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}
