package com.maxime.photodream

import kotlin.random.Random

/**
 * Ordre aléatoire du diaporama simple : chaque photo passe une fois avant qu'une autre ne revienne.
 * L'ordre est gardé d'un lancement à l'autre ; un nouveau tirage n'a lieu qu'une fois tout vu.
 */
object RandomOrder {

    /**
     * Reprend l'ordre [saved] (des chemins) pour les photos toujours présentes, et ajoute à la fin,
     * mélangées, celles qui n'y étaient pas encore (nouvelles photos, ou premier lancement).
     */
    fun reconcile(saved: List<String>, photos: List<RemotePhoto>, random: Random = Random.Default): List<RemotePhoto> {
        val byPath = photos.associateBy { it.path }
        val kept = saved.mapNotNull { byPath[it] }.distinct()
        val known = kept.toHashSet()
        return kept + photos.filter { it !in known }.shuffled(random)
    }

    /** Nouveau tirage pour le tour suivant, sans recommencer par la photo qui vient de passer. */
    fun reshuffle(previous: List<RemotePhoto>, random: Random = Random.Default): List<RemotePhoto> {
        val next = previous.shuffled(random).toMutableList()
        if (next.size > 1 && next[0] == previous.last()) {
            val j = 1 + random.nextInt(next.size - 1)
            next[0] = next[j].also { next[j] = next[0] }
        }
        return next
    }
}
