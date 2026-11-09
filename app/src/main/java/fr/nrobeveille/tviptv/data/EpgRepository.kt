package fr.nrobeveille.tviptv.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Guide des programmes, indexé par clé de chaîne. */
class Epg(private val byChannel: Map<String, List<Programme>>) {

    val isEmpty: Boolean get() = byChannel.isEmpty()
    val channelCount: Int get() = byChannel.size

    fun programmes(channel: Channel): List<Programme> = byChannel[channel.key].orEmpty()

    fun now(channel: Channel, time: Long = System.currentTimeMillis()): Programme? =
        programmes(channel).firstOrNull { it.isOnAt(time) }

    fun next(channel: Channel, time: Long = System.currentTimeMillis()): Programme? =
        programmes(channel).firstOrNull { it.start >= time }

    /** Programmes en cours et à venir (pour le détail d'une chaîne). */
    fun upcoming(channel: Channel, count: Int, time: Long = System.currentTimeMillis()): List<Programme> =
        programmes(channel).filter { it.stop > time }.take(count)

    companion object {
        val EMPTY = Epg(emptyMap())
    }
}

/** Télécharge le guide TV (XMLTV), le garde en cache et ne retient que les chaînes de la playlist. */
class EpgRepository(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "epg").apply { mkdirs() }

    private fun cacheFile(id: String) = File(dir, "$id.xml")

    fun lastUpdate(id: String): Long? = cacheFile(id).takeIf { it.exists() }?.lastModified()

    /**
     * Adresse du guide : celle saisie, sinon celle annoncée par la playlist, sinon celle
     * d'un compte Xtream (`get.php?username=…&password=…` → `xmltv.php?…`).
     */
    fun resolveUrl(playlist: Playlist, content: PlaylistContent?): String? {
        playlist.epgUrl.takeIf { it.isNotBlank() }?.let { return it }
        content?.headerEpgUrl?.let { return it }
        return xtreamEpgUrl(playlist.url)
    }

    suspend fun download(playlist: Playlist, url: String, userAgent: String) = withContext(Dispatchers.IO) {
        Http.download(url, cacheFile(playlist.id), userAgent)
    }

    suspend fun loadCached(id: String, content: PlaylistContent): Epg? = withContext(Dispatchers.IO) {
        val file = cacheFile(id)
        if (!file.exists()) return@withContext null
        runCatching { parse(file, content) }.getOrNull()
    }

    fun deleteCache(id: String) {
        cacheFile(id).delete()
    }

    private suspend fun parse(file: File, content: PlaylistContent): Epg {
        val now = System.currentTimeMillis()
        val windowStart = now - 3 * HOUR
        val windowEnd = now + 36 * HOUR

        // Correspondances possibles entre la playlist et le guide.
        val channelsByTvgId = HashMap<String, MutableList<Channel>>()
        val channelsByName = HashMap<String, MutableList<Channel>>()
        content.allLive.forEach { channel ->
            channel.tvgId?.lowercase()?.let { channelsByTvgId.getOrPut(it) { ArrayList(1) }.add(channel) }
            (listOfNotNull(channel.tvgName, channel.name).map(::normalize).toSet()).forEach { name ->
                if (name.isNotEmpty()) channelsByName.getOrPut(name) { ArrayList(1) }.add(channel)
            }
        }

        // id du guide → chaînes de la playlist
        val targets = HashMap<String, MutableSet<Channel>>()
        val matched = HashSet<String>()
        fun link(epgId: String, displayNames: List<String>) {
            val set = LinkedHashSet<Channel>()
            channelsByTvgId[epgId.lowercase()]?.let(set::addAll)
            // Par nom, pour les chaînes pas encore reliées au guide.
            displayNames.map(::normalize).forEach { name ->
                if (name.isEmpty()) return@forEach
                channelsByName[name]?.forEach { c -> if (c.key !in matched) set += c }
            }
            if (set.isNotEmpty()) {
                targets[epgId] = set
                set.forEach { matched += it.key }
            }
        }

        val result = HashMap<String, MutableList<Programme>>()
        file.inputStream().buffered(64 * 1024).use { input ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            var channelId: String? = null
            val displayNames = ArrayList<String>()
            var programmeChannel: String? = null
            var start = 0L
            var stop = 0L
            var title: String? = null
            var desc: String? = null
            var counter = 0

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (++counter % 5000 == 0) currentCoroutineContext().ensureActive()
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> {
                            channelId = parser.getAttributeValue(null, "id")
                            displayNames.clear()
                        }
                        "display-name" -> if (channelId != null) displayNames += parser.nextText().trim()
                        "programme" -> {
                            val id = parser.getAttributeValue(null, "channel")
                            val s = parseTime(parser.getAttributeValue(null, "start"))
                            val e = parseTime(parser.getAttributeValue(null, "stop"))
                            if (id != null && s != null && e != null && id in targets &&
                                e > windowStart && s < windowEnd
                            ) {
                                programmeChannel = id
                                start = s
                                stop = e
                                title = null
                                desc = null
                            } else {
                                programmeChannel = null
                            }
                        }
                        "title" -> if (programmeChannel != null && title == null) title = parser.nextText().trim()
                        "desc" -> if (programmeChannel != null && desc == null) {
                            desc = parser.nextText().trim().take(400)
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "channel" -> {
                            channelId?.let { link(it, displayNames) }
                            channelId = null
                        }
                        "programme" -> {
                            val id = programmeChannel
                            if (id != null) {
                                val programme = Programme(start, stop, title ?: "Sans titre", desc?.ifBlank { null })
                                targets[id]?.forEach { c ->
                                    result.getOrPut(c.key) { ArrayList() }.add(programme)
                                }
                            }
                            programmeChannel = null
                        }
                    }
                }
                event = parser.next()
            }
        }
        result.values.forEach { list -> list.sortBy { it.start } }
        return Epg(result)
    }

    companion object {
        private const val HOUR = 3_600_000L
        private val COUNTRY_PREFIX = Regex("""^[a-z]{2,3}\s*[:|\-]\s*""")
        private val QUALITY_SUFFIX = Regex("""(fhd|uhd|hd|sd|4k|hevc|h265|backup)+$""")
        private val NON_ALNUM = Regex("""[^a-z0-9]""")

        /** « FR| TF1 HD » et « TF1 » donnent la même clé. */
        fun normalize(name: String): String {
            val ascii = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return ascii.replace(COUNTRY_PREFIX, "")
                .replace(NON_ALNUM, "")
                .replace(QUALITY_SUFFIX, "")
        }

        /** Format XMLTV : 20261109203000 +0100 */
        fun parseTime(value: String?): Long? {
            val t = value?.trim() ?: return null
            if (t.length < 12) return null
            return try {
                val year = t.substring(0, 4).toInt()
                val month = t.substring(4, 6).toInt()
                val day = t.substring(6, 8).toInt()
                val hour = t.substring(8, 10).toInt()
                val minute = t.substring(10, 12).toInt()
                val hasSeconds = t.length >= 14 && t[12].isDigit() && t[13].isDigit()
                val second = if (hasSeconds) t.substring(12, 14).toInt() else 0
                val rest = t.substring(if (hasSeconds) 14 else 12).trim()
                var offset = 0
                if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                    val sign = if (rest[0] == '-') -1 else 1
                    offset = sign * (rest.substring(1, 3).toInt() * 3600 + rest.substring(3, 5).toInt() * 60)
                }
                LocalDateTime.of(year, month, day, hour, minute, second)
                    .toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000
            } catch (e: Exception) {
                null
            }
        }

        fun xtreamEpgUrl(playlistUrl: String): String? {
            val match = Regex("""^(https?://[^/]+)/get\.php\?(.*)$""", RegexOption.IGNORE_CASE)
                .find(playlistUrl.trim()) ?: return null
            val params = match.groupValues[2].split('&').associate {
                it.substringBefore('=') to it.substringAfter('=', "")
            }
            val user = params["username"] ?: return null
            val pass = params["password"] ?: return null
            return "${match.groupValues[1]}/xmltv.php?username=$user&password=$pass"
        }
    }
}
