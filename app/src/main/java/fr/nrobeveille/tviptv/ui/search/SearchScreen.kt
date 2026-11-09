package fr.nrobeveille.tviptv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.SearchResult
import fr.nrobeveille.tviptv.data.AppSettings
import fr.nrobeveille.tviptv.data.ContentType
import fr.nrobeveille.tviptv.ui.components.Artwork
import fr.nrobeveille.tviptv.ui.components.ChannelRow
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.SettingsRow
import fr.nrobeveille.tviptv.ui.components.TextEditDialog
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.home.rememberNow
import fr.nrobeveille.tviptv.ui.theme.SubtleText
import fr.nrobeveille.tviptv.ui.vod.seasonsLabel

@Composable
fun SearchScreen(vm: IptvViewModel, settings: AppSettings, registry: FocusRegistry) {
    val epg by vm.epg.collectAsStateWithLifecycle()
    val state by vm.playlist.collectAsStateWithLifecycle()
    val now = rememberNow()
    var query by remember { mutableStateOf(vm.searchQuery) }
    var editing by remember { mutableStateOf(false) }

    val results by produceState<List<SearchResult>?>(initialValue = null, query, state.content) {
        value = if (query.trim().length < 2) null else vm.search(query)
    }
    val liveResults = remember(results) {
        results.orEmpty().filterIsInstance<SearchResult.Item>().map { it.channel }.filter { it.type == ContentType.LIVE }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Rechercher", fontSize = 28.sp, fontWeight = FontWeight.SemiBold) }
        item {
            SettingsRow(
                title = query.ifBlank { "Chaîne, film ou série…" },
                subtitle = "OK pour saisir",
                highlighted = true,
                onClick = { editing = true },
                modifier = Modifier.tracked(registry, "search:field"),
            )
        }
        val list = results
        when {
            list == null -> item {
                Text("Tape au moins 2 lettres.", fontSize = 15.sp, color = SubtleText)
            }
            list.isEmpty() -> item {
                Text("Aucun résultat pour « $query ».", fontSize = 15.sp, color = SubtleText)
            }
            else -> {
                item { Text("${list.size} résultat${if (list.size > 1) "s" else ""}", fontSize = 14.sp, color = SubtleText) }
                itemsIndexed(list, key = { i, r -> "$i|${r.title}" }) { index, result ->
                    val modifier = Modifier.tracked(registry, "search:$index")
                    when (result) {
                        is SearchResult.Item -> {
                            val c = result.channel
                            if (c.type == ContentType.LIVE) {
                                ChannelRow(
                                    channel = c,
                                    now = epg.now(c, now),
                                    time = now,
                                    favorite = c.key in settings.favorites,
                                    onClick = { vm.play(liveResults, liveResults.indexOf(c)) },
                                    onLongClick = { vm.toggleFavorite(c.key, c.name) },
                                    modifier = modifier,
                                )
                            } else {
                                SettingsRow(
                                    title = c.name,
                                    subtitle = (if (c.type == ContentType.MOVIE) "Film" else "Épisode") + " · ${c.group}",
                                    onClick = { vm.play(listOf(c), 0) },
                                    onLongClick = { vm.toggleFavorite(c.key, c.name) },
                                    modifier = modifier,
                                    leading = {
                                        Artwork(c.logo, c.name, Modifier.width(40.dp).height(56.dp), ContentScale.Crop, 12.sp)
                                    },
                                )
                            }
                        }
                        is SearchResult.Show -> SettingsRow(
                            title = result.series.name,
                            subtitle = "Série · " + seasonsLabel(result.series.seasons.size, result.series.episodes.size),
                            chevron = true,
                            onClick = { vm.openSeries(result.series) },
                            onLongClick = { vm.toggleFavorite(result.series.key, result.series.name) },
                            modifier = modifier,
                            leading = {
                                Artwork(
                                    result.series.poster,
                                    result.series.name,
                                    Modifier.width(40.dp).height(56.dp),
                                    ContentScale.Crop,
                                    12.sp,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (editing) {
        TextEditDialog(
            title = "Rechercher",
            initial = query,
            onDone = {
                query = it
                vm.searchQuery = it
            },
            onDismiss = { editing = false },
        )
    }
}
