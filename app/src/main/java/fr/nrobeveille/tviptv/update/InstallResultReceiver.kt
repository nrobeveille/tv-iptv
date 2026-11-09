package fr.nrobeveille.tviptv.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Remonte au ViewModel les échecs d'installation (le succès redémarre l'appli). */
object UpdateEvents {
    private val _installError = MutableStateFlow<String?>(null)
    val installError: StateFlow<String?> = _installError.asStateFlow()

    fun report(message: String) {
        _installError.value = message
    }

    fun clear() {
        _installError.value = null
    }
}

/** Reçoit le résultat de la session PackageInstaller. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android demande une confirmation : on affiche son écran.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    fail(context, "Confirmation d'installation impossible")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: ActivityNotFoundException) {
                    fail(context, "Confirmation d'installation impossible")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Android remplace l'appli et la relance : rien à faire.
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> fail(context, "Installation annulée")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> fail(
                context,
                "Signature différente : désinstalle puis réinstalle l'appli une fois (voir README)",
            )
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> fail(context, "APK incompatible avec cet appareil")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                fail(context, "Échec de l'installation" + (message?.let { " : $it" } ?: ""))
            }
        }
    }

    private fun fail(context: Context, message: String) {
        UpdateEvents.report(message)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
