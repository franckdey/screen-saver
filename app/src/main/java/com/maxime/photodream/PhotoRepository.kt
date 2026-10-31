package com.maxime.photodream

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Une photo prête à être affichée en grand. */
class LoadedPhoto(val full: Bitmap, val tile: Bitmap, val label: String)

/**
 * Liste les photos du partage, les télécharge et les garde en cache (réduites à la taille
 * de l'écran) pour que les passages suivants soient instantanés et marchent même si le NAS dort.
 */
class PhotoRepository(
    context: Context,
    private val prefs: Prefs,
    private val sourceFactory: (Prefs) -> PhotoSource? = ::smbSource,
) : Closeable {
    private val cacheDir = File(context.cacheDir, "photos").apply { mkdirs() }
    private var source: PhotoSource? = null
    private var photos: List<RemotePhoto> = emptyList()
    private val queue = ArrayDeque<RemotePhoto>()
    private var downloadsSinceTrim = 0

    /** Dernière erreur rencontrée, en clair. */
    var lastError: String? = null
        private set

    /** Vrai si le NAS était injoignable et qu'on se contente des photos déjà en cache. */
    var offline = false
        private set

    /** Parcourt le dossier réseau. Renvoie le nombre de photos disponibles. */
    suspend fun load(): Int = withContext(Dispatchers.IO) {
        lastError = null
        offline = false
        source?.close()
        source = null
        val src = sourceFactory(prefs)
        if (src == null) {
            lastError = "Aucun dossier réseau configuré (ouvre l'appli PhotoDream pour le régler)"
        } else {
            source = src
            try {
                photos = src.listPhotos(prefs.recursive)
                if (photos.isEmpty()) lastError = "Aucune photo trouvée dans ce dossier"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = SmbPhotoSource.describe(e)
                photos = emptyList()
            }
        }
        if (photos.isEmpty()) {
            // Plan B : les photos déjà téléchargées lors des passages précédents.
            photos = cacheDir.listFiles { f -> f.name.endsWith(".jpg") }
                .orEmpty()
                .map { RemotePhoto(CACHE_PREFIX + it.name, it.length(), 0L) }
            offline = photos.isNotEmpty()
        }
        queue.clear()
        photos.size
    }

    /** Photo suivante (ordre aléatoire, sans répétition avant d'avoir tout vu). */
    suspend fun nextPhoto(blurLevel: Int): LoadedPhoto? = withContext(Dispatchers.IO) {
        repeat(MAX_ATTEMPTS) {
            val photo = nextRemote() ?: return@withContext null
            val file = localFile(photo) ?: return@repeat
            val full = Images.decode(file, 1920) ?: return@repeat
            val label = if (photo.path.startsWith(CACHE_PREFIX)) "" else source?.folderLabel(photo).orEmpty()
            return@withContext LoadedPhoto(full, Images.makeTile(full, blurLevel), label)
        }
        null
    }

    /** Vignette (floutée selon le réglage) d'une photo au hasard, pour la mosaïque. */
    suspend fun nextTile(blurLevel: Int): Bitmap? = withContext(Dispatchers.IO) {
        repeat(MAX_ATTEMPTS) {
            val photo = nextRemote() ?: return@withContext null
            val file = localFile(photo) ?: return@repeat
            val small = Images.decode(file, 480, Bitmap.Config.RGB_565) ?: return@repeat
            val tile = Images.makeTile(small, blurLevel)
            small.recycle()
            return@withContext tile
        }
        null
    }

    @Synchronized
    private fun nextRemote(): RemotePhoto? {
        if (photos.isEmpty()) return null
        if (queue.isEmpty()) queue.addAll(photos.shuffled())
        return queue.removeFirst()
    }

    private suspend fun localFile(photo: RemotePhoto): File? {
        if (photo.path.startsWith(CACHE_PREFIX)) {
            return File(cacheDir, photo.path.removePrefix(CACHE_PREFIX)).takeIf { it.exists() }
        }
        val file = File(cacheDir, key(photo) + ".jpg")
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val src = source ?: return null
        return try {
            val bytes = src.read(photo.path)
            if (!Images.downscaleToJpeg(bytes, file)) return null
            if (++downloadsSinceTrim >= 20) {
                downloadsSinceTrim = 0
                trimCache(keep = file)
            }
            file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = SmbPhotoSource.describe(e)
            null
        } catch (e: OutOfMemoryError) {
            null // photo gigantesque : on passe à la suivante
        }
    }

    /** Supprime les photos les moins récemment vues quand le cache dépasse la taille choisie. */
    @Synchronized
    private fun trimCache(keep: File) {
        val files = cacheDir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        val max = prefs.cacheMb * 1024L * 1024L
        for (f in files) {
            if (total <= max) break
            if (f == keep) continue // la photo qu'on vient de télécharger va être affichée
            total -= f.length()
            f.delete()
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    override fun close() {
        source?.close()
        source = null
    }

    private fun key(photo: RemotePhoto): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${photo.path}|${photo.size}|${photo.modified}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** La source par défaut : le partage SMB réglé dans l'appli, ou null s'il n'y en a pas. */
        fun smbSource(prefs: Prefs): PhotoSource? {
            val location = SmbLocation.parse(prefs.address) ?: return null
            return SmbPhotoSource(location, prefs.user, prefs.password, prefs.domain)
        }

        private const val CACHE_PREFIX = "cache:"
        private const val MAX_ATTEMPTS = 8
    }
}
