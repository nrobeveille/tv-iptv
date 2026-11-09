package fr.nrobeveille.tviptv.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class DownloadException(message: String) : IOException(message)

object Http {

    /** User-Agent proposés : beaucoup de fournisseurs IPTV refusent les clients inconnus. */
    val USER_AGENTS = linkedMapOf(
        "VLC" to "VLC/3.0.20 LibVLC/3.0.20",
        "IPTV Smarters" to "IPTVSmartersPlayer",
        "Navigateur" to "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "ExoPlayer" to "ExoPlayerLib/1.5.1",
    )
    const val DEFAULT_USER_AGENT_NAME = "VLC"

    /**
     * Télécharge [url] dans [target] (via un fichier temporaire, pour ne jamais casser le cache).
     * Décompresse automatiquement le gzip (guides TV en .xml.gz).
     */
    suspend fun download(url: String, target: File, userAgent: String, onProgress: (Long) -> Unit = {}) {
        val connection = open(url, userAgent)
        val temp = File(target.parentFile, target.name + ".part")
        try {
            val raw = BufferedInputStream(connection.inputStream, 64 * 1024)
            val input: InputStream = if (isGzip(raw)) GZIPInputStream(raw, 64 * 1024) else raw
            input.use { stream ->
                temp.outputStream().buffered(64 * 1024).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        total += read
                        if (total - lastReport > 512 * 1024) {
                            lastReport = total
                            onProgress(total)
                        }
                    }
                }
            }
            if (temp.length() == 0L) throw DownloadException("Le serveur a renvoyé un fichier vide")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
        } finally {
            temp.delete()
            connection.disconnect()
        }
    }

    private fun isGzip(input: BufferedInputStream): Boolean {
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return b1 == 0x1f && b2 == 0x8b
    }

    /** Ouvre l'URL en suivant les redirections, y compris http ↔ https. */
    fun open(startUrl: String, userAgent: String): HttpURLConnection {
        var url = try {
            URL(startUrl.trim())
        } catch (e: Exception) {
            throw DownloadException("Adresse invalide")
        }
        repeat(8) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
            }
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw DownloadException("Serveur injoignable (${url.host})")
            }
            when (code) {
                in 200..299 -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrEmpty()) throw DownloadException("Redirection invalide")
                    url = URL(url, location)
                }
                401, 403 -> {
                    connection.disconnect()
                    throw DownloadException("Accès refusé ($code) : identifiants ou abonnement à vérifier")
                }
                404 -> {
                    connection.disconnect()
                    throw DownloadException("Introuvable (404) : vérifie l'adresse")
                }
                else -> {
                    connection.disconnect()
                    throw DownloadException("Erreur du serveur ($code)")
                }
            }
        }
        throw DownloadException("Trop de redirections")
    }
}
