package fr.nrobeveille.tviptv.ui.vod

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.data.Channel
import fr.nrobeveille.tviptv.ui.components.Artwork
import fr.nrobeveille.tviptv.ui.components.Button
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.MessageView
import fr.nrobeveille.tviptv.ui.components.ProgressLine
import fr.nrobeveille.tviptv.ui.components.awaitLayout
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.components.trapFocus
import fr.nrobeveille.tviptv.ui.theme.AccentSoft
import fr.nrobeveille.tviptv.ui.theme.FocusedText
import fr.nrobeveille.tviptv.ui.theme.SubtleText

@Composable
fun SeriesDetailScreen(vm: IptvViewModel, seriesKey: String, isTop: Boolean) {
    val state by vm.playlist.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val series = state.content?.series(seriesKey)
    val registry = remember { FocusRegistry() }

    if (series == null) {
        MessageView(
            "Série introuvable",
            "Elle n'est plus dans la playlist.",
            actions = listOf("Retour" to { vm.back(); Unit }),
            firstActionModifier = Modifier.tracked(registry, "back"),
        )
        LaunchedEffect(isTop) { if (isTop) { awaitLayout(); registry.request("back") } }
        return
    }

    var season by remember { mutableIntStateOf(series.seasons.firstOrNull() ?: 1) }
    val episodes = remember(series, season) { series.episodesOf(season) }
    val favorite = settings?.favorites?.contains(series.key) == true

    Row(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .padding(40.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        Column(
            Modifier
                .width(260.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Artwork(
                url = series.poster,
                name = series.name,
                contentScale = ContentScale.Crop,
                fontSize = 30.sp,
                modifier = Modifier
                    .width(260.dp)
                    .height(390.dp),
            )
            Text(series.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
            Text(seasonsLabel(series.seasons.size, series.episodes.size), fontSize = 14.sp, color = SubtleText)
            Text(series.group, fontSize = 13.sp, color = SubtleText, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Button(
                label = if (favorite) "★ Retirer des favoris" else "☆ Ajouter aux favoris",
                onClick = { vm.toggleFavorite(series.key, series.name) },
                modifier = Modifier
                    .fillMaxWidth()
                    .tracked(registry, "fav"),
            )
        }

        Column(Modifier.weight(1f)) {
            if (series.seasons.size > 1) {
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    items(series.seasons, key = { it }) { s ->
                        val selected = s == season
                        Surface(
                            onClick = { season = s },
                            modifier = Modifier
                                .height(44.dp)
                                .tracked(registry, "season:$s") { season = s },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(22.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selected) AccentSoft else Color.White.copy(alpha = 0.06f),
                                contentColor = Color.White,
                                focusedContainerColor = Color.White,
                                focusedContentColor = FocusedText,
                            ),
                        ) {
                            Text(
                                "Saison $s",
                                fontSize = 16.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            val listState = remember(season) { LazyListState() }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                itemsIndexed(episodes, key = { i, e -> "$i|${e.url}" }) { index, episode ->
                    EpisodeRow(
                        episode = episode,
                        seriesName = series.name,
                        progress = vm.progressOf(episode.key),
                        onClick = { vm.play(episodes, index) },
                        modifier = Modifier.tracked(registry, "ep:$season:$index"),
                    )
                }
            }
        }
    }

    LaunchedEffect(isTop) {
        if (!isTop) return@LaunchedEffect
        awaitLayout()
        if (!registry.restore()) registry.request("ep:$season:0")
    }
}

@Composable
private fun EpisodeRow(
    episode: Channel,
    seriesName: String,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // « Ma série S01 E03 Le titre » → « Le titre »
    val title = episode.name
        .removePrefix(seriesName)
        .replace(Regex("""(?i)^[\s._\-:|]*(S\d{1,2}[\s._\-]*E\d{1,4}|Saison\s*\d+[\s._\-,]*[ÉE]pisode\s*\d+)[\s._\-:|]*"""), "")
        .ifBlank { "Épisode ${episode.episode}" }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = FocusedText,
        ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "E%02d".format(episode.episode),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(52.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (progress > 0f) {
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(progress, Modifier.fillMaxWidth(0.5f))
                }
            }
            if (progress > 0f) {
                Text("${(progress * 100).toInt()} %", fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = 0.6f))
            }
        }
    }
}
