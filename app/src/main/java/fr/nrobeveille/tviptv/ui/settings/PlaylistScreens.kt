package fr.nrobeveille.tviptv.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import fr.nrobeveille.tviptv.IptvViewModel
import fr.nrobeveille.tviptv.data.EpgRepository
import fr.nrobeveille.tviptv.remote.RemoteInputServer
import fr.nrobeveille.tviptv.ui.components.ActionMenu
import fr.nrobeveille.tviptv.ui.components.Button
import fr.nrobeveille.tviptv.ui.components.FocusRegistry
import fr.nrobeveille.tviptv.ui.components.MenuAction
import fr.nrobeveille.tviptv.ui.components.SettingsRow
import fr.nrobeveille.tviptv.ui.components.TextEditDialog
import fr.nrobeveille.tviptv.ui.components.awaitLayout
import fr.nrobeveille.tviptv.ui.components.tracked
import fr.nrobeveille.tviptv.ui.components.trapFocus
import fr.nrobeveille.tviptv.ui.theme.Accent
import fr.nrobeveille.tviptv.ui.theme.SubtleText

private enum class Field { NAME, URL, EPG }

/** Ajout / modification d'une playlist à la télécommande. */
@Composable
fun PlaylistEditorScreen(vm: IptvViewModel, playlistId: String?) {
    val existing = remember { vm.settings.value?.playlists?.firstOrNull { it.id == playlistId } }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var epg by remember { mutableStateOf(existing?.epgUrl ?: "") }
    var editing by remember { mutableStateOf<Field?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val registry = remember { FocusRegistry() }
    val urlValid = url.startsWith("http://", true) || url.startsWith("https://", true)

    Column(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .padding(horizontal = 120.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (existing == null) "Nouvelle playlist" else "Modifier la playlist",
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Astuce : « Ajouter depuis le téléphone » évite de taper l'adresse à la télécommande.",
            fontSize = 14.sp,
            color = SubtleText,
        )
        Spacer(Modifier.height(10.dp))
        SettingsRow(
            title = "Nom",
            value = name.ifBlank { "Ma playlist" },
            onClick = { editing = Field.NAME },
            modifier = Modifier.tracked(registry, "name"),
        )
        SettingsRow(
            title = "Adresse de la playlist M3U",
            subtitle = url.ifBlank { "http://serveur:port/get.php?username=…&password=…&type=m3u_plus" },
            highlighted = !urlValid,
            onClick = { editing = Field.URL },
            modifier = Modifier.tracked(registry, "url"),
        )
        val autoEpg = EpgRepository.xtreamEpgUrl(url)
        SettingsRow(
            title = "Guide TV (XMLTV) — facultatif",
            subtitle = epg.ifBlank {
                if (autoEpg != null) "Automatique (compte Xtream détecté)" else "Automatique (celui annoncé par la playlist)"
            },
            onClick = { editing = Field.EPG },
            modifier = Modifier.tracked(registry, "epg"),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                label = "Enregistrer",
                primary = true,
                onClick = {
                    if (!urlValid) {
                        vm.toast("L'adresse doit commencer par http:// ou https://")
                    } else {
                        vm.savePlaylist(playlistId, name, url, epg)
                        vm.back()
                    }
                },
                modifier = Modifier
                    .width(220.dp)
                    .tracked(registry, "save"),
            )
            Button(label = "Annuler", onClick = { vm.back() }, modifier = Modifier.width(180.dp))
            if (existing != null) {
                Button(label = "Supprimer", onClick = { confirmDelete = true }, modifier = Modifier.width(180.dp))
            }
        }
    }

    when (editing) {
        Field.NAME -> TextEditDialog("Nom de la playlist", name, { name = it }, { editing = null })
        Field.URL -> TextEditDialog(
            "Adresse de la playlist M3U", url, { url = it }, { editing = null },
            hint = "Fournie par ton abonnement IPTV (lien M3U / m3u_plus).",
            keyboardType = KeyboardType.Uri,
        )
        Field.EPG -> TextEditDialog(
            "Adresse du guide TV (XMLTV)", epg, { epg = it }, { editing = null },
            hint = "Laisse vide pour le guide automatique.",
            keyboardType = KeyboardType.Uri,
        )
        null -> Unit
    }

    if (confirmDelete && existing != null) {
        ActionMenu(
            title = "Supprimer « ${existing.name} » ?",
            actions = listOf(
                MenuAction("Annuler") {},
                MenuAction("Supprimer") {
                    vm.deletePlaylist(existing.id)
                    vm.back()
                },
            ),
            onDismiss = { confirmDelete = false },
        )
    }

    LaunchedEffect(Unit) {
        awaitLayout()
        registry.request(if (existing == null) "url" else "name")
    }
}

/** Ajout depuis le téléphone : QR code vers le mini serveur web de la TV. */
@Composable
fun RemoteAddScreen(vm: IptvViewModel) {
    var address by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val registry = remember { FocusRegistry() }

    DisposableEffect(Unit) {
        val server = RemoteInputServer { name, url, epg -> vm.addPlaylistFromPhone(name, url, epg) }
        address = server.start()
        failed = address == null
        onDispose { server.stop() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .padding(60.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Ajouter depuis le téléphone", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            if (failed) {
                Text(
                    "Impossible de démarrer : la TV doit être connectée au Wi-Fi ou en Ethernet.",
                    fontSize = 17.sp,
                    color = SubtleText,
                )
            } else {
                Step(1, "Connecte ton téléphone au même Wi-Fi que la TV.")
                Step(2, "Scanne le QR code, ou ouvre cette adresse :")
                Text(address ?: "…", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent)
                Step(3, "Colle l'adresse de ta playlist M3U et appuie sur « Envoyer à la TV ».")
                Text("La playlist se charge toute seule dès réception.", fontSize = 15.sp, color = SubtleText)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                label = "Retour",
                onClick = { vm.back() },
                modifier = Modifier
                    .width(220.dp)
                    .tracked(registry, "back"),
            )
        }
        address?.let { QrCode(it, Modifier.size(320.dp)) }
    }

    LaunchedEffect(Unit) {
        awaitLayout()
        registry.request("back")
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .background(Accent, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(text, fontSize = 18.sp)
    }
}

@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix = remember(content) {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
    Canvas(
        modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
