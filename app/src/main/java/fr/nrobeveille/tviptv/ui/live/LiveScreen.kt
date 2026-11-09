package fr.nrobeveille.tviptv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.data.AppSettings
import fr.nrobeveille.tviptv.data.Channel
import fr.nrobeveille.tviptv.data.ContentType
import fr.nrobeveille.tviptv.data.Epg
import fr.nrobeveille.tviptv.data.PlaylistContent
import fr.nrobeveille.tviptv.ui.components.Artwork
import fr.nrobeveille.tviptv.ui.components.CategoryEntry
import fr.nrobeveille.tviptv.ui.components.CategoryList
import fr.nrobeveille.tviptv.ui.components.ChannelRow
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.MessageView
import fr.nrobeveille.tviptv.ui.components.ProgressLine
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.home.rememberNow
import fr.nrobeveille.tviptv.ui.theme.Accent
import fr.nrobeveille.tviptv.ui.theme.FaintText
import fr.nrobeveille.tviptv.ui.theme.Panel
import fr.nrobeveille.tviptv.ui.theme.SubtleText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val CAT_FAVORITES = "fav"
const val CAT_RECENTS = "recent"
const val CAT_ALL = "all"

/** Catégories de chaînes, avec les rubriques Favoris / Récentes / Toutes en tête. */
fun liveCategories(content: PlaylistContent, settings: AppSettings): List<Pair<CategoryEntry, List<Channel>>> =
    buildList {
        val favorites = content.allLive.filter { it.key in settings.favorites }
        if (favorites.isNotEmpty()) add(CategoryEntry(CAT_FAVORITES, "★ Favoris", favorites.size) to favorites)
        val recents = settings.recents.mapNotNull { content.channel(it) }.filter { it.type == ContentType.LIVE }
        if (recents.isNotEmpty()) add(CategoryEntry(CAT_RECENTS, "Récentes", recents.size) to recents)
        add(CategoryEntry(CAT_ALL, "Toutes les chaînes", content.allLive.size) to content.allLive)
        content.live.forEach { add(CategoryEntry("g:${it.name}", it.name, it.items.size) to it.items) }
    }

private val TIME = SimpleDateFormat("HH:mm", Locale.FRANCE)
fun formatTime(millis: Long): String = TIME.format(Date(millis))

@Composable
fun LiveScreen(vm: IptvViewModel, content: PlaylistContent, settings: AppSettings, registry: FocusRegistry) {
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now = rememberNow()
    val categories = remember(content, settings.favorites, settings.recents) { liveCategories(content, settings) }

    if (content.allLive.isEmpty()) {
        MessageView("Aucune chaîne", "Cette playlist ne contient que des films ou des séries.")
        return
    }

    var selected by remember {
        mutableStateOf(vm.liveCategory?.takeIf { key -> categories.any { it.first.key == key } } ?: CAT_ALL)
    }
    val current = categories.firstOrNull { it.first.key == selected } ?: categories.first { it.first.key == CAT_ALL }
    val channels = current.second
    var focused by remember { mutableStateOf<Channel?>(null) }

    Row(
        Modifier
            .fillMaxSize()
            .padding(top = 24.dp, start = 12.dp)
    ) {
        CategoryList(
            entries = categories.map { it.first },
            selected = current.first.key,
            onSelect = {
                selected = it
                vm.liveCategory = it
            },
            registry = registry,
            modifier = Modifier
                .width(270.dp)
                .fillMaxHeight(),
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 12.dp)
        ) {
            Text(current.first.label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                "${channels.size} chaînes · OK : regarder · appui long : favori",
                fontSize = 13.sp,
                color = SubtleText,
            )
            Spacer(Modifier.height(8.dp))
            // Nouvel état de défilement à chaque catégorie : on repart du haut.
            val listState = remember(current.first.key) { LazyListState() }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp, start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(channels, key = { i, c -> "$i|${c.url}" }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        now = epg.now(channel, now),
                        time = now,
                        favorite = channel.key in settings.favorites,
                        onClick = {
                            vm.liveCategory = current.first.key
                            vm.play(channels, index)
                        },
                        onLongClick = { vm.toggleFavorite(channel.key, channel.name) },
                        modifier = Modifier.tracked(registry, "ch:${current.first.key}:$index") {
                            focused = channel
                        },
                    )
                }
            }
        }

        ChannelInfoPanel(
            channel = focused?.takeIf { it in channels } ?: channels.firstOrNull(),
            epg = epg,
            now = now,
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight(),
        )
    }
}

@Composable
fun ChannelInfoPanel(channel: Channel?, epg: Epg, now: Long, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Panel)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (channel != null) ChannelInfo(channel, epg, now)
    }
}

@Composable
private fun ChannelInfo(channel: Channel, epg: Epg, now: Long) {
    Artwork(
        url = channel.logo,
        name = channel.name,
        fontSize = 28.sp,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        channel.name,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        buildString {
            if (channel.number > 0) append("N° ${channel.number} · ")
            append(channel.group)
        },
        fontSize = 13.sp,
        color = SubtleText,
        maxLines = 1,
    )
    val upcoming = epg.upcoming(channel, 5, now)
    if (upcoming.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Pas de guide TV pour cette chaîne", fontSize = 14.sp, color = FaintText)
        return
    }
    val first = upcoming.first()
    Spacer(Modifier.height(12.dp))
    Text(
        if (first.isOnAt(now)) "EN CE MOMENT" else "À SUIVRE",
        fontSize = 12.sp,
        color = Accent,
        fontWeight = FontWeight.SemiBold,
    )
    Text(first.title, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text("${formatTime(first.start)} – ${formatTime(first.stop)}", fontSize = 13.sp, color = SubtleText)
    if (first.isOnAt(now)) ProgressLine(first.progressAt(now), Modifier.fillMaxWidth())
    first.description?.let {
        Text(it, fontSize = 13.sp, color = SubtleText, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
    if (upcoming.size > 1) {
        Spacer(Modifier.height(10.dp))
        Text("ENSUITE", fontSize = 12.sp, color = Accent, fontWeight = FontWeight.SemiBold)
        upcoming.drop(1).forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatTime(p.start), fontSize = 14.sp, color = SubtleText)
                Text(p.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
