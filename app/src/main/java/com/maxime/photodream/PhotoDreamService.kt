package com.maxime.photodream

import android.service.dreams.DreamService
import android.view.KeyEvent

/**
 * L'économiseur d'écran proprement dit, lancé par Android TV après l'inactivité.
 * Selon le réglage, c'est la mosaïque animée ou le diaporama simple.
 * OK fixe la photo / relance, flèche droite passe à la suivante, flèche gauche revient à la précédente,
 * flèche bas fige la mosaïque pour choisir une photo à agrandir (Retour pour reprendre),
 * toute autre touche quitte l'économiseur comme d'habitude.
 */
class PhotoDreamService : DreamService() {
    private var view: SlideshowView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true // pour recevoir les touches OK et flèches, et les appuis sur l'écran
        isFullscreen = true
        isScreenBright = true
        view = SlideshowView(this, simple = Prefs(this).simpleScreensaver).also { setContentView(it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val v = view ?: return super.dispatchKeyEvent(event)
        // les touches volume restent gérées par la TV
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) return super.dispatchKeyEvent(event)
        // OK, flèches et, pendant le choix d'une photo, Retour ; toute autre touche quitte
        if (event.action == KeyEvent.ACTION_UP && !v.handleKey(event.keyCode)) finish()
        return true
    }
}
