package fr.nrobeveille.tviptv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.Screen
import fr.nrobeveille.tviptv.UpdateState
import fr.nrobeveille.tviptv.data.Playlist
import fr.nrobeveille.tviptv.ui.components.ActionMenu
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.MenuAction
import fr.nrobeveille.tviptv.ui.components.SectionHeader
import fr.nrobeveille.tviptv.ui.components.SettingsRow
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.home.rememberNow

private class Entry(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    val chevron: Boolean = false,
    val highlighted: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** « il y a 3 h », « il y a 2 j »… */
fun ago(time: Long?, now: Long = System.currentTimeMillis()): String {
    if (time == null) return "jamais"
    val minutes = (now - time) / 60_000
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 48 * 60 -> "il y a ${minutes / 60} h"
        else -> "il y a ${minutes / (24 * 60)} j"
    }
}

/** Montre le serveur sans les identifiants : http://serveur:8080/… */
fun hostOf(url: String): String =
    Regex("""^(https?://[^/?]+)""").find(url.trim())?.groupValues?.get(1)?.let { "$it/…" } ?: url

@Composable
fun SettingsScreen(vm: IptvViewModel, registry: FocusRegistry) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.playlist.collectAsStateWithLifecycle()
    val epgState by vm.epgState.collectAsStateWithLifecycle()
    val epg by vm.epg.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val now = rememberNow()
    val s = settings ?: return
    var menuFor by remember { mutableStateOf<Playlist?>(null) }
    var confirmDelete by remember { mutableStateOf<Playlist?>(null) }

    val version = vm.versionName
    val installed = "Version installée : $version"
    val updateEntry = when (val u = update) {
        UpdateState.Idle -> Entry("update", "Rechercher une mise à jour", installed, onClick = { vm.checkForUpdate() })
        UpdateState.Checking -> Entry("update", "Recherche d'une mise à jour…", installed, onClick = {})
        UpdateState.UpToDate -> Entry(
            "update", "Rechercher une mise à jour", "Tu as la dernière version ($version)",
            onClick = { vm.checkForUpdate() },
        )
        is UpdateState.Available -> Entry(
            "update", "Installer la version ${u.info.versionName}", "Nouvelle version disponible · $installed",
            highlighted = true, onClick = { vm.installUpdate(context) },
        )
        is UpdateState.Downloading -> Entry(
            "update", "Téléchargement… ${(u.progress * 100).toInt()} %", "Version ${u.info.versionName}",
            highlighted = true, onClick = {},
        )
        UpdateState.Installing -> Entry(
            "update", "Installation…", "L'appli va redémarrer toute seule", highlighted = true, onClick = {},
        )
        is UpdateState.Error -> Entry(
            "update",
            if (u.info != null) "Réessayer l'installation" else "Rechercher une mise à jour",
            u.message,
            onClick = { if (u.info != null) vm.installUpdate(context) else vm.checkForUpdate() },
        )
    }

    val active = s.activePlaylist
    val content = state.content
    val playlistEntries = s.playlists.map { p ->
        val isActive = p.id == active?.id
        Entry(
            key = "pl:${p.id}",
            title = p.name,
            subtitle = hostOf(p.url) + if (isActive) " · OK : modifier" else " · OK : utiliser cette playlist",
            value = if (isActive) "✓ Active" else null,
            highlighted = isActive,
            onLongClick = { menuFor = p },
            onClick = {
                if (isActive) vm.navigate(Screen.PlaylistEditor(p.id)) else vm.setActivePlaylist(p.id)
            },
        )
    } + listOf(
        Entry(
            "add_phone", "Ajouter une playlist depuis le téléphone",
            "Scanne un QR code et colle l'adresse sur ton téléphone", chevron = true,
            onClick = { vm.navigate(Screen.RemoteAdd) },
        ),
        Entry(
            "add_remote", "Ajouter une playlist avec la télécommande", chevron = true,
            onClick = { vm.navigate(Screen.PlaylistEditor(null)) },
        ),
    )

    val contentEntries = if (active == null) emptyList() else listOf(
        Entry(
            "refresh", if (state.loading) (state.progress ?: "Mise à jour…") else "Actualiser la playlist",
            subtitle = buildString {
                append("Mise à jour ${ago(vm.lastPlaylistUpdate(), now)}")
                if (content != null) {
                    append(" · ${content.allLive.size} chaînes · ${content.allMovies.size} films · ${content.allSeries.size} séries")
                }
                state.error?.let { append(" · ⚠ $it") }
            },
            onClick = { vm.refreshPlaylist() },
        ),
        Entry(
            "epg", if (epgState.loading) "Guide TV : chargement…" else "Actualiser le guide TV",
            subtitle = when {
                epgState.url == null -> "Aucun guide trouvé : ajoute une adresse XMLTV dans la playlist"
                epgState.error != null -> "⚠ ${epgState.error}"
                else -> "Mis à jour ${ago(vm.lastEpgUpdate(), now)} · ${epg.channelCount} chaînes avec programme"
            },
            onClick = { vm.refreshEpg() },
        ),
    )

    val playbackEntries = listOf(
        Entry("aspect", "Format de l'image", value = s.aspect.label, onClick = { vm.cycleAspect() }),
        Entry(
            "ua", "Identifiant du lecteur (User-Agent)",
            "À changer si les chaînes ou la playlist refusent de charger", value = s.userAgentName,
            onClick = { vm.cycleUserAgent() },
        ),
        Entry(
            "resume", "Au démarrage, reprendre la dernière chaîne",
            value = if (s.resumeLastChannel) "Oui" else "Non", onClick = { vm.toggleResumeLast() },
        ),
        Entry("history", "Effacer les chaînes récentes", onClick = { vm.clearHistory() }),
    )

    val sections = listOf(
        "Playlists" to playlistEntries,
        "Contenu" to contentEntries,
        "Lecture" to playbackEntries,
        "Mise à jour" to listOf(updateEntry),
        "À propos" to listOf(
            Entry("about", "IPTV pour Android TV", "github.com/nrobeveille/tv-iptv", value = version, onClick = {}),
        ),
    ).filter { it.second.isNotEmpty() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 900.dp)
            .focusRestorer(),
        contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Paramètres", fontSize = 28.sp, fontWeight = FontWeight.SemiBold) }
        sections.forEach { (title, entries) -> section(title, entries, registry) }
    }

    menuFor?.let { p ->
        ActionMenu(
            title = p.name,
            subtitle = hostOf(p.url),
            actions = listOf(
                MenuAction("Utiliser cette playlist") { vm.setActivePlaylist(p.id) },
                MenuAction("Modifier") { vm.navigate(Screen.PlaylistEditor(p.id)) },
                MenuAction("Supprimer") { confirmDelete = p },
            ),
            onDismiss = { menuFor = null },
        )
    }
    confirmDelete?.let { p ->
        ActionMenu(
            title = "Supprimer « ${p.name} » ?",
            subtitle = "La playlist et son guide TV seront retirés de la TV.",
            actions = listOf(
                MenuAction("Annuler") {},
                MenuAction("Supprimer") { vm.deletePlaylist(p.id) },
            ),
            onDismiss = { confirmDelete = null },
        )
    }

    // En ouvrant les paramètres, on regarde s'il y a une nouvelle version.
    LaunchedEffect(Unit) {
        if (vm.updateState.value == UpdateState.Idle) vm.checkForUpdate()
    }
}

private fun LazyListScope.section(title: String, entries: List<Entry>, registry: FocusRegistry) {
    item(key = "header_$title") { SectionHeader(title) }
    entries.forEach { entry ->
        item(key = entry.key) {
            SettingsRow(
                title = entry.title,
                subtitle = entry.subtitle,
                value = entry.value,
                chevron = entry.chevron,
                highlighted = entry.highlighted,
                onClick = entry.onClick,
                onLongClick = entry.onLongClick,
                modifier = Modifier.tracked(registry, "set:${entry.key}"),
            )
        }
    }
}
