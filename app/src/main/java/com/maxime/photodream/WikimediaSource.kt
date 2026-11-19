package com.maxime.photodream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Photos de paysages sous licence libre, prises sur Wikimedia Commons : les « images de qualité »
 * et « images remarquables » de paysages. Le chemin d'une photo est son titre (« File:… »).
 * Chaque photo est montrée avec son auteur et sa licence, comme ces licences le demandent ;
 * ces crédits sont gardés dans [creditsFile] pour les photos déjà en cache.
 */
class WikimediaSource(
    private val creditsFile: File,
    private val http: (String) -> ByteArray = ::download,
) : PhotoSource {

    /** Titre lisible et crédit (« auteur, licence ») de chaque photo déjà téléchargée. */
    private val credits: MutableMap<String, Credit> by lazy { readCredits() }

    data class Credit(val title: String, val credit: String)

    override suspend fun listPhotos(recursive: Boolean, limit: Int): List<RemotePhoto> = withContext(Dispatchers.IO) {
        val titles = LinkedHashSet<String>()
        for (category in CATEGORIES) {
            var cont: String? = null
            do {
                val url = API + "?action=query&list=categorymembers&cmtype=file&cmlimit=500&format=json&formatversion=2" +
                    "&cmtitle=" + enc(category) + (cont?.let { "&cmcontinue=" + enc(it) } ?: "")
                val page = Wikimedia.parseMembers(String(http(url)))
                titles += page.titles.filter { Wikimedia.isPhoto(it) }
                cont = page.next
            } while (cont != null && titles.size < MAX_PHOTOS)
        }
        if (titles.isEmpty()) throw IOException("Aucune photo reçue de Wikimedia Commons")
        titles.take(minOf(limit, MAX_PHOTOS)).map { RemotePhoto(it, 0L, 0L) }
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = API + "?action=query&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=1920" +
            "&iiextmetadatafilter=Artist|LicenseShortName|ObjectName&format=json&formatversion=2&titles=" + enc(path)
        val info = Wikimedia.parseInfo(String(http(url))) ?: throw IOException("Photo introuvable sur Wikimedia : $path")
        val bytes = http(info.url)
        remember(path, Credit(info.title, info.credit))
        bytes
    }

    /** Pour une photo du web, pas de dossier : son titre tient lieu de nom. */
    override fun folderLabel(photo: RemotePhoto): String = ""

    override fun displayName(photo: RemotePhoto): String =
        credits[photo.path]?.title ?: Wikimedia.titleFromFile(photo.path)

    override fun credit(photo: RemotePhoto): String? = credits[photo.path]?.credit

    override fun close() {}

    @Synchronized
    private fun remember(path: String, credit: Credit) {
        if (credits[path] == credit) return
        credits[path] = credit
        try {
            creditsFile.appendText("$path\t${credit.title}\t${credit.credit}\n")
        } catch (e: Exception) {
            // pas grave : le crédit reviendra au prochain téléchargement
        }
    }

    private fun readCredits(): MutableMap<String, Credit> {
        val map = HashMap<String, Credit>()
        try {
            if (creditsFile.exists()) creditsFile.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 3) map[parts[0]] = Credit(parts[1], parts[2])
            }
        } catch (e: Exception) {
            // fichier abîmé : on repart de zéro
        }
        return map
    }

    companion object {
        private const val API = "https://commons.wikimedia.org/w/api.php"
        private const val MAX_PHOTOS = 5_000
        private val CATEGORIES = listOf(
            "Category:Featured pictures of landscapes",
            "Category:Quality images of landscapes",
        )

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Wikimedia demande un nom d'appli et une adresse de contact dans chaque requête. */
        fun download(url: String): ByteArray {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "PhotoDream/${BuildConfig.VERSION_NAME} (https://github.com/franckdey/screen-saver)")
            return try {
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    // sans la cause : sinon le message parlerait du NAS
                    throw IOException("Wikimedia Commons injoignable (la TV a-t-elle accès à Internet ?)")
                }
                if (code != 200) throw IOException("Wikimedia Commons : erreur $code")
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }
}

/** Lecture des réponses de l'API de Wikimedia Commons (org.json vient d'Android). */
object Wikimedia {

    class Members(val titles: List<String>, val next: String?)

    class Info(val url: String, val title: String, val credit: String)

    fun parseMembers(json: String): Members {
        val root = JSONObject(json)
        val list = root.optJSONObject("query")?.optJSONArray("categorymembers")
        val titles = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it)?.optString("title") }
            .filter { it.isNotEmpty() }
        val next = root.optJSONObject("continue")?.optString("cmcontinue")?.takeIf { it.isNotEmpty() }
        return Members(titles, next)
    }

    fun parseInfo(json: String): Info? {
        val page = JSONObject(json).optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0) ?: return null
        val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
        val url = info.optString("thumburl").ifEmpty { info.optString("url") }
        if (url.isEmpty()) return null
        val meta = info.optJSONObject("extmetadata")
        fun field(name: String) = meta?.optJSONObject(name)?.optString("value").orEmpty().let(::plainText)
        val title = field("ObjectName").ifEmpty { titleFromFile(page.optString("title")) }
        val author = field("Artist")
        val license = field("LicenseShortName")
        val credit = listOf(author.takeIf { it.isNotEmpty() }?.let { "© $it" }, license.takeIf { it.isNotEmpty() })
            .filterNotNull().joinToString(", ").ifEmpty { "Wikimedia Commons" }
        return Info(url, title, credit)
    }

    /** « File:Lac_d'Annecy.jpg » → « Lac d'Annecy ». */
    fun titleFromFile(title: String) = title.substringAfter(':').substringBeforeLast('.').replace('_', ' ').trim()

    /** Seulement des photos (pas de vidéos, de PDF ni de dessins). */
    fun isPhoto(title: String) = title.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg")

    /** Les champs de Wikimedia sont en HTML : on ne garde que le texte. */
    fun plainText(html: String): String = html
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
