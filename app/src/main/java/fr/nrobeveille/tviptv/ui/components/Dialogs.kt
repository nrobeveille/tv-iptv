package fr.nrobeveille.tviptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import fr.nrobeveille.tviptv.ui.theme.Accent
import fr.nrobeveille.tviptv.ui.theme.FocusedText
import fr.nrobeveille.tviptv.ui.theme.Panel
import fr.nrobeveille.tviptv.ui.theme.SubtleText

class MenuAction(val label: String, val onClick: () -> Unit)

/** Petit menu (appui long, confirmation…). */
@Composable
fun ActionMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        val firstItem = remember { FocusRequester() }
        Column(
            modifier = Modifier
                .ignoreHeldSelectKey()
                .width(440.dp)
                .background(Panel, RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(text = subtitle, fontSize = 14.sp, color = SubtleText)
            Spacer(Modifier.height(8.dp))
            actions.forEachIndexed { index, action ->
                Surface(
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    modifier = (if (index == 0) Modifier.focusRequester(firstItem) else Modifier)
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.06f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = FocusedText,
                    ),
                ) {
                    Text(
                        text = action.label,
                        fontSize = 17.sp,
                        modifier = Modifier
                            .padding(horizontal = 18.dp)
                            .align(Alignment.CenterStart),
                    )
                }
            }
        }
        LaunchedEffect(Unit) {
            awaitLayout()
            runCatching { firstItem.requestFocus() }
        }
    }
}

/**
 * Saisie de texte : le clavier de la TV s'ouvre directement.
 * Valider (touche « OK » du clavier) enregistre, Retour annule.
 */
@Composable
fun TextEditDialog(
    title: String,
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Dialog(onDismissRequest = onDismiss) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
        Column(
            modifier = Modifier
                .width(720.dp)
                .background(Panel, RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (hint != null) Text(hint, fontSize = 14.sp, color = SubtleText)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onDone(value.text.trim())
                    onDismiss()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .border(2.dp, Accent, RoundedCornerShape(10.dp))
                    .padding(14.dp),
            )
            Text("Valide avec la touche « OK » / « Terminé » du clavier", fontSize = 13.sp, color = SubtleText)
        }
        LaunchedEffect(Unit) {
            awaitLayout()
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
}
