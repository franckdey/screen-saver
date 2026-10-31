package com.maxime.photodream

/**
 * Les photos déjà agrandies, dans l'ordre, pour pouvoir revenir en arrière avec la flèche gauche.
 * [shown] est la position de la dernière photo agrandie : quand on revient en arrière, elle n'est
 * plus en fin de liste, et le diaporama rejoue la suite avant de passer à de nouvelles photos.
 */
class PhotoHistory(private val max: Int = 200) {

    private val items = ArrayList<RemotePhoto>()

    /** Position de la dernière photo agrandie, -1 au départ. */
    var shown = -1
        private set

    val size get() = items.size

    operator fun get(index: Int) = items[index]

    /**
     * Note la photo qui vient d'être agrandie. [index] est sa position si elle vient de
     * l'historique, -1 pour une nouvelle photo (ajoutée à la fin).
     */
    fun show(photo: RemotePhoto, index: Int) {
        if (index in items.indices && items[index] == photo) {
            shown = index
            return
        }
        items += photo
        while (items.size > max) items.removeAt(0)
        shown = items.size - 1
    }

    /** Position de la photo à rejouer après celle affichée, ou null s'il faut une nouvelle photo. */
    fun replayIndex(): Int? = if (shown >= 0 && shown < items.size - 1) shown + 1 else null
}
