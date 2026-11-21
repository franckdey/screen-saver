package com.maxime.photodream

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Zoom dans une photo affichée en plein écran, sans aucun dessin : niveau de zoom, point de la photo
 * au centre de l'écran, déplacements aux flèches. Les valeurs montrées ([shownScale], [shownX],
 * [shownY]) rejoignent en douceur les valeurs voulues, image après image.
 * Les positions sont en fractions de la photo (0 à 1), pour qu'une version plus nette de la même
 * photo puisse la remplacer sans que rien ne bouge. Séparé de la vue pour pouvoir être testé.
 */
class PhotoZoom(
    /** Zoom de départ montré, pour partir de la taille à laquelle la photo était dessinée. */
    startScale: Float = 1f,
) {
    /** Niveau voulu, dans [LEVELS]. */
    var level = 0
        private set
    val scale get() = LEVELS[level]

    /** Point de la photo voulu au centre de l'écran. */
    var x = 0.5f
        private set
    var y = 0.5f
        private set

    var shownScale = startScale
        private set
    var shownX = 0.5f
        private set
    var shownY = 0.5f
        private set

    private var viewW = 1f
    private var viewH = 1f
    private var imgW = 1f
    private var imgH = 1f

    /** Taille de l'écran et de la photo, en pixels. */
    fun setSizes(viewW: Float, viewH: Float, imgW: Float, imgH: Float) {
        if (viewW <= 0f || viewH <= 0f || imgW <= 0f || imgH <= 0f) return
        this.viewW = viewW
        this.viewH = viewH
        this.imgW = imgW
        this.imgH = imgH
        clampTarget()
    }

    /** Zoome un cran de plus ; faux si c'est déjà le maximum. */
    fun zoomIn(): Boolean {
        if (level >= LEVELS.size - 1) return false
        level++
        clampTarget()
        return true
    }

    /** Dézoome d'un cran ; faux au premier cran (il faut alors quitter le zoom). */
    fun zoomOut(): Boolean {
        if (level == 0) return false
        level--
        clampTarget()
        return true
    }

    /**
     * Se déplace dans la photo d'un tiers de ce qu'on voit, dans le sens de la flèche
     * ([dx], [dy] valent -1, 0 ou 1). Faux si on est déjà au bord.
     */
    fun pan(dx: Int, dy: Int): Boolean {
        val (hx, hy) = halfView(scale)
        val oldX = x
        val oldY = y
        x += dx * hx * 2f * PAN_STEP
        y += dy * hy * 2f * PAN_STEP
        clampTarget()
        return x != oldX || y != oldY
    }

    /** Fait avancer l'animation de [dtMs] millisecondes. */
    fun step(dtMs: Long) {
        val k = 1f - exp(-dtMs / SMOOTH_MS).toFloat()
        shownScale += (scale - shownScale) * k
        shownX += (x - shownX) * k
        shownY += (y - shownY) * k
        if (kotlin.math.abs(shownScale - scale) < 0.001f) shownScale = scale
        // pendant un dézoom, la partie visible reste dans la photo
        val (hx, hy) = halfView(shownScale)
        shownX = clamp(shownX, hx)
        shownY = clamp(shownY, hy)
    }

    /** Partie de la photo visible à l'écran (gauche, haut, droite, bas, en fractions de la photo). */
    fun visible(): FloatArray {
        val (hx, hy) = halfView(shownScale)
        return floatArrayOf(
            max(0f, shownX - hx), max(0f, shownY - hy),
            min(1f, shownX + hx), min(1f, shownY + hy),
        )
    }

    /** Demi-largeur et demi-hauteur de l'écran, en fractions de la photo agrandie [s] fois. */
    private fun halfView(s: Float): Pair<Float, Float> {
        val fit = min(viewW / imgW, viewH / imgH) * s
        return viewW / (2f * imgW * fit) to viewH / (2f * imgH * fit)
    }

    private fun clampTarget() {
        val (hx, hy) = halfView(scale)
        x = clamp(x, hx)
        y = clamp(y, hy)
    }

    /** Plus petite que l'écran dans ce sens : la photo reste centrée ; sinon, pas de vide sur les bords. */
    private fun clamp(v: Float, half: Float) = if (half >= 0.5f) 0.5f else v.coerceIn(half, 1f - half)

    companion object {
        /** Crans de zoom, par rapport à la photo entière à l'écran. */
        val LEVELS = floatArrayOf(2f, 3f, 4f)
        /** Chaque flèche déplace d'un tiers de ce qu'on voit. */
        const val PAN_STEP = 1f / 3f
        private const val SMOOTH_MS = 110.0
    }
}
