package com.maxime.photodream

import kotlin.math.min
import kotlin.math.pow

/**
 * Le déroulé d'une photo, sans aucun dessin : attente, apparition sur sa vignette, zoom,
 * affichage plein écran, retour et disparition. Gère aussi la photo fixée (avec reprise
 * automatique) et le passage à la suivante. Séparé de la vue pour pouvoir être testé.
 */
class Timeline(private val holdMs: Long) {

    enum class Phase { WAIT, FADE_IN, ZOOM_IN, HOLD, ZOOM_OUT, FADE_OUT }

    enum class Event {
        /** Une photo chargée doit sortir de sa vignette. */
        START_PHOTO,

        /** La photo est revenue à sa place : la vue peut l'oublier. */
        PHOTO_DONE,

        /** Rien n'a pu être chargé depuis un moment : on réessaie. */
        RETRY_LOAD,

        /** La photo fixée depuis une minute repart toute seule. */
        AUTO_RESUMED,
    }

    /** Ce qu'il faut dessiner à un instant donné. */
    data class Frame(val zoom: Float, val alpha: Float, val kenBurns: Float)

    var phase = Phase.WAIT
        private set
    var paused = false
        private set
    private var phaseStart = 0L
    private var pausedAt = 0L
    private var skipRequested = false

    /** Fixe la photo (ou la prochaine) en plein écran, ou relance le défilement. */
    fun togglePause(now: Long) {
        paused = !paused
        pausedAt = now
    }

    /** Passe tout de suite à la photo suivante. */
    fun skip() {
        paused = false
        skipRequested = true
    }

    /**
     * Fait avancer le déroulé jusqu'à [now].
     * [nextReady] : la photo suivante est chargée ; [loading] : un chargement est en cours.
     */
    fun update(now: Long, nextReady: Boolean, loading: Boolean): List<Event> {
        val events = ArrayList<Event>(1)
        if (paused && now - pausedAt >= PAUSE_MAX_MS) {
            paused = false
            events += Event.AUTO_RESUMED
        }
        val elapsed = now - phaseStart
        when (phase) {
            Phase.WAIT -> if (nextReady && elapsed >= PAUSE_MS) {
                go(Phase.FADE_IN, now)
                events += Event.START_PHOTO
            } else if (!nextReady && !loading && elapsed > RETRY_MS) {
                phaseStart = now
                events += Event.RETRY_LOAD
            }
            Phase.FADE_IN -> if (elapsed >= FADE_MS) go(Phase.ZOOM_IN, now)
            Phase.ZOOM_IN -> if (elapsed >= ZOOM_MS) go(Phase.HOLD, now)
            Phase.HOLD -> when {
                skipRequested -> {
                    skipRequested = false
                    go(Phase.ZOOM_OUT, now)
                }
                // photo fixée : le temps d'affichage reste bloqué à mi-parcours
                paused -> phaseStart = now - min(elapsed, holdMs / 2)
                elapsed >= holdMs -> go(Phase.ZOOM_OUT, now)
            }
            Phase.ZOOM_OUT -> if (elapsed >= ZOOM_MS) go(Phase.FADE_OUT, now)
            Phase.FADE_OUT -> if (elapsed >= FADE_MS) {
                go(Phase.WAIT, now)
                events += Event.PHOTO_DONE
            }
        }
        return events
    }

    fun frame(now: Long): Frame {
        val elapsed = (now - phaseStart).toFloat()
        fun t(duration: Long) = (elapsed / duration).coerceIn(0f, 1f)
        return when (phase) {
            Phase.WAIT -> Frame(0f, 0f, 0f)
            Phase.FADE_IN -> Frame(0f, t(FADE_MS), 0f)
            Phase.ZOOM_IN -> Frame(ease(t(ZOOM_MS)), 1f, 0f)
            Phase.HOLD -> Frame(1f, 1f, t(holdMs))
            Phase.ZOOM_OUT -> Frame(1f - ease(t(ZOOM_MS)), 1f, 1f)
            Phase.FADE_OUT -> Frame(0f, 1f - t(FADE_MS), 0f)
        }
    }

    private fun go(p: Phase, now: Long) {
        phase = p
        phaseStart = now
    }

    companion object {
        const val FADE_MS = 700L
        const val ZOOM_MS = 1800L
        const val PAUSE_MS = 900L
        const val RETRY_MS = 15_000L
        const val PAUSE_MAX_MS = 60_000L

        /** Accélère puis ralentit en douceur. */
        fun ease(t: Float): Float =
            if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
    }
}
