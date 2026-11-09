package fr.nrobeveille.tviptv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Background = Color(0xFF0A0E1A)
val Panel = Color(0xFF141A2E)
val PanelLight = Color(0xFF1E2640)
val Accent = Color(0xFFFF5C8A)
val AccentSoft = Color(0x33FF5C8A)
val SubtleText = Color(0xB3FFFFFF)
val FaintText = Color(0x80FFFFFF)
val FocusedText = Color(0xFF0A0E1A)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            surface = Panel,
            onSurface = Color.White,
            background = Background,
            onBackground = Color.White,
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}
