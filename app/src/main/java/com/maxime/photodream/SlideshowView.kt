package com.maxime.photodream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Le diaporama façon Windows Media Center : des vignettes floutées qui dérivent lentement
 * dans tous les sens, et une photo à la fois qui sort de la mosaïque, grandit jusqu'au plein écran,
 * reste affichée quelques secondes puis retourne à sa place.
 */
@SuppressLint("ViewConstructor")
class SlideshowView(context: Context) : View(context) {

    private val prefs = Prefs(context)
    private val repo = PhotoRepository(context, prefs)
    private val holdMs = prefs.holdSeconds * 1000L
    private val blurLevel = prefs.blurLevel
    private var scope: CoroutineScope? = null

    // --- Mosaïque -------------------------------------------------------------------------

    /**
     * Une vignette flottante : taille, position et direction propres. Elle dérive lentement
     * en tournant un peu au hasard ; les petites (« lointaines ») vont moins vite.
     */
    private class Tile(var x: Float, var y: Float, val scale: Float, val w: Float, val h: Float, var angle: Float, val speed: Float) {
        var turn = 0f
        /** Inclinaison en degrés et vitesse de rotation en degrés par seconde. */
        var rotation = 0f
        var spin = 0f
        var bitmap: Bitmap? = null
        var previous: Bitmap? = null
        var shownAt = 0L

        fun show(bmp: Bitmap, now: Long) {
            previous = bitmap
            bitmap = bmp
            shownAt = now
        }
    }

    private val tiles = ArrayList<Tile>()
    private val pool = ArrayList<Bitmap>()
    private val recentTiles = ArrayDeque<Tile>()
    private var baseH = 0f
    private var gap = 0f
    private var speed = 0f
    private val random = java.util.Random()
    private var lastFrame = 0L

    // --- Photo au premier plan ------------------------------------------------------------

    private val timeline = Timeline(holdMs)
    private var current: LoadedPhoto? = null
    private var currentTile: Tile? = null
    private var next: LoadedPhoto? = null
    private var loadingNext = false

    private var status: String? = "Connexion au dossier réseau…"
    private var notice: String? = null
    private var noticeUntil = 0L
    private var labelSize = 0f
    private var clockText = ""
    private var clockUpdated = 0L

    // --- Dessin ---------------------------------------------------------------------------

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint().apply { color = Color.rgb(26, 31, 38) }
    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    /** Cadre blanc cassé façon tirage photo. */
    private val framePaint = Paint().apply { color = Color.rgb(244, 241, 234) }
    private val scrimPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val tileRect = RectF()
    private val coverRect = RectF()
    private val fitRect = RectF()
    private val photoRect = RectF()
    private val clipRect = RectF()
    private val srcRect = RectF()
    private val shadowRect = RectF()
    private val frameRect = RectF()
    private val matrix = Matrix()

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        keepScreenOn = true
        val s = MainScope()
        scope = s
        s.launch { start() }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope?.cancel()
        scope = null
        // la déconnexion SMB fait du réseau : jamais sur le fil principal
        Thread { repo.close() }.start()
    }

    private suspend fun start() {
        val count = repo.load()
        if (count == 0) {
            status = repo.lastError ?: "Aucune photo trouvée"
            return
        }
        status = null
        if (repo.offline) {
            showNotice("NAS injoignable, photos déjà en cache (${repo.lastError})", 10_000)
        }
        scope?.launch { fillMosaic() }
        requestNext()
    }

    private suspend fun fillMosaic() {
        while (tiles.isEmpty()) delay(50)
        for (tile in tiles.shuffled()) {
            if (tile.bitmap != null) continue
            val bmp = repo.nextTile(blurLevel) ?: break
            if (tile.bitmap == null) tile.show(bmp, SystemClock.uptimeMillis())
            addToPool(bmp)
        }
    }

    private fun requestNext() {
        if (loadingNext || next != null) return
        val s = scope ?: return
        loadingNext = true
        s.launch {
            val photo = repo.nextPhoto(blurLevel)
            next = photo
            loadingNext = false
            if (photo == null && current == null) {
                showNotice(repo.lastError ?: "Impossible de charger les photos", 8_000)
            }
        }
    }

    /** Fixe la photo affichée (ou la prochaine) en plein écran, ou relance le défilement. */
    fun togglePause() {
        timeline.togglePause(SystemClock.uptimeMillis())
        showNotice(
            if (timeline.paused) "Photo fixée pendant 1 minute (OK pour reprendre avant)" else "Reprise du défilement",
            3_000,
        )
    }

    /** Passe tout de suite à la photo suivante. */
    fun skip() = timeline.skip()

    private fun showNotice(text: String, durationMs: Long) {
        notice = text
        noticeUntil = SystemClock.uptimeMillis() + durationMs
    }

    private fun addToPool(bmp: Bitmap) {
        pool += bmp
        while (pool.size > MAX_POOL) pool.removeAt(0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val short = min(w, h).toFloat()
        // le réglage « rangées » règle la taille moyenne des vignettes
        baseH = if (h > w) w * 1.7f / prefs.rows.coerceIn(2, 6) else h * 1.08f / prefs.rows.coerceIn(2, 6)
        gap = short * 0.014f
        speed = short / 1080f * 16f // pixels par seconde

        // grille « secouée » : couvre l'écran avec des espaces irréguliers
        val oldBitmaps = tiles.mapNotNull { it.bitmap }
        tiles.clear()
        val cellW = baseH * Images.TILE_ASPECT * 1.05f
        val cellH = baseH * 1.05f
        var y = -cellH / 2
        while (y < h + cellH / 2) {
            var x = -cellW / 2
            while (x < w + cellW / 2) {
                val scale = 0.55f + random.nextFloat() * 0.6f
                tiles += Tile(
                    x + (random.nextFloat() - 0.5f) * cellW * 0.6f,
                    y + (random.nextFloat() - 0.5f) * cellH * 0.6f,
                    scale, baseH * scale * Images.TILE_ASPECT, baseH * scale,
                    random.nextFloat() * 2 * Math.PI.toFloat(), speed * (0.5f + scale),
                ).apply {
                    rotation = (random.nextFloat() - 0.5f) * 36f
                    spin = (random.nextFloat() - 0.5f) * 7f
                }
                x += cellW
            }
            y += cellH
        }
        tiles.sortBy { it.scale } // les petites derrière
        oldBitmaps.forEachIndexed { i, bmp -> if (i < tiles.size) tiles[i].bitmap = bmp }
        recentTiles.clear()
        if (current != null) currentTile = tiles.last()
        textPaint.textSize = short * 0.035f
        labelSize = short * 0.026f
    }

    private fun move(dt: Long, now: Long) {
        val sec = dt / 1000f
        val onScreen = tiles.mapNotNullTo(HashSet()) { it.bitmap }
        for (t in tiles) {
            // la direction change doucement, sans à-coups
            t.turn = (t.turn + (random.nextFloat() - 0.5f) * 0.6f * sec).coerceIn(-0.25f, 0.25f)
            t.angle += t.turn * sec
            // rotation douce qui change de sens de temps en temps, et reste dans ±25°
            t.spin = (t.spin + (random.nextFloat() - 0.5f) * 4.6f * sec).coerceIn(-5.7f, 5.7f)
            if (kotlin.math.abs(t.rotation) > 25f) t.spin = -kotlin.math.sign(t.rotation) * kotlin.math.abs(t.spin)
            t.rotation += t.spin * sec * (if (t === currentTile) 0.15f else 1f)
            // la vignette choisie ralentit beaucoup pour rester sous la photo agrandie
            val v = if (t === currentTile) t.speed * 0.15f else t.speed
            t.x += kotlin.math.cos(t.angle) * v * sec
            t.y += kotlin.math.sin(t.angle) * v * sec
            var wrapped = false
            if (t.x > width + gap) { t.x = -t.w - gap; wrapped = true }
            else if (t.x + t.w < -gap) { t.x = width + gap; wrapped = true }
            if (t.y > height + gap) { t.y = -t.h - gap; wrapped = true }
            else if (t.y + t.h < -gap) { t.y = height + gap; wrapped = true }
            // sortie d'un côté, retour de l'autre : on lui donne une autre photo
            if (wrapped && t !== currentTile && t.bitmap != null) {
                val candidates = pool.filter { it !in onScreen }
                if (candidates.isNotEmpty()) {
                    val bmp = candidates[random.nextInt(candidates.size)]
                    t.show(bmp, now)
                    t.previous = null
                    onScreen += bmp
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0L else (now - lastFrame).coerceAtMost(100)
        lastFrame = now
        canvas.drawColor(Color.BLACK)

        if (tiles.isNotEmpty()) {
            move(dt, now)
            updatePhase(now)
            val zoom = drawPhotoParams(now)
            drawMosaic(canvas, now)
            dimPaint.alpha = (255 * (0.35f + 0.4f * zoom)).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            drawPhoto(canvas)
        }
        drawOverlays(canvas, now)
        postInvalidateOnAnimation()
    }

    private fun updatePhase(now: Long) {
        for (event in timeline.update(now, nextReady = next != null, loading = loadingNext)) {
            when (event) {
                Timeline.Event.START_PHOTO -> {
                    val ready = next ?: continue
                    val tile = pickTile()
                    tile.show(ready.tile, now)
                    addToPool(ready.tile)
                    current = ready
                    currentTile = tile
                    next = null
                    requestNext() // on prépare déjà la suivante pendant l'affichage
                }
                Timeline.Event.PHOTO_DONE -> {
                    current = null
                    currentTile = null
                }
                Timeline.Event.RETRY_LOAD -> requestNext()
                Timeline.Event.AUTO_RESUMED -> showNotice("Reprise du défilement", 2_500)
            }
        }
    }

    /** Choisit une vignette plutôt grande, vers le centre de l'écran. */
    private fun pickTile(): Tile {
        fun inCenter(t: Tile) = t.x >= width * 0.12f && t.x + t.w <= width * 0.88f &&
            t.y >= height * 0.1f && t.y + t.h <= height * 0.9f
        val choice = tiles.filter { inCenter(it) && it.scale > 0.8f && it !in recentTiles }
            .ifEmpty { tiles.filter { inCenter(it) } }
            .ifEmpty { tiles.filter { it.x >= 0 && it.x + it.w <= width && it.y >= 0 && it.y + it.h <= height } }
            .ifEmpty { tiles }
            .random()
        recentTiles.addLast(choice)
        while (recentTiles.size > 4) recentTiles.removeFirst()
        return choice
    }

    private fun drawMosaic(canvas: Canvas, now: Long) {
        for (t in tiles) {
            if (t.x > width + t.w * 0.3f || t.x + t.w * 1.3f < 0 ||
                t.y > height + t.h * 0.3f || t.y + t.h * 1.3f < 0
            ) continue
            tileRect.set(t.x, t.y, t.x + t.w, t.y + t.h)
            canvas.save()
            canvas.rotate(t.rotation, tileRect.centerX(), tileRect.centerY())
            val bmp = t.bitmap
            val fade = ((now - t.shownAt) / TILE_FADE_MS.toFloat()).coerceIn(0f, 1f)
            if (fade >= 1f) t.previous = null
            val prev = t.previous
            // les vignettes « lointaines » sont un peu plus sombres : impression de profondeur
            val depth = 0.55f + 0.45f * (t.scale - 0.55f) / 0.6f
            if (bmp == null || fade < 1f) {
                if (prev != null) {
                    bitmapPaint.alpha = (255 * depth).toInt()
                    canvas.drawBitmap(prev, null, tileRect, bitmapPaint)
                } else if (bmp == null) {
                    canvas.drawRect(tileRect, placeholderPaint)
                }
            }
            if (bmp != null) {
                val b = t.h * 0.02f
                framePaint.alpha = (230 * fade * depth).toInt()
                frameRect.set(tileRect.left - b, tileRect.top - b, tileRect.right + b, tileRect.bottom + b)
                canvas.drawRect(frameRect, framePaint)
                bitmapPaint.alpha = (255 * fade * depth).toInt()
                canvas.drawBitmap(bmp, null, tileRect, bitmapPaint)
            }
            canvas.restore()
        }
        bitmapPaint.alpha = 255
    }

    // Paramètres de la photo au premier plan, calculés une fois par image.
    private var fgZoom = 0f
    private var fgAlpha = 0f
    private var fgKenBurns = 0f

    private fun drawPhotoParams(now: Long): Float {
        val frame = timeline.frame(now)
        fgZoom = frame.zoom
        fgAlpha = frame.alpha
        fgKenBurns = frame.kenBurns
        return fgZoom
    }

    private fun drawPhoto(canvas: Canvas) {
        val photo = current ?: return
        val tile = currentTile ?: return
        if (fgAlpha <= 0f) return
        val bmp = photo.full
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()

        val tileW = tile.w
        val tileH = tile.h
        tileRect.set(tile.x, tile.y, tile.x + tileW, tile.y + tileH)

        // Départ : la photo recadrée exactement sur sa vignette. Arrivée : la photo entière en plein écran.
        val cover = max(tileW / bw, tileH / bh)
        centered(coverRect, tileRect.centerX(), tileRect.centerY(), bw * cover, bh * cover)
        val fit = min(width / bw, height / bh) * 0.95f // marge pour le cadre blanc
        centered(fitRect, width / 2f, height / 2f, bw * fit, bh * fit)

        lerp(photoRect, coverRect, fitRect, fgZoom)
        lerp(clipRect, tileRect, fitRect, fgZoom)

        // Léger zoom lent (effet « Ken Burns ») pendant l'affichage plein écran.
        val k = 1f + 0.06f * fgKenBurns * fgZoom
        centered(photoRect, clipRect.centerX(), clipRect.centerY(), photoRect.width() * k, photoRect.height() * k)

        // La photo garde l'inclinaison de sa vignette et se redresse en grandissant.
        canvas.save()
        canvas.rotate(tile.rotation * (1f - fgZoom), clipRect.centerX(), clipRect.centerY())

        // Ombre portée
        val shadowBase = 40f * fgAlpha * (0.4f + 0.6f * fgZoom)
        for (i in 1..4) {
            val o = gap * 0.35f * i
            shadowRect.set(clipRect.left - o, clipRect.top - o * 0.6f, clipRect.right + o, clipRect.bottom + o * 1.4f)
            shadowPaint.alpha = shadowBase.toInt()
            canvas.drawRect(shadowRect, shadowPaint)
        }

        // Cadre blanc proportionnel à la taille de la photo
        val fb = max(2f, min(clipRect.width(), clipRect.height()) * 0.02f)
        framePaint.alpha = (255 * fgAlpha).toInt()
        frameRect.set(clipRect.left - fb, clipRect.top - fb, clipRect.right + fb, clipRect.bottom + fb)
        canvas.drawRect(frameRect, framePaint)

        canvas.save()
        canvas.clipRect(clipRect)
        srcRect.set(0f, 0f, bw, bh)
        matrix.setRectToRect(srcRect, photoRect, Matrix.ScaleToFit.FILL)
        bitmapPaint.alpha = (255 * fgAlpha).toInt()
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
        bitmapPaint.alpha = 255
        canvas.restore()

        if (prefs.showFolder && photo.label.isNotEmpty() && fgZoom > 0f) {
            // Nom du dossier en bas de la photo, sur un léger dégradé sombre pour rester lisible
            val alpha = fgZoom.pow(3)
            val size = labelSize
            val scrimH = min(clipRect.height() * 0.45f, size * 6f)
            scrimPaint.shader = LinearGradient(
                0f, clipRect.bottom - scrimH, 0f, clipRect.bottom,
                Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP,
            )
            scrimPaint.alpha = (255 * alpha).toInt()
            canvas.drawRect(clipRect.left, clipRect.bottom - scrimH, clipRect.right, clipRect.bottom, scrimPaint)
            val normal = textPaint.textSize
            textPaint.textSize = size
            drawText(canvas, photo.label, clipRect.left + size * 0.9f, clipRect.bottom - size * 0.8f, alpha, Paint.Align.LEFT)
            textPaint.textSize = normal
        }
        canvas.restore()
    }

    private fun drawOverlays(canvas: Canvas, now: Long) {
        status?.let {
            drawText(canvas, it, width / 2f, height / 2f, 1f, Paint.Align.CENTER)
        }
        if (notice != null && now < noticeUntil) {
            drawText(canvas, notice!!, width / 2f, height - gap * 2.5f, 0.9f, Paint.Align.CENTER)
        }
        if (prefs.showClock) {
            if (now - clockUpdated > 1000) {
                clockText = DateFormat.getTimeFormat(context).format(Date())
                clockUpdated = now
            }
            val size = textPaint.textSize
            textPaint.textSize = size * 1.5f
            drawText(canvas, clockText, width - gap * 2.5f, gap * 2.5f + textPaint.textSize, 0.85f, Paint.Align.RIGHT)
            textPaint.textSize = size
        }
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, alpha: Float, align: Paint.Align) {
        if (alpha <= 0f) return
        textPaint.textAlign = align
        textPaint.alpha = (255 * alpha).toInt()
        textPaint.setShadowLayer(textPaint.textSize * 0.25f, 0f, textPaint.textSize * 0.06f, Color.argb((200 * alpha).toInt(), 0, 0, 0))
        canvas.drawText(text, x, y, textPaint)
    }

    private fun centered(out: RectF, cx: Float, cy: Float, w: Float, h: Float) {
        out.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun lerp(out: RectF, a: RectF, b: RectF, t: Float) {
        out.set(
            a.left + (b.left - a.left) * t,
            a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t,
            a.bottom + (b.bottom - a.bottom) * t,
        )
    }


    companion object {
        private const val TILE_FADE_MS = 700L
        private const val MAX_POOL = 120
    }
}
