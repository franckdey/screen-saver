package com.maxime.photodream

import android.service.dreams.DreamService
import android.view.KeyEvent

/**
 * L'économiseur d'écran proprement dit, lancé par Android TV après l'inactivité.
 * Selon le réglage, c'est la mosaïque animée ou le diaporama simple.
 * OK fixe la photo / relance, flèche droite passe à la suivante, flèche gauche revient à la précédente,
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
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.action == KeyEvent.ACTION_UP) v.togglePause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (event.action == KeyEvent.ACTION_UP) v.skip()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event.action == KeyEvent.ACTION_UP) v.previous()
                return true
            }
        }
        // les touches volume restent gérées par la TV
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}
