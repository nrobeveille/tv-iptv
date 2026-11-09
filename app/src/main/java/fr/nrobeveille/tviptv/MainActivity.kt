package fr.nrobeveille.tviptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import fr.nrobeveille.tviptv.ui.home.HomeScreen
import fr.nrobeveille.tviptv.ui.player.PlayerScreen
import fr.nrobeveille.tviptv.ui.settings.PlaylistEditorScreen
import fr.nrobeveille.tviptv.ui.settings.RemoteAddScreen
import fr.nrobeveille.tviptv.ui.theme.Background
import fr.nrobeveille.tviptv.ui.theme.IptvTheme
import fr.nrobeveille.tviptv.ui.vod.SeriesDetailScreen

class MainActivity : ComponentActivity() {

    private val vm: IptvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IptvTheme {
                BackHandler {
                    if (!vm.back()) finish()
                }
                AppRoot(vm)
            }
        }
    }
}

/**
 * Tous les écrans de la pile restent composés (pour retrouver la position, le focus,
 * le défilement en revenant) ; seul le dernier est visible et reçoit le focus.
 */
@Composable
private fun AppRoot(vm: IptvViewModel) {
    val stack = vm.backStack
    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        stack.forEachIndexed { index, screen ->
            val isTop = index == stack.lastIndex
            key(screen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (isTop) 1f else 0f }
                ) {
                    when (screen) {
                        Screen.Home -> HomeScreen(vm, isTop)
                        is Screen.SeriesDetail -> SeriesDetailScreen(vm, screen.seriesKey, isTop)
                        is Screen.Player -> PlayerScreen(vm, screen.items, screen.index)
                        is Screen.PlaylistEditor -> PlaylistEditorScreen(vm, screen.playlistId)
                        Screen.RemoteAdd -> RemoteAddScreen(vm)
                    }
                }
            }
        }
    }
}
