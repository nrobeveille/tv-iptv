package fr.nrobeveille.tviptv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import fr.nrobeveille.tviptv.ui.theme.Accent
import fr.nrobeveille.tviptv.ui.theme.FocusedText
import fr.nrobeveille.tviptv.ui.theme.PanelLight
import fr.nrobeveille.tviptv.ui.theme.SubtleText

private val RowShape = RoundedCornerShape(12.dp)

/** Ligne focusable générique : titre, sous-titre, valeur à droite. */
@Composable
fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = if (highlighted) 0.14f else 0.05f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = FocusedText,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (highlighted) Border(BorderStroke(2.dp, Accent), shape = RowShape) else Border.None,
            focusedBorder = if (highlighted) Border(BorderStroke(3.dp, Accent), shape = RowShape) else Border.None,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke(this)
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    fontSize = 16.sp,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
            if (chevron) Text(text = "›", fontSize = 24.sp)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Accent,
        letterSpacing = 1.5.sp,
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 4.dp),
    )
}

/** Logo de chaîne ou affiche, avec les initiales si l'image manque. */
@Composable
fun Artwork(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fontSize: TextUnit = 16.sp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PanelLight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = SubtleText,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (contentScale == ContentScale.Fit) 4.dp else 0.dp),
            )
        }
    }
}

private val PREFIX = Regex("""^[A-Za-z]{2,3}\s*[:|\-]\s*""")

fun initials(name: String): String {
    val clean = name.replace(PREFIX, "").trim()
    val words = clean.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(3).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** Fine barre de progression (programme en cours, film commencé). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = Accent, height: Dp = 3.dp) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(Color.White.copy(alpha = 0.2f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(color),
        )
    }
}

/** Message centré (chargement, erreur, liste vide) avec boutons facultatifs. */
@Composable
fun MessageView(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    firstActionModifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.width(560.dp),
        ) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (message != null) {
                Text(message, fontSize = 16.sp, color = SubtleText, textAlign = TextAlign.Center)
            }
            if (actions.isNotEmpty()) Spacer(Modifier.height(12.dp))
            actions.forEachIndexed { index, (label, onClick) ->
                Button(
                    label = label,
                    onClick = onClick,
                    modifier = (if (index == 0) firstActionModifier else Modifier).width(420.dp),
                )
            }
        }
    }
}

@Composable
fun Button(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(26.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Accent else Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = FocusedText,
        ),
    ) {
        Text(
            text = label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
        )
    }
}

/** Petite pastille de couleur (mise à jour disponible, favori…). */
@Composable
fun Dot(color: Color = Accent, size: Dp = 10.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size))
            .background(color)
    )
}
