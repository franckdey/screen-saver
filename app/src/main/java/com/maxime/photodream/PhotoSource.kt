package com.maxime.photodream

import java.io.Closeable

/** D'où viennent les photos (aujourd'hui un partage SMB ; une fausse source dans les tests). */
interface PhotoSource : Closeable {
    suspend fun listPhotos(recursive: Boolean, limit: Int = 50_000): List<RemotePhoto>

    suspend fun read(path: String): ByteArray

    /** Début du fichier seulement (pour lire l'EXIF sans tout télécharger). */
    suspend fun readHead(path: String, maxBytes: Int): ByteArray = read(path)

    /** Nom du dossier de la photo, tel qu'on l'affiche sous la photo. */
    fun folderLabel(photo: RemotePhoto): String
}
