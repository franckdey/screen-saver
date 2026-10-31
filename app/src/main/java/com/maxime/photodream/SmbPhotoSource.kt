package com.maxime.photodream

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
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
import java.io.Closeable
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
) : Closeable {

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
    )
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
                block(connectedShare())
            }
        }
    }

    suspend fun listPhotos(recursive: Boolean, limit: Int = 50_000): List<RemotePhoto> = withShare { disk ->
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
                } else if (isImage(name)) {
                    out += RemotePhoto(path, info.endOfFile, info.lastWriteTime.toEpochMillis())
                }
            }
        }
        walk(location.folder, 0)
        out
    }

    suspend fun read(path: String): ByteArray = withShare { disk ->
        disk.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { file -> file.inputStream.use { it.readBytes() } }
    }

    /** Nom du dossier de la photo, relatif au dossier choisi (« 2019 Corse › Plage »). */
    fun folderLabel(photo: RemotePhoto): String {
        var dir = photo.path.substringBeforeLast('\\', "")
        if (location.folder.isNotEmpty()) {
            dir = dir.removePrefix(location.folder).trimStart('\\')
        }
        return dir.replace("\\", " › ")
    }

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
        private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

        fun isImage(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "heic" || ext == "heif") return android.os.Build.VERSION.SDK_INT >= 28
            return ext in EXTENSIONS
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
