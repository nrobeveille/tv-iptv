package fr.nrobeveille.tviptv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import fr.nrobeveille.tviptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Une version publiée dans les releases GitHub. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** URL API de l'APK (fonctionne pour un dépôt public ou privé avec jeton). */
    val apkUrl: String,
    val apkSize: Long,
)

class UpdateException(message: String) : Exception(message)

/**
 * Cherche une version plus récente dans les releases GitHub du dépôt, la télécharge
 * et l'installe avec le PackageInstaller d'Android.
 */
class Updater(context: Context) {

    private val appContext = context.applicationContext

    val currentVersionCode: Int = BuildConfig.VERSION_CODE
    val currentVersionName: String = BuildConfig.VERSION_NAME

    /** Renvoie la dernière release si elle est plus récente que l'appli installée, sinon null. */
    suspend fun findUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val json = httpGetText("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val release = JSONObject(json)
        val tag = release.optString("tag_name")
        val versionCode = VERSION_IN_TAG.find(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw UpdateException("Version illisible dans la release « $tag »")

        val assets = release.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    apk = asset
                    break
                }
            }
        }
        if (apk == null) throw UpdateException("Aucun APK dans la dernière release")

        val info = UpdateInfo(
            versionCode = versionCode,
            versionName = tag.removePrefix("v"),
            apkUrl = apk.getString("url"),
            apkSize = apk.optLong("size", -1L),
        )
        if (info.versionCode > currentVersionCode) info else null
    }

    /** Télécharge l'APK dans le cache de l'appli. [onProgress] reçoit une valeur entre 0 et 1. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "tv-iptv-${info.versionCode}.apk")

        val connection = openFollowingRedirects(info.apkUrl, accept = "application/octet-stream")
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (info.apkSize > 0 && target.length() != info.apkSize) {
            target.delete()
            throw UpdateException("Téléchargement incomplet, réessaie")
        }
        checkApk(target, info)
        target
    }

    /** true si l'utilisateur a autorisé cette appli à installer des applis. */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /** Ouvre l'écran système qui autorise l'installation d'applis. Renvoie false s'il n'existe pas. */
    fun openInstallPermissionScreen(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Lance l'installation ; le résultat arrive dans [InstallResultReceiver]. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Une appli qui se met à jour elle-même peut le faire sans confirmation.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = Intent(appContext, InstallResultReceiver::class.java)
                .setPackage(appContext.packageName)
            val pending = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    /** Vérifie que le fichier téléchargé est bien une version plus récente de cette appli. */
    private fun checkApk(apk: File, info: UpdateInfo) {
        val packageInfo = appContext.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: run {
                apk.delete()
                throw UpdateException("Le fichier téléchargé n'est pas un APK valide")
            }
        if (packageInfo.packageName != appContext.packageName) {
            apk.delete()
            throw UpdateException("L'APK téléchargé n'est pas cette appli")
        }
        if (PackageInfoCompat.getLongVersionCode(packageInfo) < info.versionCode) {
            apk.delete()
            throw UpdateException("La version de l'APK ne correspond pas à la release")
        }
    }

    private fun httpGetText(url: String): String {
        val connection = openFollowingRedirects(url, accept = "application/vnd.github+json")
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Ouvre une connexion en suivant les redirections à la main : le jeton GitHub
     * ne doit être envoyé qu'à api.github.com, pas au stockage vers lequel GitHub redirige.
     */
    private fun openFollowingRedirects(startUrl: String, accept: String): HttpURLConnection {
        var url = URL(startUrl)
        repeat(MAX_REDIRECTS) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "tv-iptv/${BuildConfig.VERSION_NAME}")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (BuildConfig.UPDATE_TOKEN.isNotEmpty() && url.host == "api.github.com") {
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
                }
            }
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw UpdateException("Pas de connexion à GitHub")
            }
            when (code) {
                in 200..299 -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrEmpty()) throw UpdateException("Redirection GitHub invalide")
                    url = URL(url, location)
                }
                404 -> {
                    connection.disconnect()
                    throw UpdateException("Aucune release trouvée (dépôt privé ou pas encore publié ?)")
                }
                403, 429 -> {
                    connection.disconnect()
                    throw UpdateException("GitHub limite les requêtes, réessaie plus tard")
                }
                else -> {
                    connection.disconnect()
                    throw UpdateException("Erreur GitHub ($code)")
                }
            }
        }
        throw UpdateException("Trop de redirections")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        /** Le numéro de build est le dernier nombre du tag : v1.0.42 → 42. */
        val VERSION_IN_TAG = Regex("(\\d+)$")
    }
}
