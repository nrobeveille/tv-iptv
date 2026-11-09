package fr.nrobeveille.tviptv.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Garde un FocusRequester par clé (chaîne, catégorie, réglage…) pour redonner le focus
 * au bon élément en revenant sur un écran, et mémorise le dernier élément focalisé.
 */
class FocusRegistry {
    private val requesters = HashMap<String, FocusRequester>()
    var lastFocused: String? = null

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** Donne le focus à l'élément ; renvoie false s'il n'est pas (encore) affiché. */
    fun request(key: String?): Boolean {
        val requester = key?.let { requesters[it] } ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }

    fun restore(): Boolean = request(lastFocused)
}

/** Relie un élément au registre : focusRequester + mémorisation quand il prend le focus. */
fun Modifier.tracked(registry: FocusRegistry, key: String, onFocused: () -> Unit = {}): Modifier =
    this
        .focusRequester(registry.requester(key))
        .onFocusChanged {
            if (it.isFocused) {
                registry.lastFocused = key
                onFocused()
            }
        }

/** Empêche le focus de sortir de cet écran (vers les écrans cachés en dessous). */
fun Modifier.trapFocus(): Modifier =
    this
        .focusProperties { exit = { FocusRequester.Cancel } }
        .focusGroup()

/** Attend que l'écran ait été dessiné (les listes paresseuses composent leurs éléments). */
suspend fun awaitLayout() {
    withFrameNanos { }
    withFrameNanos { }
}

fun KeyEvent.isSelectKey(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Ignore la touche OK tant qu'elle n'a pas été relâchée puis réappuyée.
 * Évite qu'un menu ouvert par un appui long valide aussitôt son premier choix.
 */
@Composable
fun Modifier.ignoreHeldSelectKey(): Modifier {
    val freshPress = remember { BooleanArray(1) }
    return this.onPreviewKeyEvent { event ->
        if (!event.isSelectKey()) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) {
                    freshPress[0] = true
                    false
                } else {
                    !freshPress[0]
                }
            }
            KeyEventType.KeyUp -> {
                if (freshPress[0]) {
                    freshPress[0] = false
                    false
                } else {
                    true
                }
            }
            else -> false
        }
    }
}
