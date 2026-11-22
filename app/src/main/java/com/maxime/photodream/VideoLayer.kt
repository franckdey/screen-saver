package com.maxime.photodream

import android.content.Context
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import java.io.File

/**
 * Lit une vidéo par-dessus le diaporama, exactement à la place de son image. L'image reste
 * visible dessous tant que la première image de la vidéo n'est pas prête, sans écran noir.
 * Tout se passe sur le fil principal (celui de l'animation).
 */
class VideoLayer(context: Context, host: FrameLayout) {

    private val view = TextureView(context).apply {
        alpha = 0f // invisible tant qu'aucune vidéo ne joue
        isFocusable = false
        isClickable = false
    }
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantPaused = false
    private var maxMs = 0
    private var onEnd: ((LoadedPhoto, Boolean) -> Unit)? = null
    private var pendingFile: File? = null
    private var muted = true
    /** Début de la lecture demandée, et vrai une fois la première image affichée. */
    private var startedAt = 0L
    private var rendering = false

    /** La photo (vidéo) en cours de lecture, ou null. */
    var playing: LoadedPhoto? = null
        private set

    init {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                pendingFile?.let { begin(it) }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stop()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        host.addView(view, FrameLayout.LayoutParams(1, 1))
    }

    /**
     * Lit la vidéo de [photo] dans le rectangle [rect] (en pixels de la vue), sans le son si [muted],
     * au plus [maxMs] millisecondes (0 = jusqu'au bout). [onEnd] reçoit la photo et vrai en cas d'erreur.
     */
    fun start(photo: LoadedPhoto, rect: RectF, muted: Boolean, maxMs: Int, onEnd: (LoadedPhoto, Boolean) -> Unit) {
        val file = photo.video ?: return
        stop()
        playing = photo
        this.muted = muted
        this.maxMs = maxMs
        this.onEnd = onEnd
        startedAt = SystemClock.uptimeMillis()
        rendering = false
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val w = rect.width().toInt().coerceAtLeast(1)
        val h = rect.height().toInt().coerceAtLeast(1)
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            view.layoutParams = lp
        }
        view.translationX = rect.left
        view.translationY = rect.top
        if (surface != null) begin(file) else pendingFile = file
    }

    private fun begin(file: File) {
        pendingFile = null
        val s = surface ?: return
        val mp = MediaPlayer()
        player = mp
        prepared = false
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            mp.setSurface(s)
            mp.setDataSource(file.path)
            if (muted) mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                prepared = true
                if (!wantPaused) it.start()
            }
            mp.setOnInfoListener { it, what, _ ->
                // la première image est affichée : la vidéo peut recouvrir son image
                if (player === it && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    rendering = true
                    view.alpha = 1f
                }
                false
            }
            mp.setOnCompletionListener { if (player === it) finish(false) }
            mp.setOnErrorListener { it, _, _ ->
                if (player === it) finish(true)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            finish(true)
        }
    }

    /** Met en pause ou relance la vidéo (OK de la télécommande). */
    fun setPaused(paused: Boolean) {
        wantPaused = paused
        val mp = player ?: return
        if (!prepared) return
        try {
            if (paused && mp.isPlaying) mp.pause() else if (!paused && !mp.isPlaying) mp.start()
        } catch (e: IllegalStateException) {
            // lecteur déjà arrêté
        }
    }

    /**
     * À chaque image de l'animation : arrête la vidéo une fois la durée maximale atteinte, et
     * abandonne une vidéo qui ne démarre pas (le diaporama ne doit jamais rester bloqué dessus).
     */
    fun tick() {
        if (playing == null) return
        if (!rendering && SystemClock.uptimeMillis() - startedAt > START_TIMEOUT_MS) {
            finish(true)
            return
        }
        val mp = player ?: return
        if (!prepared || maxMs <= 0) return
        val pos = try {
            mp.currentPosition
        } catch (e: IllegalStateException) {
            return
        }
        if (pos >= maxMs) finish(false)
    }

    private fun finish(error: Boolean) {
        val photo = playing
        val callback = onEnd
        stop()
        if (photo != null) callback?.invoke(photo, error)
    }

    /** Arrête la vidéo et cache sa surface : l'image de la vidéo redevient visible. */
    fun stop() {
        pendingFile = null
        playing = null
        onEnd = null
        prepared = false
        view.alpha = 0f
        player?.let { runCatching { it.release() } }
        player = null
    }

    private companion object {
        /** Une vidéo dont aucune image n'est apparue au bout de ce temps est considérée illisible. */
        const val START_TIMEOUT_MS = 15_000L
    }
}
