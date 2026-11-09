package fr.nrobeveille.tviptv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.data.AspectMode
import fr.nrobeveille.tviptv.data.Channel
import fr.nrobeveille.tviptv.data.ContentType
import fr.nrobeveille.tviptv.data.Epg
import fr.nrobeveille.tviptv.ui.components.Artwork
import fr.nrobeveille.tviptv.ui.components.ChannelRow
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.ProgressLine
import fr.nrobeveille.tviptv.ui.components.SettingsRow
import fr.nrobeveille.tviptv.ui.components.awaitLayout
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.components.trapFocus
import fr.nrobeveille.tviptv.ui.home.rememberNow
import fr.nrobeveille.tviptv.ui.live.formatTime
import fr.nrobeveille.tviptv.ui.theme.Accent
import fr.nrobeveille.tviptv.ui.theme.Panel
import fr.nrobeveille.tviptv.ui.theme.SubtleText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Une piste audio ou de sous-titres sélectionnable. */
private class TrackChoice(val group: Tracks.Group, val index: Int, val label: String)

private fun choices(tracks: Tracks, type: Int): List<TrackChoice> =
    tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { i ->
            val format = group.getTrackFormat(i)
            val language = format.language?.takeIf { it != "und" }?.let {
                Locale.forLanguageTag(it).getDisplayLanguage(Locale.FRENCH).replaceFirstChar { c -> c.uppercase() }
            }
            val label = listOfNotNull(format.label, language).distinct().joinToString(" · ").ifBlank { "Piste ${i + 1}" }
            TrackChoice(group, i, label)
        }
    }

@Composable
fun PlayerScreen(vm: IptvViewModel, initialItems: List<Channel>, initialIndex: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now = rememberNow()

    var items by remember { mutableStateOf(initialItems) }
    var index by remember { mutableIntStateOf(initialIndex.coerceIn(0, initialItems.lastIndex)) }
    val channel = items[index]
    val isLive = channel.type == ContentType.LIVE

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }

    var showInfo by remember { mutableStateOf(false) }
    var infoTick by remember { mutableIntStateOf(0) }
    var showList by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var controlsTick by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var numberInput by remember { mutableStateOf("") }

    val rootFocus = remember { FocusRequester() }

    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)))
            .build()
            .apply { playWhenReady = true }
    }

    fun saveProgress(c: Channel) {
        if (c.type == ContentType.LIVE) return
        runCatching {
            val d = player.duration
            if (d > 0) vm.savePosition(c.key, player.currentPosition, d)
        }
    }

    fun start(c: Channel) {
        error = null
        httpFactory.setUserAgent(c.userAgent ?: vm.userAgent())
        httpFactory.setDefaultRequestProperties(c.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
        val item = MediaItem.Builder()
            .setUri(c.url)
            .apply { if (c.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        val resume = if (c.type == ContentType.LIVE) 0L else vm.positions.get(c.key)
        player.setMediaItem(item, resume)
        player.prepare()
        player.play()
        if (resume > 0) vm.toast("Reprise à ${formatDuration(resume)}")
        vm.onChannelWatched(c)
    }

    fun zap(delta: Int) {
        if (items.size < 2) return
        retries = 0
        index = (index + delta + items.size) % items.size
    }

    fun showControlsNow() {
        showControls = true
        controlsTick++
    }

    // Écouteur du lecteur ; libération en quittant l'écran.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    error = null
                    retries = 0
                }
                if (playbackState == Player.STATE_ENDED) {
                    val current = items[index]
                    if (current.type != ContentType.LIVE) {
                        saveProgress(current)
                        if (index < items.lastIndex) {
                            vm.toast("Épisode suivant")
                            index++
                        } else {
                            vm.back()
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (retries < 3) {
                    retries++
                    val attempt = retries
                    error = "Reconnexion… ($attempt/3)"
                    scope.launch {
                        delay(1_500L * attempt)
                        player.prepare()
                        player.play()
                    }
                    return
                }
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Flux refusé par le serveur (abonnement expiré ou trop d'écrans connectés ?)"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Connexion au flux impossible"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Flux introuvable"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        "Format vidéo non pris en charge par cet appareil"
                    else -> "Lecture impossible (${e.errorCodeName})"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            saveProgress(items[index])
            player.release()
        }
    }

    // Changement de chaîne / d'épisode.
    DisposableEffect(channel) {
        start(channel)
        if (channel.type == ContentType.LIVE) {
            showInfo = true
            infoTick++
        } else {
            showControlsNow()
        }
        onDispose { saveProgress(channel) }
    }

    // Pause quand on quitte l'appli (bouton Home), reprise au retour.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    saveProgress(items[index])
                    player.pause()
                }
                Lifecycle.Event.ON_START -> {
                    if (items[index].type == ContentType.LIVE) player.seekToDefaultPosition()
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sauvegarde régulière de la position (films / séries).
    LaunchedEffect(channel) {
        if (channel.type == ContentType.LIVE) return@LaunchedEffect
        while (true) {
            delay(15_000)
            saveProgress(channel)
        }
    }

    // Masquage automatique des bandeaux.
    LaunchedEffect(showInfo, infoTick) {
        if (showInfo) {
            delay(6_000)
            showInfo = false
        }
    }
    LaunchedEffect(showControls, controlsTick) {
        if (showControls) {
            delay(5_000)
            if (player.isPlaying) showControls = false
        }
    }
    LaunchedEffect(showControls) {
        while (showControls) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    // Saisie d'un numéro de chaîne au clavier numérique.
    LaunchedEffect(numberInput) {
        if (numberInput.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val number = numberInput.toIntOrNull()
        numberInput = ""
        if (number == null) return@LaunchedEffect
        val inList = items.indexOfFirst { it.number == number }
        if (inList >= 0) {
            index = inList
            return@LaunchedEffect
        }
        val content = vm.playlist.value.content ?: return@LaunchedEffect
        val target = content.allLive.firstOrNull { it.number == number }
        if (target == null) {
            vm.toast("Pas de chaîne n° $number")
            return@LaunchedEffect
        }
        val category = content.live.firstOrNull { it.name == target.group }?.items ?: listOf(target)
        items = category
        index = category.indexOf(target).coerceAtLeast(0)
    }

    // Le focus revient au lecteur dès qu'aucun menu n'est ouvert.
    LaunchedEffect(showList, showOptions) {
        if (!showList && !showOptions) {
            awaitLayout()
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler(enabled = showList || showOptions) {
        showList = false
        showOptions = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .trapFocus()
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { event ->
                if (showList || showOptions) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                if (code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 && isLive) {
                    if (numberInput.length < 5) numberInput += (code - AndroidKeyEvent.KEYCODE_0).toString()
                    return@onKeyEvent true
                }
                val center = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    code == AndroidKeyEvent.KEYCODE_ENTER || code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (error != null && center && retries >= 3) {
                    retries = 0
                    start(channel)
                    return@onKeyEvent true
                }
                if (isLive) {
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> showList = true
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_INFO -> {
                            showInfo = !showInfo
                            infoTick++
                        }
                        AndroidKeyEvent.KEYCODE_MENU -> showOptions = true
                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                        else -> if (center) showList = true else return@onKeyEvent false
                    }
                } else {
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                            showControlsNow()
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            val max = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            player.seekTo((player.currentPosition + 30_000).coerceAtMost(max - 1_000))
                            showControlsNow()
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> showControlsNow()
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU -> showOptions = true
                        AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> if (index < items.lastIndex) index++
                        AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (index > 0) index--
                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
                        AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (player.isPlaying) player.pause() else player.play()
                            showControlsNow()
                        }
                        else -> if (center) {
                            if (player.isPlaying) player.pause() else player.play()
                            showControlsNow()
                        } else {
                            return@onKeyEvent false
                        }
                    }
                }
                true
            }
    ) {
        val aspect = settings?.aspect ?: AspectMode.FIT
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setKeepContentOnPlayerReset(true)
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { view ->
                view.resizeMode = when (aspect) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---------------------------------------------- Messages
        error?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(channel.name, fontSize = 18.sp, color = SubtleText)
                Text(message, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                if (retries >= 3) {
                    Text(
                        if (isLive) "OK : réessayer · ▲▼ : autre chaîne · Paramètres : changer d'User-Agent" else "OK : réessayer",
                        fontSize = 14.sp,
                        color = SubtleText,
                    )
                }
            }
        }

        if (numberInput.isNotEmpty()) {
            Text(
                numberInput,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        // ---------------------------------------------- Bandeau d'info (direct)
        if (isLive && showInfo && !showList) {
            LiveInfoBanner(
                channel = channel,
                epg = epg,
                now = now,
                favorite = settings?.favorites?.contains(channel.key) == true,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ---------------------------------------------- Contrôles (films / séries)
        if (!isLive && (showControls || !isPlaying && !buffering) && !showOptions) {
            VodControls(
                channel = channel,
                position = position,
                duration = duration,
                playing = isPlaying,
                hasNext = index < items.lastIndex,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ---------------------------------------------- Liste des chaînes
        if (showList) {
            ChannelListOverlay(
                items = items,
                current = index,
                epg = epg,
                now = now,
                favorites = settings?.favorites.orEmpty(),
                onSelect = { i ->
                    showList = false
                    if (i != index) {
                        retries = 0
                        index = i
                    }
                },
                onToggleFavorite = { c -> vm.toggleFavorite(c.key, c.name) },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        // ---------------------------------------------- Options
        if (showOptions) {
            val audio = choices(tracks, C.TRACK_TYPE_AUDIO)
            val subtitles = choices(tracks, C.TRACK_TYPE_TEXT)
            val subtitlesOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            OptionsOverlay(
                aspect = aspect,
                audioLabel = audio.firstOrNull { it.group.isTrackSelected(it.index) }?.label,
                audioCount = audio.size,
                subtitleLabel = if (subtitlesOff) "Désactivés" else subtitles.firstOrNull { it.group.isTrackSelected(it.index) }?.label ?: "Désactivés",
                subtitleCount = subtitles.size,
                favorite = settings?.favorites?.contains(channel.key) == true,
                onAspect = { vm.cycleAspect() },
                onAudio = {
                    if (audio.size > 1) {
                        val current = audio.indexOfFirst { it.group.isTrackSelected(it.index) }
                        val next = audio[(current + 1) % audio.size]
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(next.group.mediaTrackGroup, next.index))
                            .build()
                    }
                },
                onSubtitles = {
                    if (subtitles.isNotEmpty()) {
                        val current = if (subtitlesOff) -1 else subtitles.indexOfFirst { it.group.isTrackSelected(it.index) }
                        val params = player.trackSelectionParameters.buildUpon()
                        if (current + 1 >= subtitles.size) {
                            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        } else {
                            val next = subtitles[current + 1]
                            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(next.group.mediaTrackGroup, next.index))
                        }
                        player.trackSelectionParameters = params.build()
                    }
                },
                onFavorite = { vm.toggleFavorite(channel.key, channel.name) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun LiveInfoBanner(channel: Channel, epg: Epg, now: Long, favorite: Boolean, modifier: Modifier = Modifier) {
    val current = epg.now(channel, now)
    val next = epg.next(channel, now)
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Artwork(channel.logo, channel.name, Modifier.width(150.dp).height(90.dp), fontSize = 22.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (channel.number > 0) Text("${channel.number}", fontSize = 26.sp, color = Accent, fontWeight = FontWeight.Bold)
                Text(channel.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (favorite) Text("★", fontSize = 22.sp, color = Accent)
            }
            if (current != null) {
                Text(
                    "${formatTime(current.start)} – ${formatTime(current.stop)}  ${current.title}",
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressLine(current.progressAt(now), Modifier.fillMaxWidth(0.5f), height = 4.dp)
            } else {
                Text(channel.group, fontSize = 16.sp, color = SubtleText)
            }
            if (next != null) {
                Text("Ensuite ${formatTime(next.start)}  ${next.title}", fontSize = 15.sp, color = SubtleText, maxLines = 1)
            }
            Text("▲▼ chaînes · ◀ ou OK liste · Menu : options", fontSize = 13.sp, color = SubtleText)
        }
    }
}

@Composable
private fun VodControls(
    channel: Channel,
    position: Long,
    duration: Long,
    playing: Boolean,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(channel.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ProgressLine(
            if (duration > 0) position.toFloat() / duration else 0f,
            Modifier.fillMaxWidth(),
            height = 6.dp,
        )
        Row {
            Text(
                (if (playing) "▶  " else "❚❚  ") + formatDuration(position),
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Text(if (duration > 0) formatDuration(duration) else "", fontSize = 16.sp, color = SubtleText)
        }
        Text(
            "OK : pause · ◀ −10 s · ▶ +30 s · ▼ : audio / sous-titres" + if (hasNext) " · ⏭ épisode suivant" else "",
            fontSize = 13.sp,
            color = SubtleText,
        )
    }
}

@Composable
private fun ChannelListOverlay(
    items: List<Channel>,
    current: Int,
    epg: Epg,
    now: Long,
    favorites: Set<String>,
    onSelect: (Int) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val registry = remember { FocusRegistry() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    Column(
        modifier
            .width(520.dp)
            .fillMaxHeight()
            .background(Panel.copy(alpha = 0.96f))
            .trapFocus()
            .padding(top = 28.dp, start = 16.dp, end = 16.dp),
    ) {
        Text(
            items.getOrNull(current)?.group ?: "",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text("OK : regarder · appui long : favori · Retour : fermer", fontSize = 13.sp, color = SubtleText, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            state = state,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 40.dp, start = 4.dp, end = 4.dp, top = 4.dp),
        ) {
            itemsIndexed(items, key = { i, c -> "$i|${c.url}" }) { i, c ->
                ChannelRow(
                    channel = c,
                    now = epg.now(c, now),
                    time = now,
                    favorite = c.key in favorites,
                    playing = i == current,
                    compact = true,
                    onClick = { onSelect(i) },
                    onLongClick = { onToggleFavorite(c) },
                    modifier = Modifier.tracked(registry, "pl:$i"),
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        awaitLayout()
        registry.request("pl:$current")
    }
}

@Composable
private fun OptionsOverlay(
    aspect: AspectMode,
    audioLabel: String?,
    audioCount: Int,
    subtitleLabel: String,
    subtitleCount: Int,
    favorite: Boolean,
    onAspect: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val registry = remember { FocusRegistry() }
    Column(
        modifier
            .width(480.dp)
            .fillMaxHeight()
            .background(Panel.copy(alpha = 0.96f))
            .trapFocus()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Options", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("OK pour changer · Retour pour fermer", fontSize = 13.sp, color = SubtleText)
        Spacer(Modifier.height(6.dp))
        SettingsRow(
            title = "Format de l'image",
            value = aspect.label,
            onClick = onAspect,
            modifier = Modifier.tracked(registry, "aspect"),
        )
        SettingsRow(
            title = "Piste audio",
            value = audioLabel ?: "Par défaut",
            subtitle = if (audioCount > 1) "$audioCount pistes disponibles" else "Une seule piste",
            onClick = onAudio,
        )
        SettingsRow(
            title = "Sous-titres",
            value = subtitleLabel,
            subtitle = if (subtitleCount > 0) "$subtitleCount disponibles" else "Aucun sous-titre",
            onClick = onSubtitles,
        )
        SettingsRow(
            title = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
            onClick = onFavorite,
        )
    }
    LaunchedEffect(Unit) {
        awaitLayout()
        registry.request("aspect")
    }
}
