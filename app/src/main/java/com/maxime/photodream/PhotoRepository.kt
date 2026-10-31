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
    /** Liste des photos du dernier parcours, pour démarrer sans relire tout le NAS. */
    private val listingFile = File(context.filesDir, "liste-photos.txt")
    private var source: PhotoSource? = null
    @Volatile private var photos: List<RemotePhoto> = emptyList()
    /** Ordre des photos en grand : celles pas encore montrées d'abord. */
    private val queue = ArrayDeque<RemotePhoto>()
    /** Ordre des vignettes de la mosaïque, indépendant. */
    private val tileQueue = ArrayDeque<RemotePhoto>()
    /** Photos déjà montrées en grand, gardées d'un lancement à l'autre. */
    private val seenFile = File(context.filesDir, "photos-vues.txt")
    private var seen: MutableSet<String>? = null
    private var downloadsSinceTrim = 0

    /** Dernière erreur rencontrée, en clair. */
    var lastError: String? = null
        private set

    /** Vrai si le NAS était injoignable et qu'on se contente des photos déjà en cache. */
    var offline = false
        private set

    /** Vrai si [load] a repris la liste mémorisée : appeler [refresh] pour la remettre à jour. */
    var usedSavedListing = false
        private set

    /**
     * Prépare la liste des photos. Renvoie le nombre de photos disponibles.
     * Avec [useSaved], la liste mémorisée du même dossier est reprise tout de suite
     * (un gros NAS peut mettre plusieurs minutes à être parcouru).
     */
    suspend fun load(useSaved: Boolean = false): Int = withContext(Dispatchers.IO) {
        lastError = null
        offline = false
        usedSavedListing = false
        source?.close()
        source = null
        val src = sourceFactory(prefs)
        if (src == null) {
            lastError = "Aucun dossier réseau configuré (ouvre l'appli PhotoDream pour le régler)"
        } else {
            source = src
            val saved = if (useSaved) readListing() else null
            if (!saved.isNullOrEmpty()) {
                photos = saved
                usedSavedListing = true
            } else {
                try {
                    photos = src.listPhotos(prefs.recursive)
                    if (photos.isEmpty()) lastError = "Aucune photo trouvée dans ce dossier"
                    saveListing(photos)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = SmbPhotoSource.describe(e)
                    photos = emptyList()
                }
            }
        }
        if (photos.isEmpty()) {
            // Plan B : les photos déjà téléchargées lors des passages précédents.
            photos = cacheDir.listFiles { f -> f.name.endsWith(".jpg") }
                .orEmpty()
                .map { RemotePhoto(CACHE_PREFIX + it.name, it.length(), 0L) }
            offline = photos.isNotEmpty()
        }
        synchronized(this) {
            queue.clear()
            tileQueue.clear()
        }
        photos.size
    }

    /**
     * Relit le dossier en arrière-plan après un démarrage sur la liste mémorisée, pour
     * prendre en compte les photos ajoutées ou supprimées. Renvoie vrai si la liste a changé.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        // Connexion séparée : le parcours peut être long et ne doit pas bloquer les téléchargements.
        val src = sourceFactory(prefs) ?: return@withContext false
        val fresh = try {
            src.listPhotos(prefs.recursive)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext false // NAS injoignable : on garde la liste mémorisée
        } finally {
            runCatching { src.close() }
        }
        if (fresh.isEmpty()) return@withContext false
        saveListing(fresh)
        if (fresh.toSet() == photos.toSet()) return@withContext false
        replacePhotos(fresh)
        true
    }

    @Synchronized
    private fun replacePhotos(list: List<RemotePhoto>) {
        photos = list
        // garde l'ordre en cours pour les photos toujours présentes, ajoute les nouvelles
        val present = list.toHashSet()
        val kept = queue.filter { it in present }
        val known = kept.toHashSet()
        queue.clear()
        queue.addAll(kept)
        queue.addAll(list.filter { it !in known && it.path !in seenSet() }.shuffled())
        tileQueue.retainAll(present)
    }

    /** Clé du dossier réglé : une autre adresse ou un autre choix de sous-dossiers invalide la liste. */
    private fun listingKey() = "${prefs.address.trim()}|${prefs.recursive}"

    private fun saveListing(list: List<RemotePhoto>) {
        if (list.isEmpty()) return
        try {
            val tmp = File(listingFile.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(listingKey())
                w.newLine()
                for (p in list) {
                    w.write("${p.size}\t${p.modified}\t${p.path}")
                    w.newLine()
                }
            }
            tmp.renameTo(listingFile)
        } catch (e: Exception) {
            // pas grave : on relira le dossier la prochaine fois
        }
    }

    private fun readListing(): List<RemotePhoto>? = try {
        listingFile.takeIf { it.exists() }?.bufferedReader()?.use { r ->
            if (r.readLine() != listingKey()) return@use null
            r.lineSequence().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@mapNotNull null
                RemotePhoto(parts[2], parts[0].toLongOrNull() ?: return@mapNotNull null, parts[1].toLongOrNull() ?: 0L)
            }.toList()
        }
    } catch (e: Exception) {
        null
    }

    /** Photo suivante (ordre aléatoire, sans répétition avant d'avoir tout vu). */
    suspend fun nextPhoto(blurLevel: Int): LoadedPhoto? = withContext(Dispatchers.IO) {
        repeat(MAX_ATTEMPTS) {
            val photo = nextRemote() ?: return@withContext null
            val file = localFile(photo) ?: return@repeat
            val full = Images.decode(file, 1920) ?: run {
                lastError = "Photo en cache illisible : ${name(photo)}"
                file.delete()
                return@repeat
            }
            val label = if (photo.path.startsWith(CACHE_PREFIX)) "" else source?.folderLabel(photo).orEmpty()
            markSeen(photo)
            return@withContext LoadedPhoto(full, Images.makeTile(full, blurLevel), label)
        }
        null
    }

    /** Vignette (floutée selon le réglage) d'une photo au hasard, pour la mosaïque. */
    suspend fun nextTile(blurLevel: Int): Bitmap? = withContext(Dispatchers.IO) {
        repeat(MAX_ATTEMPTS) {
            val photo = nextTileRemote() ?: return@withContext null
            val file = localFile(photo) ?: return@repeat
            val small = Images.decode(file, 480, Bitmap.Config.RGB_565) ?: return@repeat
            val tile = Images.makeTile(small, blurLevel)
            small.recycle()
            return@withContext tile
        }
        null
    }

    /**
     * Prochaine photo à montrer en grand : d'abord celles jamais montrées (même lors des
     * lancements précédents). Quand tout est passé, on oublie et on recommence un tour.
     */
    @Synchronized
    private fun nextRemote(): RemotePhoto? {
        if (photos.isEmpty()) return null
        if (queue.isEmpty()) {
            val seenNow = seenSet()
            var fresh = photos.filter { it.path !in seenNow }
            if (fresh.isEmpty()) {
                resetSeen()
                fresh = photos
            }
            queue.addAll(fresh.shuffled())
        }
        return queue.removeFirst()
    }

    @Synchronized
    private fun nextTileRemote(): RemotePhoto? {
        if (photos.isEmpty()) return null
        if (tileQueue.isEmpty()) tileQueue.addAll(photos.shuffled())
        return tileQueue.removeFirst()
    }

    @Synchronized
    private fun seenSet(): MutableSet<String> = seen ?: try {
        if (seenFile.exists()) seenFile.readLines().filterTo(HashSet()) { it.isNotEmpty() } else HashSet()
    } catch (e: Exception) {
        HashSet()
    }.also { seen = it }

    @Synchronized
    private fun markSeen(photo: RemotePhoto) {
        if (seenSet().add(photo.path)) {
            try {
                seenFile.appendText(photo.path + "\n")
            } catch (e: Exception) {
                // pas grave : la photo pourra revenir au prochain lancement
            }
        }
    }

    @Synchronized
    private fun resetSeen() {
        seen = HashSet()
        seenFile.delete()
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
            if (bytes.isEmpty()) {
                lastError = "Fichier vide : ${name(photo)}"
                return null
            }
            if (!Images.downscaleToJpeg(bytes, file)) {
                lastError = "Format de photo non reconnu par l'appareil : ${name(photo)}"
                return null
            }
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
            lastError = "Photo trop grande pour la mémoire de l'appareil : ${name(photo)}"
            null // on passe à la suivante
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
        listingFile.delete()
        resetSeen()
    }

    override fun close() {
        source?.close()
        source = null
    }

    private fun name(photo: RemotePhoto) = photo.path.substringAfterLast('\\')

    private fun key(photo: RemotePhoto): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${photo.path}|${photo.size}|${photo.modified}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** La source par défaut : le partage SMB réglé dans l'appli, ou null s'il n'y en a pas. */
        fun smbSource(prefs: Prefs): PhotoSource? {
            val location = SmbLocation.parse(prefs.address) ?: return null
            return SmbPhotoSource(
                location, prefs.user, prefs.password, prefs.domain,
                compat = prefs.smbCompat,
                onCompat = { prefs.smbCompat = true },
            )
        }

        private const val CACHE_PREFIX = "cache:"
        private const val MAX_ATTEMPTS = 8
    }
}
