package com.maxime.photodream

/**
 * Le diaporama simple, sans aucun dessin : les photos dans l'ordre des dossiers, une
 * toutes les [intervalMs], avec retour en arrière et pause. Le temps ne compte qu'à partir du
 * moment où la photo est vraiment affichée, pour qu'un chargement lent ne la fasse pas sauter.
 * Séparé de la vue pour pouvoir être testé.
 */
class PhotoPlayer(val photos: List<RemotePhoto>, start: Int, private val intervalMs: Long) {

    init {
        require(photos.isNotEmpty())
    }

    /** Position de la photo à afficher. */
    var index = start.coerceIn(0, photos.size - 1)
        private set

    var paused = false
        private set

    /** Instant où la photo [index] est apparue, ou -1 tant qu'elle se charge. */
    private var shownAt = -1L

    val current get() = photos[index]

    /** Photo suivante, en revenant au début après la dernière. */
    fun peekNext() = photos[wrap(index + 1)]

    fun peekPrevious() = photos[wrap(index - 1)]

    /** La photo [index] vient d'apparaître : le compte à rebours démarre. */
    fun shown(now: Long) {
        if (shownAt < 0) shownAt = now
    }

    fun next() = go(index + 1)

    fun previous() = go(index - 1)

    /** Met en pause ou relance ; à la reprise, la photo reste encore un intervalle entier. */
    fun togglePause(now: Long) {
        paused = !paused
        if (!paused && shownAt >= 0) shownAt = now
    }

    /** Avance d'une photo quand son temps est écoulé ; renvoie vrai si la photo a changé. */
    fun update(now: Long): Boolean {
        if (paused || shownAt < 0 || now - shownAt < intervalMs) return false
        next()
        return true
    }

    private fun go(i: Int) {
        index = wrap(i)
        shownAt = -1L
    }

    private fun wrap(i: Int) = ((i % photos.size) + photos.size) % photos.size

    companion object {
        /**
         * Ordre du diaporama simple : dossier par dossier (les photos d'un dossier avant celles
         * de ses sous-dossiers), et par nom dans chaque dossier, sans tenir compte des majuscules.
         */
        fun ordered(all: List<RemotePhoto>): List<RemotePhoto> = all.sortedWith(
            compareBy<RemotePhoto, String>(String.CASE_INSENSITIVE_ORDER) { it.path.substringBeforeLast('\\', "") }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.path.substringAfterLast('\\') },
        )
    }
}
