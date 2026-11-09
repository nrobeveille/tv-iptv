package fr.nrobeveille.tviptv

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.nrobeveille.tviptv.data.AppSettings
import fr.nrobeveille.tviptv.data.AspectMode
import fr.nrobeveille.tviptv.data.Channel
import fr.nrobeveille.tviptv.data.ContentType
import fr.nrobeveille.tviptv.data.Epg
import fr.nrobeveille.tviptv.data.EpgRepository
import fr.nrobeveille.tviptv.data.Http
import fr.nrobeveille.tviptv.data.PlaybackPositions
import fr.nrobeveille.tviptv.data.Playlist
import fr.nrobeveille.tviptv.data.PlaylistContent
import fr.nrobeveille.tviptv.data.PlaylistRepository
import fr.nrobeveille.tviptv.data.Series
import fr.nrobeveille.tviptv.data.SettingsRepository
import fr.nrobeveille.tviptv.update.UpdateEvents
import fr.nrobeveille.tviptv.update.UpdateException
import fr.nrobeveille.tviptv.update.UpdateInfo
import fr.nrobeveille.tviptv.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rubriques du menu de gauche. */
enum class Section(val label: String) {
    LIVE("TV en direct"),
    MOVIES("Films"),
    SERIES("Séries"),
    FAVORITES("Favoris"),
    SEARCH("Rechercher"),
    SETTINGS("Paramètres"),
}

/** Écrans de l'appli (navigation simple par pile, sans bibliothèque). */
sealed interface Screen {
    data object Home : Screen
    data class SeriesDetail(val seriesKey: String) : Screen
    data class Player(val items: List<Channel>, val index: Int) : Screen
    data class PlaylistEditor(val playlistId: String?) : Screen
    data object RemoteAdd : Screen
}

data class PlaylistState(
    val playlist: Playlist? = null,
    val content: PlaylistContent? = null,
    val loading: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
)

data class EpgState(
    val loading: Boolean = false,
    val url: String? = null,
    val error: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String, val info: UpdateInfo? = null) : UpdateState
}

class IptvViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    val settingsRepository = SettingsRepository(application)
    private val playlistRepository = PlaylistRepository(application)
    private val epgRepository = EpgRepository(application)
    val positions = PlaybackPositions(application)
    private val updater = Updater(application)

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    private val _playlist = MutableStateFlow(PlaylistState())
    val playlist: StateFlow<PlaylistState> = _playlist.asStateFlow()

    private val _epg = MutableStateFlow(Epg.EMPTY)
    val epg: StateFlow<Epg> = _epg.asStateFlow()

    private val _epgState = MutableStateFlow(EpgState())
    val epgState: StateFlow<EpgState> = _epgState.asStateFlow()

    private val _section = MutableStateFlow(Section.LIVE)
    val section: StateFlow<Section> = _section.asStateFlow()

    /** Pile de navigation : le dernier écran est affiché. */
    val backStack = mutableStateListOf<Screen>(Screen.Home)

    // Sélections mémorisées pour revenir au même endroit après la lecture.
    var liveCategory: String? = null
    var movieCategory: String? = null
    var seriesCategory: String? = null
    var lastFocusedKey: String? = null
    var searchQuery: String = ""

    private var playlistJob: Job? = null
    private var epgJob: Job? = null
    private var loadedPlaylist: Playlist? = null
    private var autoResumeDone = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _settings.value = s
                val active = s.activePlaylist
                if (active != loadedPlaylist) {
                    val onlyEpgChanged = active != null && loadedPlaylist?.let {
                        it.id == active.id && it.url == active.url
                    } == true
                    loadedPlaylist = active
                    if (onlyEpgChanged) {
                        _playlist.value = _playlist.value.copy(playlist = active)
                        _playlist.value.content?.let { loadEpg(active!!, it, force = true) }
                    } else {
                        loadPlaylist(active)
                    }
                }
            }
        }
        viewModelScope.launch {
            UpdateEvents.installError.collect { message ->
                if (message != null) {
                    UpdateEvents.clear()
                    _updateState.value = UpdateState.Error(message, lastInstallInfo)
                }
            }
        }
        checkForUpdate(silent = true)
    }

    // ---------------------------------------------------------------- Navigation

    val currentScreen: Screen get() = backStack.last()

    fun navigate(screen: Screen) {
        backStack.add(screen)
    }

    /** Renvoie false s'il n'y a plus rien à fermer (l'activité se ferme alors). */
    fun back(): Boolean {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        if (_section.value != Section.LIVE) {
            _section.value = Section.LIVE
            return true
        }
        return false
    }

    fun selectSection(section: Section) {
        _section.value = section
    }

    fun play(items: List<Channel>, index: Int) {
        if (items.isEmpty()) return
        // Pas d'empilement de lecteurs : on remplace celui en cours.
        if (currentScreen is Screen.Player) backStack.removeAt(backStack.lastIndex)
        navigate(Screen.Player(items, index.coerceIn(0, items.lastIndex)))
    }

    fun openSeries(series: Series) = navigate(Screen.SeriesDetail(series.key))

    /** Incrémenté à chaque position enregistrée, pour rafraîchir les barres de progression. */
    private val positionsVersion = mutableIntStateOf(0)

    /** Avancement (0..1) d'un film / épisode ; à lire depuis un composable. */
    fun progressOf(key: String): Float {
        positionsVersion.intValue
        return positions.progress(key) / 100f
    }

    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        positions.save(key, positionMs, durationMs)
        positionsVersion.intValue++
    }

    fun onChannelWatched(channel: Channel) {
        if (channel.type != ContentType.LIVE) return
        viewModelScope.launch { settingsRepository.addRecent(channel.key) }
    }

    fun toggleFavorite(key: String, label: String) {
        val wasFavorite = _settings.value?.favorites?.contains(key) == true
        viewModelScope.launch { settingsRepository.toggleFavorite(key) }
        toast(if (wasFavorite) "« $label » retiré des favoris" else "« $label » ajouté aux favoris")
    }

    fun toast(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- Playlist

    private fun loadPlaylist(playlist: Playlist?) {
        playlistJob?.cancel()
        epgJob?.cancel()
        _epg.value = Epg.EMPTY
        _epgState.value = EpgState()
        if (playlist == null) {
            _playlist.value = PlaylistState()
            return
        }
        _playlist.value = PlaylistState(playlist = playlist, loading = true, progress = "Ouverture de la playlist…")
        playlistJob = viewModelScope.launch {
            val cached = playlistRepository.loadCached(playlist.id)
            if (cached != null) {
                _playlist.value = PlaylistState(playlist = playlist, content = cached)
                onContentReady(playlist, cached)
                val age = System.currentTimeMillis() - (playlistRepository.lastUpdate(playlist.id) ?: 0L)
                if (age > PLAYLIST_MAX_AGE) downloadPlaylist(playlist, silent = true)
            } else {
                downloadPlaylist(playlist, silent = false)
            }
        }
    }

    fun refreshPlaylist() {
        val playlist = _playlist.value.playlist ?: return
        if (_playlist.value.loading) return
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch { downloadPlaylist(playlist, silent = false) }
    }

    private suspend fun downloadPlaylist(playlist: Playlist, silent: Boolean) {
        val previous = _playlist.value.content
        if (!silent || previous == null) {
            _playlist.value = _playlist.value.copy(loading = true, progress = "Téléchargement…", error = null)
        }
        try {
            val content = playlistRepository.download(playlist, userAgent()) { bytes ->
                if (!silent || previous == null) {
                    _playlist.value = _playlist.value.copy(progress = "Téléchargement… ${formatSize(bytes)}")
                }
            }
            _playlist.value = PlaylistState(playlist = playlist, content = content)
            if (!silent) {
                toast("Playlist à jour : ${content.allLive.size} chaînes, ${content.allMovies.size} films, ${content.allSeries.size} séries")
            }
            onContentReady(playlist, content, forceEpg = !silent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Téléchargement impossible"
            _playlist.value = _playlist.value.copy(loading = false, progress = null, error = message)
            if (previous != null && !silent) toast(message)
        }
    }

    private fun onContentReady(playlist: Playlist, content: PlaylistContent, forceEpg: Boolean = false) {
        loadEpg(playlist, content, force = forceEpg)
        maybeResumeLastChannel(content)
    }

    fun lastPlaylistUpdate(): Long? = _playlist.value.playlist?.let { playlistRepository.lastUpdate(it.id) }

    private fun maybeResumeLastChannel(content: PlaylistContent) {
        if (autoResumeDone) return
        autoResumeDone = true
        val s = _settings.value ?: return
        if (!s.resumeLastChannel || backStack.size > 1) return
        val channel = s.lastChannel?.let { content.channel(it) } ?: return
        if (channel.type != ContentType.LIVE) return
        val list = content.live.firstOrNull { it.name == channel.group }?.items ?: listOf(channel)
        liveCategory = channel.group
        play(list, list.indexOf(channel).coerceAtLeast(0))
    }

    // ---------------------------------------------------------------- Guide TV

    private fun loadEpg(playlist: Playlist, content: PlaylistContent, force: Boolean) {
        epgJob?.cancel()
        val url = epgRepository.resolveUrl(playlist, content)
        if (url == null) {
            _epg.value = Epg.EMPTY
            _epgState.value = EpgState(url = null)
            return
        }
        epgJob = viewModelScope.launch {
            _epgState.value = EpgState(loading = true, url = url)
            if (!force) {
                epgRepository.loadCached(playlist.id, content)?.let { _epg.value = it }
            }
            val age = System.currentTimeMillis() - (epgRepository.lastUpdate(playlist.id) ?: 0L)
            if (force || age > EPG_MAX_AGE || _epg.value.isEmpty) {
                try {
                    epgRepository.download(playlist, url, userAgent())
                    _epg.value = epgRepository.loadCached(playlist.id, content) ?: Epg.EMPTY
                    _epgState.value = EpgState(url = url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _epgState.value = EpgState(url = url, error = e.message ?: "Guide TV indisponible")
                }
            } else {
                _epgState.value = EpgState(url = url)
            }
        }
    }

    fun refreshEpg() {
        val state = _playlist.value
        val playlist = state.playlist ?: return
        val content = state.content ?: return
        loadEpg(playlist, content, force = true)
    }

    fun lastEpgUpdate(): Long? = _playlist.value.playlist?.let { epgRepository.lastUpdate(it.id) }

    // ---------------------------------------------------------------- Réglages

    fun savePlaylist(id: String?, name: String, url: String, epgUrl: String) {
        viewModelScope.launch {
            val existing = _settings.value?.playlists?.firstOrNull { it.id == id }
            if (existing != null && existing.url != url.trim()) playlistRepository.deleteCache(existing.id)
            if (existing != null && existing.epgUrl != epgUrl.trim()) epgRepository.deleteCache(existing.id)
            settingsRepository.savePlaylist(name, url, epgUrl, id)
        }
    }

    /** Playlist reçue depuis le téléphone. */
    fun addPlaylistFromPhone(name: String, url: String, epgUrl: String) {
        viewModelScope.launch(Dispatchers.Main) {
            settingsRepository.savePlaylist(name.ifBlank { "Ma playlist" }, url, epgUrl)
            toast("Playlist reçue, chargement…")
            if (currentScreen is Screen.RemoteAdd) back()
            _section.value = Section.LIVE
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            settingsRepository.deletePlaylist(id)
            playlistRepository.deleteCache(id)
            epgRepository.deleteCache(id)
        }
    }

    fun setActivePlaylist(id: String) {
        viewModelScope.launch { settingsRepository.setActivePlaylist(id) }
    }

    fun cycleUserAgent() {
        val names = Http.USER_AGENTS.keys.toList()
        val current = _settings.value?.userAgentName ?: Http.DEFAULT_USER_AGENT_NAME
        val next = names[(names.indexOf(current) + 1) % names.size]
        viewModelScope.launch { settingsRepository.setUserAgent(next) }
    }

    fun cycleAspect() {
        val values = AspectMode.entries
        val current = _settings.value?.aspect ?: AspectMode.FIT
        viewModelScope.launch { settingsRepository.setAspect(values[(current.ordinal + 1) % values.size]) }
    }

    fun toggleResumeLast() {
        val current = _settings.value?.resumeLastChannel ?: false
        viewModelScope.launch { settingsRepository.setResumeLast(!current) }
    }

    fun clearHistory() {
        viewModelScope.launch { settingsRepository.clearHistory() }
        toast("Historique effacé")
    }

    fun userAgent(): String = _settings.value?.userAgent ?: Http.USER_AGENTS.getValue(Http.DEFAULT_USER_AGENT_NAME)

    // ---------------------------------------------------------------- Mise à jour

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var updateJob: Job? = null
    private var lastInstallInfo: UpdateInfo? = null

    val versionName: String get() = updater.currentVersionName

    fun checkForUpdate(silent: Boolean = false) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            if (!silent) _updateState.value = UpdateState.Checking
            _updateState.value = try {
                val info = updater.findUpdate()
                if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: UpdateException) {
                if (silent) UpdateState.Idle else UpdateState.Error(e.message.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (silent) UpdateState.Idle else UpdateState.Error("Vérification impossible")
            }
        }
    }

    fun installUpdate(context: Context) {
        val info = when (val state = _updateState.value) {
            is UpdateState.Available -> state.info
            is UpdateState.Error -> state.info ?: return checkForUpdate()
            else -> return
        }
        if (!updater.canInstall()) {
            val opened = updater.openInstallPermissionScreen(context)
            _updateState.value = UpdateState.Error(
                if (opened) {
                    "Autorise « IPTV » à installer des applis, puis relance la mise à jour"
                } else {
                    "Autorise l'installation avec : adb shell appops set fr.nrobeveille.tviptv REQUEST_INSTALL_PACKAGES allow"
                },
                info,
            )
            return
        }
        if (updateJob?.isActive == true) return
        lastInstallInfo = info
        updateJob = viewModelScope.launch {
            try {
                _updateState.value = UpdateState.Downloading(info, 0f)
                val apk = updater.download(info) { progress ->
                    _updateState.value = UpdateState.Downloading(info, progress)
                }
                _updateState.value = UpdateState.Installing
                updater.install(apk)
            } catch (e: UpdateException) {
                _updateState.value = UpdateState.Error(e.message.orEmpty(), info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error("Échec du téléchargement", info)
            }
        }
    }

    // ---------------------------------------------------------------- Recherche

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.Default) {
        val content = _playlist.value.content ?: return@withContext emptyList()
        val q = query.trim().lowercase()
        if (q.length < 2) return@withContext emptyList()
        val results = ArrayList<SearchResult>()
        content.allLive.forEach { if (it.name.lowercase().contains(q)) results += SearchResult.Item(it) }
        content.allMovies.forEach { if (it.name.lowercase().contains(q)) results += SearchResult.Item(it) }
        content.allSeries.forEach { if (it.name.lowercase().contains(q)) results += SearchResult.Show(it) }
        // Les noms qui commencent par la recherche d'abord.
        results.sortedBy { if (it.title.lowercase().startsWith(q)) 0 else 1 }.take(300)
    }

    private fun formatSize(bytes: Long): String =
        if (bytes > 1_048_576) "%.1f Mo".format(bytes / 1_048_576f) else "${bytes / 1024} Ko"

    private companion object {
        const val PLAYLIST_MAX_AGE = 24 * 3_600_000L
        const val EPG_MAX_AGE = 12 * 3_600_000L
    }
}

sealed interface SearchResult {
    val title: String

    data class Item(val channel: Channel) : SearchResult {
        override val title: String get() = channel.name
    }

    data class Show(val series: Series) : SearchResult {
        override val title: String get() = series.name
    }
}
