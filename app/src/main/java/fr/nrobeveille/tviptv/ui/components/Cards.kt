package fr.nrobeveille.tviptv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.data.Channel
import fr.nrobeveille.tviptv.data.Programme
import fr.nrobeveille.tviptv.ui.theme.Accent
import fr.nrobeveille.tviptv.ui.theme.AccentSoft
import fr.nrobeveille.tviptv.ui.theme.FocusedText

/** Une entrée de la colonne des catégories. */
data class CategoryEntry(val key: String, val label: String, val count: Int)

/**
 * Colonne des catégories : la catégorie change dès qu'on passe dessus (comme les applis IPTV
 * classiques), la liste à droite suit.
 */
@Composable
fun CategoryList(
    entries: List<CategoryEntry>,
    selected: String?,
    onSelect: (String) -> Unit,
    registry: FocusRegistry,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = entries.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    )
    LazyColumn(
        state = state,
        modifier = modifier.focusRestorer(),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            val isSelected = entry.key == selected
            Surface(
                onClick = { onSelect(entry.key) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .tracked(registry, "cat:${entry.key}") { onSelect(entry.key) },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) AccentSoft else Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = FocusedText,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.label,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = entry.count.toString(),
                        fontSize = 13.sp,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/** Ligne de chaîne : numéro, logo, nom, programme en cours. */
@Composable
fun ChannelRow(
    channel: Channel,
    now: Programme?,
    time: Long,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 58.dp else 68.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = if (playing) 0.12f else 0.04f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = FocusedText,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (playing) Border(BorderStroke(2.dp, Accent), shape = shape) else Border.None,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (channel.number > 0) {
                Text(
                    text = channel.number.toString(),
                    fontSize = 14.sp,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.width(40.dp),
                )
            }
            Artwork(
                url = channel.logo,
                name = channel.name,
                modifier = Modifier
                    .width(if (compact) 64.dp else 76.dp)
                    .height(if (compact) 40.dp else 46.dp),
                fontSize = 13.sp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now != null) {
                    Text(
                        text = now.title,
                        fontSize = 13.sp,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    ProgressLine(now.progressAt(time), Modifier.fillMaxWidth(0.6f))
                }
            }
            if (favorite) Text("★", fontSize = 18.sp, color = Accent)
        }
    }
}

/** Affiche de film ou de série. */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    favorite: Boolean = false,
    caption: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(10.dp)),
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.05f),
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
            ),
        ) {
            Artwork(
                url = image,
                name = title,
                contentScale = ContentScale.Crop,
                fontSize = 24.sp,
                modifier = Modifier.fillMaxSize(),
            )
            if (favorite) {
                Text(
                    "★",
                    fontSize = 20.sp,
                    color = Accent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
            if (progress > 0f) {
                ProgressLine(
                    progress,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(8.dp),
                    height = 4.dp,
                )
            }
        }
        Text(
            text = title,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (caption != null) {
            Text(caption, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}
