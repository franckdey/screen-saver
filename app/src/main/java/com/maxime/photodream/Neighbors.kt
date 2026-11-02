package com.maxime.photodream

import kotlin.math.abs

/** Choix de la vignette voisine quand on déplace le cadre de sélection avec les flèches. */
object Neighbors {

    /**
     * Parmi les centres [candidates], l'indice du plus proche de [fromX], [fromY] dans la direction
     * ([dx], [dy]) (par exemple 1, 0 pour la droite), ou null s'il n'y en a aucun de ce côté.
     * Un décalage sur le côté compte plus qu'une distance dans l'axe, pour rester bien aligné.
     */
    fun pick(fromX: Float, fromY: Float, candidates: List<Pair<Float, Float>>, dx: Int, dy: Int): Int? {
        var best: Int? = null
        var bestScore = Float.MAX_VALUE
        candidates.forEachIndexed { i, (x, y) ->
            val along = (x - fromX) * dx + (y - fromY) * dy
            val side = abs((x - fromX) * dy - (y - fromY) * dx)
            // devant, et pas trop de biais (moins de 60° environ)
            if (along <= 1f || side > along * 1.8f) return@forEachIndexed
            val score = along + side * 3f
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }
}
