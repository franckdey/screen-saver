package com.maxime.photodream

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/** Version publiée sur GitHub (fichier version.json de la Release « latest »). */
data class UpdateInfo(val versionCode: Int, val versionName: String)

/**
 * Vérifie et télécharge les mises à jour publiées par GitHub Actions.
 * Aucune dépendance Android ici, pour pouvoir tester sur un simple JVM ;
 * l'installation elle-même est faite par [ApkInstaller].
 */
class Updater(private val baseUrl: String, private val currentVersion: Int) {

    /** Renvoie la version publiée si elle est plus récente que celle installée, sinon null. */
    fun check(): UpdateInfo? {
        val info = parse(open("version.json").use { it.inputStream.bufferedReader().readText() })
        return info.takeIf { it.versionCode > currentVersion }
    }

    /** Télécharge l'APK dans [dest] ; [onProgress] reçoit un pourcentage (0..100), ou -1 si la taille est inconnue. */
    fun download(dest: File, onProgress: (Int) -> Unit = {}): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        open(APK_NAME).use { conn ->
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -2
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) {
                part.delete()
                throw IOException("Téléchargement incomplet (${part.length()} / $total octets)")
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("Impossible d'enregistrer ${dest.name}")
        return dest
    }

    private fun open(name: String): Connection {
        val conn = URL(baseUrl + name).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Cache-Control", "no-cache")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("Le serveur a répondu $code pour $name")
        }
        return Connection(conn)
    }

    private class Connection(private val conn: HttpURLConnection) : AutoCloseable {
        val inputStream get() = conn.inputStream
        val contentLengthLong get() = conn.contentLengthLong
        override fun close() = conn.disconnect()
    }

    companion object {
        const val APK_NAME = "PhotoDream.apk"

        private val CODE = Regex("\"versionCode\"\\s*:\\s*(\\d+)")
        private val NAME = Regex("\"versionName\"\\s*:\\s*\"([^\"]*)\"")

        /** Lit {"versionCode": 12, "versionName": "1.12"}. */
        fun parse(json: String): UpdateInfo {
            val code = CODE.find(json)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IOException("Fichier de version illisible")
            val name = NAME.find(json)?.groupValues?.get(1) ?: "build $code"
            return UpdateInfo(code, name)
        }

        /** Message d'erreur lisible pour l'écran de réglages. */
        fun describe(error: Throwable): String = when (error) {
            is UnknownHostException -> "pas d'accès à Internet (GitHub injoignable)"
            is java.net.SocketTimeoutException -> "GitHub ne répond pas, réessaie plus tard"
            else -> error.message ?: error.javaClass.simpleName
        }
    }
}
