package com.maxime.photodream

import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/**
 * Plusieurs dossiers réseau réunis en un seul. Les photos du premier dossier gardent leur chemin
 * tel quel (rien ne change pour qui n'a qu'un dossier) ; celles des suivants sont préfixées par
 * une courte empreinte de leur adresse, « @1a2b3c4d@ », pour savoir où les relire.
 */
class MultiPhotoSource(private val parts: List<Part>) : PhotoSource {

    /** Un dossier : son adresse telle que saisie, et la source qui le lit. */
    class Part(val address: String, val source: PhotoSource, val tag: String)

    /**
     * Lit tous les dossiers. Un dossier injoignable est ignoré tant qu'un autre répond ;
     * si aucun ne répond, l'erreur du premier remonte.
     */
    override suspend fun listPhotos(recursive: Boolean, limit: Int): List<RemotePhoto> {
        val all = ArrayList<RemotePhoto>()
        var firstError: Exception? = null
        var anyOk = false
        for (part in parts) {
            try {
                val list = part.source.listPhotos(recursive, limit)
                anyOk = true
                list.mapTo(all) { if (part.tag.isEmpty()) it else it.copy(path = prefix(part.tag) + it.path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (!anyOk) throw firstError ?: IllegalStateException("Aucun dossier réseau lisible")
        return if (all.size > limit) all.shuffled().take(limit) else all
    }

    override suspend fun read(path: String): ByteArray {
        val (part, inner) = route(path)
        return part.source.read(inner)
    }

    override suspend fun readHead(path: String, maxBytes: Int): ByteArray {
        val (part, inner) = route(path)
        return part.source.readHead(inner, maxBytes)
    }

    override fun folderLabel(photo: RemotePhoto): String {
        val (part, inner) = route(photo.path)
        return part.source.folderLabel(photo.copy(path = inner))
    }

    /** L'adresse du dossier d'une photo et son chemin dans ce dossier (pour le test d'une photo). */
    fun locate(path: String): Pair<String, String> {
        val (part, inner) = route(path)
        return part.address to inner
    }

    override fun close() {
        parts.forEach { runCatching { it.source.close() } }
    }

    private fun route(path: String): Pair<Part, String> {
        if (path.startsWith("@")) {
            val end = path.indexOf('@', 1)
            if (end > 0) {
                val tag = path.substring(1, end)
                parts.firstOrNull { it.tag == tag }?.let { return it to path.substring(end + 1) }
            }
        }
        return (parts.firstOrNull { it.tag.isEmpty() } ?: parts.first()) to path
    }

    companion object {
        private fun prefix(tag: String) = "@$tag@"

        /** Empreinte courte et stable d'une adresse. */
        fun tagOf(address: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(address.trim().lowercase().toByteArray())
            return digest.take(4).joinToString("") { "%02x".format(it) }
        }

        /** Le premier dossier sans préfixe, les autres avec l'empreinte de leur adresse. */
        fun of(sources: List<Pair<String, PhotoSource>>): MultiPhotoSource = MultiPhotoSource(
            sources.mapIndexed { i, (address, src) -> Part(address, src, if (i == 0) "" else tagOf(address)) },
        )
    }
}
