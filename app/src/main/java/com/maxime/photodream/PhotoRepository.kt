package com.maxime.photodream

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Une photo prête à être affichée en grand ; [photo] dit de quel fichier elle vient. */
class LoadedPhoto(
    val full: Bitmap,
    val tile: Bitmap,
    val label: String,
    val photo: RemotePhoto? = null,
    /** Empreinte de couleurs (voir [Variety]), pour ne pas enchaîner deux photos presque pareilles. */
    val print: FloatArray? = null,
)

/** Vignette de la mosaïque et la photo dont elle vient (pour l'agrandir ensuite). */
class TileImage(val bitmap: Bitmap, val photo: RemotePhoto)

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
    /** Ordre aléatoire du diaporama simple, gardé d'un lancement à l'autre. */
    private val orderFile = File(context.filesDir, "ordre-aleatoire.txt")
    /** Noms de lieux déjà trouvés à partir du GPS des photos. */
    private val places = PlaceNames(File(context.filesDir, "lieux.txt"))
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
            lastError = "Aucun dossier réseau ni photo du web choisi (ouvre l'appli PhotoDream pour le régler)"
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
        queue.addAll(order(list.filter { it !in known && it.path !in seenSet() }))
        tileQueue.retainAll(present)
    }

    /** Clé du dossier réglé : une autre adresse ou un autre choix de sous-dossiers invalide la liste. */
    private fun listingKey() = "${prefs.allAddresses.joinToString(";")}|${prefs.recursive}|${if (prefs.webPhotos) "web" else ""}"

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
        var deferred = 0
        repeat(MAX_ATTEMPTS) {
            val photo = nextRemote() ?: return@withContext null
            // en aléatoire, une photo aux couleurs presque pareilles à la précédente attend un peu
            var tooClose = false
            val reject: ((FloatArray) -> Boolean)? = if (prefs.mosaicRandom && deferred < MAX_DEFERRED) {
                { print -> Variety.tooClose(lastPrint, print).also { tooClose = it } }
            } else {
                null
            }
            val loaded = load(photo, blurLevel, reject = reject)
            if (loaded == null && tooClose) {
                deferred++
                defer(photo)
                return@repeat
            }
            loaded?.let {
                lastPrint = it.print
                return@withContext it
            }
        }
        null
    }

    /** Empreinte de la dernière photo montrée en grand dans la mosaïque. */
    @Volatile private var lastPrint: FloatArray? = null

    /** Remet la photo un peu plus loin dans la file, pour plus tard dans ce tour. */
    @Synchronized
    private fun defer(photo: RemotePhoto) {
        val list = queue.toMutableList()
        list.add(minOf(DEFER_BY, list.size), photo)
        queue.clear()
        queue.addAll(list)
    }

    /** Charge en grand une photo précise, par exemple celle d'une vignette visible. */
    suspend fun loadPhoto(photo: RemotePhoto, blurLevel: Int): LoadedPhoto? =
        withContext(Dispatchers.IO) { load(photo, blurLevel)?.also { lastPrint = it.print } }

    /**
     * Charge une photo pour le diaporama simple : pas de vignette floutée à préparer.
     * Elle compte comme vue, comme dans le diaporama habituel.
     */
    suspend fun loadFull(photo: RemotePhoto): LoadedPhoto? =
        withContext(Dispatchers.IO) { load(photo, 0, withTile = false) }

    /**
     * L'original de la photo, relu sur le NAS (ou le web) en plus grand que la copie du cache,
     * pour que le zoom reste net. Null hors ligne, ou si elle ne tient pas en mémoire.
     */
    suspend fun loadOriginal(photo: RemotePhoto): Bitmap? = withContext(Dispatchers.IO) {
        val src = source
        if (src == null || photo.path.startsWith(CACHE_PREFIX)) return@withContext null
        try {
            Images.decodeLarge(src.read(photo.path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Toutes les photos, dossier par dossier et par nom, pour le diaporama simple. */
    fun orderedPhotos(): List<RemotePhoto> = PhotoPlayer.ordered(photos)

    /** Ordre des photos en grand de la mosaïque : aléatoire ou dossier par dossier, selon le réglage. */
    private fun order(list: List<RemotePhoto>) =
        if (prefs.mosaicRandom) Variety.spreadPhotos(list.shuffled()) else PhotoPlayer.ordered(list)

    /** Diaporama simple en ordre aléatoire : l'ordre mémorisé, complété des nouvelles photos. */
    fun randomOrderPhotos(): List<RemotePhoto> {
        val saved = try {
            if (orderFile.exists()) orderFile.readLines() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return RandomOrder.reconcile(saved, photos).also { saveRandomOrder(it) }
    }

    fun saveRandomOrder(list: List<RemotePhoto>) {
        try {
            orderFile.writeText(list.joinToString("\n") { it.path })
        } catch (e: Exception) {
            // pas grave : un nouvel ordre sera tiré au prochain lancement
        }
    }

    private suspend fun load(
        photo: RemotePhoto,
        blurLevel: Int,
        withTile: Boolean = true,
        reject: ((FloatArray) -> Boolean)? = null,
    ): LoadedPhoto? {
        val file = localFile(photo) ?: run {
            markSeen(photo) // illisible : on la saute pour ce tour au lieu de la redemander
            return null
        }
        val full = Images.decode(file, 1920) ?: run {
            lastError = "Photo en cache illisible : ${name(photo)}"
            file.delete()
            markSeen(photo)
            return null
        }
        val print = Images.colorPrint(full)
        if (print != null && reject?.invoke(print) == true) {
            // pas vue : elle reviendra un peu plus tard
            full.recycle()
            return null
        }
        val label = caption(photo, file)
        markSeen(photo)
        return LoadedPhoto(full, if (withTile) Images.makeTile(full, blurLevel) else full, label, photo, print)
    }

    /** Vrai si la photo a déjà été montrée en grand pendant ce tour. */
    fun isSeen(photo: RemotePhoto): Boolean = synchronized(this) { photo.path in seenSet() }

    /**
     * Vignette (floutée selon le réglage) pour la mosaïque. Elle est prise parmi les photos
     * pas encore montrées : ce sont les candidates à l'agrandissement.
     */
    suspend fun nextTile(blurLevel: Int): TileImage? = withContext(Dispatchers.IO) {
        repeat(MAX_ATTEMPTS) {
            val photo = nextTileRemote() ?: return@withContext null
            val file = localFile(photo) ?: return@repeat
            val small = Images.decode(file, 480, Bitmap.Config.RGB_565) ?: return@repeat
            val tile = Images.makeTile(small, blurLevel)
            small.recycle()
            return@withContext TileImage(tile, photo)
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
        // une photo déjà montrée entre-temps (agrandie depuis sa vignette) ne repasse pas dans ce tour
        val seenNow = seenSet()
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (p.path !in seenNow) return p
        }
        var fresh = photos.filter { it.path !in seenNow }
        if (fresh.isEmpty()) {
            resetSeen()
            fresh = photos
        }
        queue.addAll(order(fresh))
        return queue.removeFirst()
    }

    @Synchronized
    private fun nextTileRemote(): RemotePhoto? {
        if (photos.isEmpty()) return null
        if (tileQueue.isEmpty()) {
            val seenNow = seenSet()
            val (fresh, old) = photos.partition { it.path !in seenNow }
            tileQueue.addAll(fresh.shuffled())
            tileQueue.addAll(old.shuffled())
        }
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
            // la copie réduite perd l'EXIF : on garde à côté le GPS et la date
            writeMeta(file, PhotoMeta.fromExif(bytes))
            // et l'auteur d'une photo du web, à montrer même hors ligne
            src.credit(photo)?.let { writeCredit(file, it) }
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
        orderFile.delete()
        resetSeen()
    }

    override fun close() {
        source?.close()
        source = null
    }

    /**
     * Texte affiché sur la photo : lieu (d'après le GPS) et date, ou le dossier et le nom de la photo à défaut.
     * Pour une photo déjà en cache sans ces infos, on relit juste le début du fichier.
     */
    private suspend fun caption(photo: RemotePhoto, file: File): String {
        // l'auteur et la licence d'une photo du web s'affichent toujours : sa licence le demande
        val credit = readCredit(file)
        if (!prefs.showFolder) return credit.orEmpty()
        val offline = photo.path.startsWith(CACHE_PREFIX)
        // sans lieu connu : le dossier et le nom de la photo
        val fallback = if (offline) "" else listOf(
            source?.folderLabel(photo).orEmpty(),
            source?.displayName(photo) ?: name(photo).substringBeforeLast('.'),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        var meta = readMeta(file)
        if (meta == null && !offline) {
            meta = try {
                source?.readHead(photo.path, EXIF_HEAD)?.let { PhotoMeta.fromExif(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            meta?.let { writeMeta(file, it) }
        }
        val place = meta?.let { m -> if (m.lat != null && m.lon != null) places.name(m.lat, m.lon) else null }
        return listOfNotNull(Captions.format(place, meta?.taken, fallback).ifBlank { null }, credit).joinToString(" · ")
    }

    private fun creditFile(file: File) = File(file.path.removeSuffix(".jpg") + ".credit")

    private fun readCredit(file: File): String? = try {
        creditFile(file).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun writeCredit(file: File, credit: String) {
        try {
            creditFile(file).writeText(credit)
        } catch (e: Exception) {
            // pas grave : le crédit reviendra au prochain téléchargement
        }
    }

    private fun metaFile(file: File) = File(file.path.removeSuffix(".jpg") + ".meta")

    private fun readMeta(file: File): PhotoMeta? = try {
        metaFile(file).takeIf { it.exists() }?.readText()?.let(PhotoMeta::parse)
    } catch (e: Exception) {
        null
    }

    private fun writeMeta(file: File, meta: PhotoMeta) {
        try {
            metaFile(file).writeText(meta.serialize())
        } catch (e: Exception) {
            // pas grave : on retrouvera l'info plus tard
        }
    }

    private fun name(photo: RemotePhoto) = photo.path.substringAfterLast('\\')

    private fun key(photo: RemotePhoto): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${photo.path}|${photo.size}|${photo.modified}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * La source par défaut : le ou les partages SMB réglés dans l'appli, ou null s'il n'y en a pas.
         * Avec plusieurs dossiers, leurs photos sont réunies.
         */
        fun smbSource(prefs: Prefs): PhotoSource? {
            // le dossier principal garde ses chemins tels quels, les autres sont repérés par leur adresse :
            // cocher ou décocher un dossier ne change pas le nom des photos des autres
            val parts = prefs.enabledFolders.mapNotNull { (address, main) ->
                smbSourceFor(prefs, address)?.let { MultiPhotoSource.Part(address, it, if (main) "" else MultiPhotoSource.tagOf(address)) }
            } + listOfNotNull(
                // les paysages du web, à part : leurs chemins commencent par @web@
                if (prefs.webPhotos) MultiPhotoSource.Part(WEB_LABEL, WikimediaSource(File(prefs.filesDir, "credits-web.txt")), WEB_TAG) else null,
            )
            return when {
                parts.isEmpty() -> null
                parts.size == 1 && parts[0].tag.isEmpty() -> parts[0].source
                else -> MultiPhotoSource(parts)
            }
        }

        /** Un seul dossier réseau, avec les identifiants réglés ; null si l'adresse n'est pas valable. */
        fun smbSourceFor(prefs: Prefs, address: String): SmbPhotoSource? {
            val location = SmbLocation.parse(address) ?: return null
            return SmbPhotoSource(
                location, prefs.user, prefs.password, prefs.domain,
                compat = prefs.smbCompat,
                onCompat = { prefs.smbCompat = true },
            )
        }

        const val WEB_LABEL = "Paysages du web (Wikimedia Commons)"
        private const val WEB_TAG = "web"
        private const val CACHE_PREFIX = "cache:"
        private const val MAX_ATTEMPTS = 8
        /** Photos trop ressemblantes écartées au plus avant d'en prendre une quand même. */
        private const val MAX_DEFERRED = 3
        /** De combien de places une photo trop ressemblante recule dans la file. */
        private const val DEFER_BY = 10
        /** L'EXIF est au début du fichier : 256 Ko suffisent largement. */
        private const val EXIF_HEAD = 256 * 1024
    }
}
