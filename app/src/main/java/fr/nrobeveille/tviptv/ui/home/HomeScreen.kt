package fr.nrobeveille.tviptv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.Screen
import fr.nrobeveille.tviptv.Section
import fr.nrobeveille.tviptv.UpdateState
import fr.nrobeveille.tviptv.ui.components.Dot
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.MessageView
import fr.nrobeveille.tviptv.ui.components.awaitLayout
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.live.LiveScreen
import fr.nrobeveille.tviptv.ui.search.SearchScreen
import fr.nrobeveille.tviptv.ui.settings.SettingsScreen
import fr.nrobeveille.tviptv.ui.theme.Accent
import fr.nrobeveille.tviptv.ui.theme.FocusedText
import fr.nrobeveille.tviptv.ui.theme.Panel
import fr.nrobeveille.tviptv.ui.theme.SubtleText
import fr.nrobeveille.tviptv.ui.vod.FavoritesScreen
import fr.nrobeveille.tviptv.ui.vod.MoviesScreen
import fr.nrobeveille.tviptv.ui.vod.SeriesScreen
import kotlinx.coroutines.delay

/** Heure courante, rafraîchie toutes les 30 s (programmes en cours, barres de progression). */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

private fun Section.icon(): ImageVector = when (this) {
    Section.LIVE -> Icons.Filled.LiveTv
    Section.MOVIES -> Icons.Filled.Movie
    Section.SERIES -> Icons.Filled.VideoLibrary
    Section.FAVORITES -> Icons.Filled.Favorite
    Section.SEARCH -> Icons.Filled.Search
    Section.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun HomeScreen(vm: IptvViewModel, isTop: Boolean) {
    val section by vm.section.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.playlist.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val registry = remember { FocusRegistry() }

    if (settings == null) return // réglages pas encore lus : écran vide une fraction de seconde

    Row(Modifier.fillMaxSize()) {
        // ------------------------------------------------ Menu de gauche
        Column(
            modifier = Modifier
                .width(230.dp)
                .fillMaxHeight()
                .background(Panel)
                .padding(horizontal = 12.dp, vertical = 24.dp)
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "IPTV",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Accent,
                modifier = Modifier.padding(start = 12.dp),
            )
            Text(
                state.playlist?.name ?: "Aucune playlist",
                fontSize = 13.sp,
                color = SubtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, bottom = 18.dp),
            )
            Section.entries.forEach { item ->
                NavItem(
                    section = item,
                    selected = item == section,
                    badge = item == Section.SETTINGS &&
                        (update is UpdateState.Available || update is UpdateState.Downloading),
                    onClick = { vm.selectSection(item) },
                    modifier = Modifier.tracked(registry, "nav:${item.name}") { vm.selectSection(item) },
                )
            }
        }

        // ------------------------------------------------ Contenu
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            val content = state.content
            when {
                section == Section.SETTINGS -> SettingsScreen(vm, registry)
                state.playlist == null -> MessageView(
                    title = "Bienvenue !",
                    message = "Ajoute ton compte IPTV : l'adresse de ta playlist M3U " +
                        "(par exemple http://serveur:port/get.php?username=…&password=…&type=m3u_plus).",
                    actions = listOf(
                        "Depuis mon téléphone (recommandé)" to { vm.navigate(Screen.RemoteAdd) },
                        "Saisir avec la télécommande" to { vm.navigate(Screen.PlaylistEditor(null)) },
                    ),
                    firstActionModifier = Modifier.tracked(registry, "welcome"),
                )
                content == null && state.loading -> MessageView(
                    title = "Chargement de la playlist",
                    message = state.progress ?: "Patiente un instant…",
                )
                content == null -> MessageView(
                    title = "Impossible de charger la playlist",
                    message = state.error,
                    actions = listOf(
                        "Réessayer" to { vm.refreshPlaylist() },
                        "Modifier la playlist" to { vm.navigate(Screen.PlaylistEditor(state.playlist?.id)) },
                        "Changer d'User-Agent (${settings?.userAgentName})" to {
                            vm.cycleUserAgent()
                            vm.refreshPlaylist()
                        },
                    ),
                    firstActionModifier = Modifier.tracked(registry, "retry"),
                )
                else -> when (section) {
                    Section.LIVE -> LiveScreen(vm, content, settings!!, registry)
                    Section.MOVIES -> MoviesScreen(vm, content, settings!!, registry)
                    Section.SERIES -> SeriesScreen(vm, content, settings!!, registry)
                    Section.FAVORITES -> FavoritesScreen(vm, content, settings!!, registry)
                    Section.SEARCH -> SearchScreen(vm, settings!!, registry)
                    Section.SETTINGS -> Unit
                }
            }
            if (content != null && state.loading) {
                Text(
                    state.progress ?: "Mise à jour…",
                    fontSize = 13.sp,
                    color = SubtleText,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
        }
    }

    // Focus au démarrage et en revenant d'un autre écran (lecteur, série…).
    LaunchedEffect(isTop) {
        if (!isTop) return@LaunchedEffect
        awaitLayout()
        if (!registry.restore()) {
            if (!registry.request("welcome")) registry.request("nav:${section.name}")
        }
    }
}

@Composable
private fun NavItem(
    section: Section,
    selected: Boolean,
    badge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent,
            contentColor = if (selected) Color.White else SubtleText,
            focusedContainerColor = Color.White,
            focusedContentColor = FocusedText,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = section.icon(),
                contentDescription = null,
                tint = if (selected) Accent else LocalContentColor.current,
                modifier = Modifier.size(24.dp),
            )
            Text(
                section.label,
                fontSize = 17.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (badge) Dot()
        }
    }
    Spacer(Modifier.height(0.dp))
}
