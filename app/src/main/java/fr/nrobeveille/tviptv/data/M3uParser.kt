package fr.nrobeveille.tviptv.data

import java.io.BufferedReader

/**
 * Lit une playlist M3U / M3U8 « étendue » (format IPTV) :
 *
 * ```
 * #EXTM3U url-tvg="http://…/epg.xml"
 * #EXTINF:-1 tvg-id="tf1.fr" tvg-name="TF1" tvg-logo="http://…" group-title="France",TF1 HD
 * #EXTVLCOPT:http-user-agent=Mozilla/5.0
 * http://serveur/live/user/pass/1234.ts
 * ```
 *
 * Les comptes Xtream exportés en M3U (`get.php?…&type=m3u_plus`) mélangent chaînes, films
 * (`/movie/` dans l'URL) et épisodes de séries (`/series/`) : on les répartit automatiquement.
 */
object M3uParser {

    private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val EPISODE_SXXEXX = Regex("""(?i)^(.*?)[\s._\-:|]*\bS(\d{1,2})[\s._\-]*E(\d{1,4})\b""")
    private val EPISODE_FR = Regex("""(?i)^(.*?)[\s._\-:|]*\bSaison\s*(\d{1,2})[\s._\-,]*[ÉE]pisode\s*(\d{1,4})""")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "webm", "mpg", "mpeg")
    const val NO_GROUP = "Sans catégorie"

    fun parse(reader: BufferedReader): PlaylistContent {
        var headerEpg: String? = null

        val live = LinkedHashMap<String, MutableList<Channel>>()
        val movies = LinkedHashMap<String, MutableList<Channel>>()
        // Séries : catégorie → nom de série → épisodes
        val series = LinkedHashMap<String, LinkedHashMap<String, MutableList<Channel>>>()

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var pendingUserAgent: String? = null
        var pendingReferrer: String? = null
        var liveNumber = 0
        var first = true

        while (true) {
            var line = reader.readLine() ?: break
            if (first) {
                line = line.removePrefix("﻿")
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    headerEpg = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"])
                        ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim()
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val value = option.substringAfter('=', "").trim()
                    when (option.substringBefore('=').trim().lowercase()) {
                        "http-user-agent" -> pendingUserAgent = value
                        "http-referrer", "http-referer" -> pendingReferrer = value
                    }
                }
                line.startsWith("#") -> Unit // #KODIPROP, commentaires…
                else -> {
                    val url = line
                    val info = pendingInfo
                    val attrs = if (info != null) attributes(info) else emptyMap()
                    val title = info?.let { titleOf(it) }?.takeIf { it.isNotBlank() }
                        ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        ?: url.substringAfterLast('/').substringBefore('?')
                    val group = (attrs["group-title"] ?: pendingGroup)?.trim()?.takeIf { it.isNotEmpty() } ?: NO_GROUP
                    val type = typeOf(url, title)
                    val base = Channel(
                        name = title,
                        url = url,
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        group = group,
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
                        type = type,
                        userAgent = pendingUserAgent ?: attrs["user-agent"]?.takeIf { it.isNotBlank() },
                        referrer = pendingReferrer ?: attrs["referrer"]?.takeIf { it.isNotBlank() },
                    )
                    when (type) {
                        ContentType.LIVE -> {
                            liveNumber++
                            live.getOrPut(group) { ArrayList() }.add(base.copy(number = liveNumber))
                        }
                        ContentType.MOVIE -> movies.getOrPut(group) { ArrayList() }.add(base)
                        ContentType.SERIES -> {
                            val episode = episodeInfo(base)
                            series.getOrPut(group) { LinkedHashMap() }
                                .getOrPut(episode.seriesName!!) { ArrayList() }
                                .add(episode)
                        }
                    }
                    pendingInfo = null
                    pendingGroup = null
                    pendingUserAgent = null
                    pendingReferrer = null
                }
            }
        }

        return PlaylistContent(
            live = live.map { (name, items) -> Category(name, items) },
            movies = movies.map { (name, items) -> Category(name, items) },
            series = series.map { (group, shows) ->
                Category(group, shows.map { (name, episodes) ->
                    val sorted = numberEpisodes(episodes)
                    Series(
                        name = name,
                        group = group,
                        poster = episodes.firstNotNullOfOrNull { it.logo },
                        episodes = sorted,
                    )
                })
            },
            headerEpgUrl = headerEpg,
        )
    }

    /** Attributs `clé="valeur"` d'une ligne #EXTINF / #EXTM3U. */
    private fun attributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        // On ne cherche les attributs que dans la partie avant le titre.
        val head = titleStart(line)?.let { line.substring(0, it) } ?: line
        ATTRIBUTE.findAll(head).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
        return map
    }

    /** Le titre est après la première virgule située hors des guillemets. */
    private fun titleOf(line: String): String? = titleStart(line)?.let { line.substring(it + 1).trim() }

    private fun titleStart(line: String): Int? {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return null
    }

    private fun typeOf(url: String, title: String): ContentType {
        val path = url.substringBefore('?').lowercase()
        if (path.contains("/movie/") || path.contains("/movies/")) return ContentType.MOVIE
        if (path.contains("/series/")) return ContentType.SERIES
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        if (extension in VIDEO_EXTENSIONS) {
            return if (EPISODE_SXXEXX.containsMatchIn(title) || EPISODE_FR.containsMatchIn(title)) {
                ContentType.SERIES
            } else {
                ContentType.MOVIE
            }
        }
        return ContentType.LIVE
    }

    private fun episodeInfo(channel: Channel): Channel {
        val match = EPISODE_SXXEXX.find(channel.name) ?: EPISODE_FR.find(channel.name)
        if (match != null) {
            val name = match.groupValues[1].trim().trimEnd('-', '_', '.', ':', '|', ' ').ifBlank { channel.name }
            return channel.copy(
                seriesName = name,
                season = match.groupValues[2].toIntOrNull() ?: 1,
                episode = match.groupValues[3].toIntOrNull() ?: 0,
            )
        }
        return channel.copy(seriesName = channel.name, season = 1, episode = 0)
    }

    /** Trie les épisodes et numérote ceux qui n'ont pas de numéro lisible. */
    private fun numberEpisodes(episodes: List<Channel>): List<Channel> {
        var counter = 0
        return episodes
            .map { if (it.episode == 0) it.copy(episode = ++counter) else it }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }
}
