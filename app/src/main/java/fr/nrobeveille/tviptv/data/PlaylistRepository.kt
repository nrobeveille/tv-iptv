package fr.nrobeveille.tviptv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Télécharge les playlists, les garde en cache sur le stick et les analyse. */
class PlaylistRepository(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "playlists").apply { mkdirs() }

    private fun cacheFile(id: String) = File(dir, "$id.m3u")

    /** Date du dernier téléchargement réussi, ou null s'il n'y a pas de cache. */
    fun lastUpdate(id: String): Long? = cacheFile(id).takeIf { it.exists() }?.lastModified()

    suspend fun loadCached(id: String): PlaylistContent? = withContext(Dispatchers.IO) {
        val file = cacheFile(id)
        if (!file.exists()) return@withContext null
        runCatching { file.bufferedReader(bufferSize = 64 * 1024).use { M3uParser.parse(it) } }.getOrNull()
    }

    /** Télécharge la playlist, vérifie qu'elle est lisible puis remplace le cache. */
    suspend fun download(playlist: Playlist, userAgent: String, onProgress: (Long) -> Unit): PlaylistContent =
        withContext(Dispatchers.IO) {
            val fresh = File(dir, "${playlist.id}.new")
            try {
                Http.download(playlist.url, fresh, userAgent, onProgress)
                val content = fresh.bufferedReader(bufferSize = 64 * 1024).use { M3uParser.parse(it) }
                if (content.isEmpty) {
                    val start = fresh.bufferedReader().use { it.readLine().orEmpty() }
                    throw DownloadException(
                        if (start.contains("#EXTM3U", ignoreCase = true)) {
                            "La playlist est vide"
                        } else {
                            "Ce n'est pas une playlist M3U (vérifie l'adresse et l'abonnement)"
                        }
                    )
                }
                val target = cacheFile(playlist.id)
                if (!fresh.renameTo(target)) fresh.copyTo(target, overwrite = true)
                content
            } finally {
                fresh.delete()
            }
        }

    fun deleteCache(id: String) {
        cacheFile(id).delete()
    }
}
