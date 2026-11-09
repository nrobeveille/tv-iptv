package fr.nrobeveille.tviptv.data

import android.content.Context

/** Mémorise où l'on s'est arrêté dans un film ou un épisode. */
class PlaybackPositions(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun get(key: String): Long = prefs.getLong(key.hashCode().toString(), 0L)

    fun save(key: String, positionMs: Long, durationMs: Long) {
        val id = key.hashCode().toString()
        // Terminé (ou presque) ou à peine commencé : on repartira du début.
        val finished = durationMs > 0 && positionMs > durationMs - 90_000
        if (positionMs < 30_000 || finished) {
            prefs.edit().remove(id).apply()
        } else {
            prefs.edit().putLong(id, positionMs).apply()
        }
        if (durationMs > 0) {
            prefs.edit().putInt("p_$id", ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)).apply()
        }
    }

    /** Avancement en pourcentage (0 si jamais vu). */
    fun progress(key: String): Int = prefs.getInt("p_${key.hashCode()}", 0)
}
