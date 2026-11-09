package fr.nrobeveille.tviptv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Cadrage de l'image dans le lecteur. */
enum class AspectMode(val label: String) {
    FIT("Ajusté"),
    FILL("Étiré"),
    ZOOM("Zoom (recadré)"),
}

data class AppSettings(
    val playlists: List<Playlist> = emptyList(),
    val activePlaylistId: String? = null,
    val userAgentName: String = Http.DEFAULT_USER_AGENT_NAME,
    val resumeLastChannel: Boolean = false,
    val aspect: AspectMode = AspectMode.FIT,
    val favorites: Set<String> = emptySet(),
    val recents: List<String> = emptyList(),
    val lastChannel: String? = null,
) {
    val activePlaylist: Playlist?
        get() = playlists.firstOrNull { it.id == activePlaylistId } ?: playlists.firstOrNull()

    val userAgent: String
        get() = Http.USER_AGENTS[userAgentName] ?: Http.USER_AGENTS.getValue(Http.DEFAULT_USER_AGENT_NAME)
}

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val PLAYLISTS = stringPreferencesKey("playlists")
        val ACTIVE = stringPreferencesKey("active_playlist")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val RESUME_LAST = booleanPreferencesKey("resume_last")
        val ASPECT = stringPreferencesKey("aspect")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val RECENTS = stringPreferencesKey("recents")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            playlists = Playlist.listFromJson(p[Keys.PLAYLISTS]),
            activePlaylistId = p[Keys.ACTIVE],
            userAgentName = p[Keys.USER_AGENT]?.takeIf { it in Http.USER_AGENTS } ?: Http.DEFAULT_USER_AGENT_NAME,
            resumeLastChannel = p[Keys.RESUME_LAST] ?: false,
            aspect = p[Keys.ASPECT]?.let { runCatching { AspectMode.valueOf(it) }.getOrNull() } ?: AspectMode.FIT,
            favorites = p[Keys.FAVORITES] ?: emptySet(),
            recents = stringList(p[Keys.RECENTS]),
            lastChannel = p[Keys.LAST_CHANNEL],
        )
    }

    /** Ajoute ou met à jour une playlist ; la rend active. Renvoie son identifiant. */
    suspend fun savePlaylist(name: String, url: String, epgUrl: String, id: String? = null): String {
        val playlistId = id ?: UUID.randomUUID().toString()
        store.edit { p ->
            val list = Playlist.listFromJson(p[Keys.PLAYLISTS]).toMutableList()
            val playlist = Playlist(playlistId, name.ifBlank { "Ma playlist" }, url.trim(), epgUrl.trim())
            val index = list.indexOfFirst { it.id == playlistId }
            if (index >= 0) list[index] = playlist else list += playlist
            p[Keys.PLAYLISTS] = Playlist.listToJson(list)
            p[Keys.ACTIVE] = playlistId
        }
        return playlistId
    }

    suspend fun deletePlaylist(id: String) {
        store.edit { p ->
            val list = Playlist.listFromJson(p[Keys.PLAYLISTS]).filterNot { it.id == id }
            p[Keys.PLAYLISTS] = Playlist.listToJson(list)
            if (p[Keys.ACTIVE] == id) {
                list.firstOrNull()?.let { p[Keys.ACTIVE] = it.id } ?: p.remove(Keys.ACTIVE)
            }
        }
    }

    suspend fun setActivePlaylist(id: String) = store.edit { it[Keys.ACTIVE] = id }

    suspend fun setUserAgent(name: String) = store.edit { it[Keys.USER_AGENT] = name }

    suspend fun setResumeLast(enabled: Boolean) = store.edit { it[Keys.RESUME_LAST] = enabled }

    suspend fun setAspect(mode: AspectMode) = store.edit { it[Keys.ASPECT] = mode.name }

    suspend fun toggleFavorite(key: String) = store.edit { p ->
        val current = p[Keys.FAVORITES] ?: emptySet()
        p[Keys.FAVORITES] = if (key in current) current - key else current + key
    }

    /** Mémorise une chaîne regardée (historique + dernière chaîne). */
    suspend fun addRecent(key: String) = store.edit { p ->
        val list = stringList(p[Keys.RECENTS]).toMutableList()
        list.remove(key)
        list.add(0, key)
        p[Keys.RECENTS] = JSONArray(list.take(MAX_RECENTS)).toString()
        p[Keys.LAST_CHANNEL] = key
    }

    suspend fun clearHistory() = store.edit { p ->
        p.remove(Keys.RECENTS)
        p.remove(Keys.LAST_CHANNEL)
    }

    private fun stringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_RECENTS = 30
    }
}
