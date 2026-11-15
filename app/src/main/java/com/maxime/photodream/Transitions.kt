package com.maxime.photodream

import kotlin.random.Random

/** Effets de passage d'une photo à l'autre dans le diaporama simple. */
enum class Transition(val key: String, val label: String, val durationMs: Long) {
    FADE("fade", "fondu enchaîné", 600),
    BLACK("black", "fondu au noir", 1200),
    SLIDE("slide", "glissement", 800),
    ZOOM("zoom", "zoom", 900),
    WIPE("wipe", "rideau", 900),
    RANDOM("random", "au hasard", 0);

    /**
     * Comment dessiner l'ancienne et la nouvelle photo quand la transition en est à [t] (0 à 1).
     * [forward] : on avance (faux quand on revient en arrière, pour glisser dans l'autre sens).
     */
    fun frame(t: Float, forward: Boolean): Pair<Layer, Layer> {
        val e = Timeline.ease(t.coerceIn(0f, 1f))
        val dir = if (forward) 1f else -1f
        return when (this) {
            FADE, RANDOM -> Layer(alpha = 1f - e) to Layer(alpha = e)
            BLACK -> Layer(alpha = (1f - 2f * e).coerceAtLeast(0f)) to Layer(alpha = (2f * e - 1f).coerceAtLeast(0f))
            SLIDE -> Layer(dx = 0f - dir * e) to Layer(dx = dir * (1f - e) + 0f) // + 0f : pas de « -0 »
            ZOOM -> Layer(alpha = 1f - e, scale = 1f + 0.15f * e) to Layer(alpha = e, scale = 0.85f + 0.15f * e)
            // la nouvelle photo se dévoile de gauche à droite (de droite à gauche en arrière)
            WIPE -> Layer() to if (forward) Layer(clipFrom = 0f, clipTo = e) else Layer(clipFrom = 1f - e, clipTo = 1f)
        }
    }

    /** Le hasard choisit un vrai effet, différent du précédent si possible. */
    fun resolve(previous: Transition?, random: Random = Random.Default): Transition {
        if (this != RANDOM) return this
        val choices = entries.filter { it != RANDOM && it != previous }
        return choices[random.nextInt(choices.size)]
    }

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: FADE
    }
}

/**
 * Une photo pendant la transition : opacité, décalage horizontal (en largeurs d'écran),
 * agrandissement, et bande visible de l'écran (de [clipFrom] à [clipTo], en largeurs d'écran).
 */
data class Layer(
    val alpha: Float = 1f,
    val dx: Float = 0f,
    val scale: Float = 1f,
    val clipFrom: Float = 0f,
    val clipTo: Float = 1f,
)
