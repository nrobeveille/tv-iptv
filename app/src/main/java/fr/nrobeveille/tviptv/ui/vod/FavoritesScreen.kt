package fr.nrobeveille.tviptv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.data.AppSettings
import fr.nrobeveille.tviptv.data.PlaylistContent
import fr.nrobeveille.tviptv.ui.components.ChannelRow
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.MessageView
import fr.nrobeveille.tviptv.ui.components.PosterCard
import fr.nrobeveille.tviptv.ui.components.SectionHeader
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.home.rememberNow

@Composable
fun FavoritesScreen(vm: IptvViewModel, content: PlaylistContent, settings: AppSettings, registry: FocusRegistry) {
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now = rememberNow()
    val channels = remember(content, settings.favorites) { content.allLive.filter { it.key in settings.favorites } }
    val movies = remember(content, settings.favorites) { content.allMovies.filter { it.key in settings.favorites } }
    val shows = remember(content, settings.favorites) { content.allSeries.filter { it.key in settings.favorites } }

    if (channels.isEmpty() && movies.isEmpty() && shows.isEmpty()) {
        MessageView(
            "Aucun favori",
            "Fais un appui long sur OK sur une chaîne, un film ou une série pour l'ajouter ici.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Favoris", fontSize = 28.sp, fontWeight = FontWeight.SemiBold) }
        if (channels.isNotEmpty()) {
            item { SectionHeader("Chaînes") }
            itemsIndexed(channels, key = { i, c -> "c$i|${c.url}" }) { index, channel ->
                ChannelRow(
                    channel = channel,
                    now = epg.now(channel, now),
                    time = now,
                    favorite = true,
                    onClick = { vm.play(channels, index) },
                    onLongClick = { vm.toggleFavorite(channel.key, channel.name) },
                    modifier = Modifier.tracked(registry, "favch:$index"),
                )
            }
        }
        if (movies.isNotEmpty()) {
            item { SectionHeader("Films") }
            item {
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    itemsIndexed(movies, key = { i, m -> "m$i|${m.url}" }) { index, movie ->
                        PosterCard(
                            title = movie.name,
                            image = movie.logo,
                            onClick = { vm.play(listOf(movie), 0) },
                            onLongClick = { vm.toggleFavorite(movie.key, movie.name) },
                            progress = vm.progressOf(movie.key),
                            favorite = true,
                            modifier = Modifier
                                .width(150.dp)
                                .tracked(registry, "favmv:$index"),
                        )
                    }
                }
            }
        }
        if (shows.isNotEmpty()) {
            item { SectionHeader("Séries") }
            item {
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    itemsIndexed(shows, key = { i, s -> "s$i|${s.key}" }) { index, series ->
                        PosterCard(
                            title = series.name,
                            image = series.poster,
                            onClick = { vm.openSeries(series) },
                            onLongClick = { vm.toggleFavorite(series.key, series.name) },
                            favorite = true,
                            caption = seasonsLabel(series.seasons.size, series.episodes.size),
                            modifier = Modifier
                                .width(150.dp)
                                .padding(0.dp)
                                .tracked(registry, "favsr:$index"),
                        )
                    }
                }
            }
        }
    }
}
