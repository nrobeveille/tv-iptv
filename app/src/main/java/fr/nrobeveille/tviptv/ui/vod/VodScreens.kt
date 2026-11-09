package fr.nrobeveille.tviptv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.data.AppSettings
import fr.nrobeveille.tviptv.data.Category
import fr.nrobeveille.tviptv.data.PlaylistContent
import fr.nrobeveille.tviptv.ui.components.CategoryEntry
import fr.nrobeveille.tviptv.ui.components.CategoryList
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.MessageView
import fr.nrobeveille.tviptv.ui.components.PosterCard
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.theme.SubtleText

private const val ALL = "all"
private const val FAV = "fav"

/** Catégories avec « Favoris » et « Tout » en tête. */
private fun <T> withVirtual(
    categories: List<Category<T>>,
    all: List<T>,
    allLabel: String,
    isFavorite: (T) -> Boolean,
): List<Pair<CategoryEntry, List<T>>> = buildList {
    val favorites = all.filter(isFavorite)
    if (favorites.isNotEmpty()) add(CategoryEntry(FAV, "★ Favoris", favorites.size) to favorites)
    add(CategoryEntry(ALL, allLabel, all.size) to all)
    categories.forEach { add(CategoryEntry("g:${it.name}", it.name, it.items.size) to it.items) }
}

/** Disposition commune : catégories à gauche, grille d'affiches à droite. */
@Composable
private fun <T> PosterBrowser(
    categories: List<Pair<CategoryEntry, List<T>>>,
    initialCategory: String?,
    onCategory: (String) -> Unit,
    registry: FocusRegistry,
    keyPrefix: String,
    unit: String,
    itemKey: (T) -> String,
    card: @Composable (index: Int, item: T, items: List<T>, modifier: Modifier) -> Unit,
) {
    var selected by remember {
        mutableStateOf(initialCategory?.takeIf { key -> categories.any { it.first.key == key } } ?: ALL)
    }
    val current = categories.firstOrNull { it.first.key == selected } ?: categories.first { it.first.key == ALL }
    val items = current.second

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
                onCategory(it)
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
                .padding(horizontal = 16.dp)
        ) {
            Text(current.first.label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${items.size} $unit · appui long : favori", fontSize = 13.sp, color = SubtleText)
            Spacer(Modifier.height(8.dp))
            val gridState = remember(current.first.key) { LazyGridState() }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(items, key = { i, item -> "$i|${itemKey(item)}" }) { index, item ->
                    card(index, item, items, Modifier.tracked(registry, "$keyPrefix:${current.first.key}:$index"))
                }
            }
        }
    }
}

@Composable
fun MoviesScreen(vm: IptvViewModel, content: PlaylistContent, settings: AppSettings, registry: FocusRegistry) {
    if (content.allMovies.isEmpty()) {
        MessageView("Aucun film", "Cette playlist ne contient pas de films (VOD).")
        return
    }
    val categories = remember(content, settings.favorites) {
        withVirtual(content.movies, content.allMovies, "Tous les films") { it.key in settings.favorites }
    }
    PosterBrowser(
        categories = categories,
        initialCategory = vm.movieCategory,
        onCategory = { vm.movieCategory = it },
        registry = registry,
        keyPrefix = "mv",
        unit = "films",
        itemKey = { it.url },
    ) { _, movie, _, modifier ->
        PosterCard(
            title = movie.name,
            image = movie.logo,
            onClick = { vm.play(listOf(movie), 0) },
            onLongClick = { vm.toggleFavorite(movie.key, movie.name) },
            progress = vm.progressOf(movie.key),
            favorite = movie.key in settings.favorites,
            modifier = modifier,
        )
    }
}

@Composable
fun SeriesScreen(vm: IptvViewModel, content: PlaylistContent, settings: AppSettings, registry: FocusRegistry) {
    if (content.allSeries.isEmpty()) {
        MessageView("Aucune série", "Cette playlist ne contient pas de séries.")
        return
    }
    val categories = remember(content, settings.favorites) {
        withVirtual(content.series, content.allSeries, "Toutes les séries") { it.key in settings.favorites }
    }
    PosterBrowser(
        categories = categories,
        initialCategory = vm.seriesCategory,
        onCategory = { vm.seriesCategory = it },
        registry = registry,
        keyPrefix = "sr",
        unit = "séries",
        itemKey = { it.key },
    ) { _, series, _, modifier ->
        PosterCard(
            title = series.name,
            image = series.poster,
            onClick = { vm.openSeries(series) },
            onLongClick = { vm.toggleFavorite(series.key, series.name) },
            favorite = series.key in settings.favorites,
            caption = seasonsLabel(series.seasons.size, series.episodes.size),
            modifier = modifier,
        )
    }
}

fun seasonsLabel(seasons: Int, episodes: Int): String =
    (if (seasons > 1) "$seasons saisons · " else "") + "$episodes épisode" + (if (episodes > 1) "s" else "")
