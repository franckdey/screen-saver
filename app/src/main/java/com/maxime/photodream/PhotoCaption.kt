package com.maxime.photodream

import android.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Infos utiles tirées de l'EXIF d'une photo : position GPS et date de prise de vue. */
data class PhotoMeta(val lat: Double?, val lon: Double?, val taken: Long?) {

    fun serialize() = "${lat ?: ""};${lon ?: ""};${taken ?: ""}"

    companion object {
        val EMPTY = PhotoMeta(null, null, null)

        fun parse(text: String): PhotoMeta? {
            val parts = text.trim().split(';')
            if (parts.size != 3) return null
            return PhotoMeta(parts[0].toDoubleOrNull(), parts[1].toDoubleOrNull(), parts[2].toLongOrNull())
        }

        /** Lit l'EXIF d'une photo originale (le début du fichier suffit). */
        fun fromExif(bytes: ByteArray): PhotoMeta = try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val latLong = FloatArray(2)
            val hasGps = exif.getLatLong(latLong) // getLatLong(): DoubleArray n'existe qu'à partir d'Android 10
            val date = (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME))?.let(::parseExifDate)
            // 0,0 = GPS vide écrit par certains appareils
            val valid = hasGps && !(latLong[0] == 0f && latLong[1] == 0f)
            PhotoMeta(if (valid) latLong[0].toDouble() else null, if (valid) latLong[1].toDouble() else null, date)
        } catch (e: Exception) {
            EMPTY
        }

        /** « 2026:08:18 13:30:38 » → millisecondes (heure locale de la photo, peu importe ici). */
        fun parseExifDate(text: String): Long? = try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(text.trim())?.time
        } catch (e: Exception) {
            null
        }
    }
}

object Captions {
    /** « Key West, Floride · août 2026 » ; sans lieu, le nom de la photo prend sa place. */
    fun format(place: String?, taken: Long?, fallback: String): String {
        val where = place?.takeIf { it.isNotBlank() } ?: fallback.takeIf { it.isNotBlank() }
        val date = taken?.let {
            SimpleDateFormat("MMMM yyyy", Locale.FRENCH).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it))
        }
        return listOfNotNull(where, date).joinToString(" · ")
    }

    /**
     * Nom court d'un lieu à partir de la réponse d'OpenStreetMap (Nominatim) :
     * la ville, puis le département en France, l'État aux États-Unis…, sinon le pays.
     */
    fun placeFromNominatim(json: String): String? = try {
        val address = JSONObject(json).optJSONObject("address")
        if (address == null) {
            null
        } else {
            fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k -> address.optString(k).takeIf { it.isNotBlank() } }
            val locality = pick("city", "town", "village", "municipality", "hamlet", "suburb", "county")
            val region = when (address.optString("country_code")) {
                "fr" -> pick("county", "state")
                "us", "ca", "au" -> pick("state")
                else -> pick("country")
            }
            listOfNotNull(locality, region?.takeIf { it != locality }).joinToString(", ").ifBlank { null }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Noms de lieux trouvés à partir des coordonnées GPS, via OpenStreetMap, gardés dans un
 * fichier pour ne demander chaque endroit qu'une fois (à ~100 m près).
 */
class PlaceNames(private val file: File, private val lookup: (Double, Double) -> String? = ::nominatim) {
    private var cache: MutableMap<String, String>? = null

    @Synchronized
    fun name(lat: Double, lon: Double): String? {
        val key = "%.3f,%.3f".format(Locale.US, lat, lon)
        val map = cache ?: load().also { cache = it }
        map[key]?.let { return it.ifEmpty { null } }
        val found = try { lookup(lat, lon) } catch (e: Exception) { return null } // hors ligne : on réessaiera
        map[key] = found.orEmpty()
        try {
            file.appendText("$key\t${found.orEmpty()}\n")
        } catch (e: Exception) {
            // pas grave
        }
        return found
    }

    private fun load(): MutableMap<String, String> = try {
        if (!file.exists()) HashMap() else file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap(HashMap())
    } catch (e: Exception) {
        HashMap()
    }

    companion object {
        private var lastCall = 0L

        /** Nominatim demande au plus une requête par seconde et un User-Agent identifiable. */
        fun nominatim(lat: Double, lon: Double): String? {
            synchronized(this) {
                val wait = lastCall + 1100 - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastCall = System.currentTimeMillis()
            }
            val url = URL(
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&accept-language=fr" +
                    "&lat=%.5f&lon=%.5f".format(Locale.US, lat, lon),
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "PhotoDream/${BuildConfig.VERSION_NAME} (https://github.com/franckdey/screen-saver)")
            return try {
                if (conn.responseCode != 200) throw java.io.IOException("Nominatim ${conn.responseCode}")
                Captions.placeFromNominatim(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }
    }
}
