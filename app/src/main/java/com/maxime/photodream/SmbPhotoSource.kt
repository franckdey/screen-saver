package com.maxime.photodream

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Une photo sur le partage réseau. [path] est relatif à la racine du partage. */
data class RemotePhoto(val path: String, val size: Long, val modified: Long)

/** Emplacement SMB découpé : smb://hôte/partage/dossier/sous-dossier */
data class SmbLocation(val host: String, val share: String, val folder: String) {
    companion object {
        fun parse(raw: String): SmbLocation? {
            val cleaned = raw.trim()
                .removePrefix("smb://").removePrefix("SMB://")
                .replace('\\', '/')
                .trim('/')
            val parts = cleaned.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return SmbLocation(parts[0], parts[1], parts.drop(2).joinToString("\\"))
        }
    }
}

class SmbPhotoSource(
    private val location: SmbLocation,
    private val user: String,
    private val password: String,
    private val domain: String,
    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT,
    compat: Boolean = false,
    /** Appelé quand la source passe d'elle-même en mode compatible, pour s'en souvenir. */
    private val onCompat: () -> Unit = {},
    /** Faux pour le diagnostic, qui veut tester chaque mode séparément. */
    private val autoCompat: Boolean = true,
) : PhotoSource {

    /** Vrai en mode compatible : SMB 2.0/2.1 seulement, sans chiffrement ni signature imposée. */
    var compatMode = compat
        private set

    private var client = SMBClient(config(compat))

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private val mutex = Mutex()

    private fun connectedShare(): DiskShare {
        share?.let { if (it.isConnected) return it }
        closeQuietly()
        val conn = client.connect(location.host)
        connection = conn
        val auth = if (user.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(user, password.toCharArray(), domain.ifBlank { null })
        }
        val ses = conn.authenticate(auth)
        session = ses
        val disk = ses.connectShare(location.share) as? DiskShare
            ?: throw IOException("« ${location.share} » n'est pas un dossier partagé")
        share = disk
        return disk
    }

    /** Exécute [block] sur le partage, avec une reconnexion automatique en cas de coupure. */
    private suspend fun <T> withShare(block: (DiskShare) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                block(connectedShare())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SMBApiException) {
                throw e // erreur « logique » (mot de passe, dossier…) : inutile de réessayer
            } catch (e: Exception) {
                closeQuietly()
                try {
                    block(connectedShare())
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: SMBApiException) {
                    throw e2
                } catch (e2: Exception) {
                    if (compatMode || !autoCompat) throw e2
                    // Deux échecs de suite (souvent un délai dépassé pendant une lecture) :
                    // on réessaie avec un protocole plus simple que certains NAS gèrent mieux.
                    switchToCompat()
                    block(connectedShare())
                }
            }
        }
    }

    private fun switchToCompat() {
        closeQuietly()
        runCatching { client.close() }
        compatMode = true
        client = SMBClient(config(true))
        onCompat()
    }

    override suspend fun listPhotos(recursive: Boolean, limit: Int): List<RemotePhoto> = withShare { disk ->
        val out = ArrayList<RemotePhoto>()
        fun walk(dir: String, depth: Int) {
            for (info in disk.list(dir)) {
                if (out.size >= limit) return
                val name = info.fileName
                // ignore les dossiers techniques (Synology @eaDir, #recycle, fichiers cachés…)
                if (name == "." || name == ".." || name.startsWith(".") ||
                    name.startsWith("@") || name.startsWith("#")
                ) continue
                val path = if (dir.isEmpty()) name else "$dir\\$name"
                val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                if (isDir) {
                    if (recursive && depth < 15) walk(path, depth + 1)
                } else if (isImage(name, sdkInt)) {
                    out += RemotePhoto(path, info.endOfFile, info.lastWriteTime.toEpochMillis())
                }
            }
        }
        walk(location.folder, 0)
        out
    }

    override suspend fun read(path: String): ByteArray = read(path) {}

    /**
     * Lit un fichier par blocs, une requête à la fois (pas de lecture anticipée, que certains
     * serveurs gèrent mal). [onProgress] reçoit le nombre d'octets déjà reçus.
     */
    suspend fun read(path: String, onProgress: (Long) -> Unit): ByteArray = withShare { disk ->
        disk.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { file ->
            // On s'arrête à la taille connue du fichier : certains serveurs (Freebox) ne
            // répondent jamais à une lecture demandée pile à la fin du fichier.
            val size = file.fileInformation.standardInformation.endOfFile
            val out = ByteArrayOutputStream(size.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            val buffer = ByteArray(READ_CHUNK)
            var offset = 0L
            while (offset < size) {
                val wanted = minOf(READ_CHUNK.toLong(), size - offset).toInt()
                val n = file.read(buffer, offset, 0, wanted)
                if (n <= 0) break
                out.write(buffer, 0, n)
                offset += n
                onProgress(offset)
            }
            if (offset < size) throw IOException("Fichier incomplet : $offset octets sur $size")
            out.toByteArray()
        }
    }

    /** Nom du dossier de la photo, relatif au dossier choisi (« 2019 Corse › Plage »). */
    override fun folderLabel(photo: RemotePhoto): String = folderLabel(location.folder, photo.path)

    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        share = null
        session = null
        connection = null
    }

    override fun close() {
        closeQuietly()
        runCatching { client.close() }
    }

    companion object {
        private const val READ_CHUNK = 64 * 1024

        /** Réglages du client SMB ; [compat] : SMB 2.0/2.1 seulement, sans chiffrement. */
        fun config(compat: Boolean): SmbConfig {
            val builder = SmbConfig.builder()
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                // blocs de 64 Ko (un seul « crédit » SMB), mieux supportés par les petits NAS
                .withReadBufferSize(READ_CHUNK)
            if (compat) {
                builder.withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
                    .withEncryptData(false)
                    .withSigningRequired(false)
            }
            return builder.build()
        }

        private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

        /** HEIC n'est lisible qu'à partir d'Android 9 (API 28). */
        fun isImage(name: String, sdkInt: Int = android.os.Build.VERSION.SDK_INT): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "heic" || ext == "heif") return sdkInt >= 28
            return ext in EXTENSIONS
        }

        /** Dossier d'une photo relatif au dossier choisi : « 2019 Corse › Plage ». */
        fun folderLabel(rootFolder: String, path: String): String {
            var dir = path.substringBeforeLast('\\', "")
            if (rootFolder.isNotEmpty()) {
                dir = dir.removePrefix(rootFolder).trimStart('\\')
            }
            return dir.replace("\\", " › ")
        }

        /** Traduit une erreur technique en message compréhensible. */
        fun describe(error: Throwable): String {
            // smbj enveloppe souvent l'erreur réseau : on regarde toute la chaîne des causes
            val chain = generateSequence(error) { it.cause }.take(8).toList()
            val smb = chain.filterIsInstance<SMBApiException>().firstOrNull()
            return when {
                smb?.status == NtStatus.STATUS_LOGON_FAILURE ->
                    "Identifiant ou mot de passe refusé par le NAS"
                smb?.status == NtStatus.STATUS_ACCESS_DENIED ->
                    "Accès refusé (vérifie les droits de l'utilisateur sur ce dossier)"
                smb?.status == NtStatus.STATUS_BAD_NETWORK_NAME ->
                    "Partage introuvable (vérifie le nom juste après l'adresse du NAS)"
                smb?.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
                    smb?.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                    "Dossier introuvable sur le partage"
                smb != null -> "Erreur SMB : ${smb.status}"
                chain.any { it is UnknownHostException } ->
                    "Adresse du NAS inconnue (essaie son adresse IP)"
                chain.any { it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException } ->
                    "NAS injoignable (allumé ? partage SMB activé ? même réseau ?)"
                else -> error.message ?: error.javaClass.simpleName
            }
        }
    }
}
