package com.maxime.photodream

import android.content.Context
import java.io.File

/**
 * Un dossier réseau supplémentaire et sa coche « Utiliser ce dossier ». [user] et [password] servent
 * pour un autre NAS qui a ses propres identifiants ; vides, ce sont ceux du dossier principal.
 */
data class NetFolder(
    val address: String,
    val enabled: Boolean = true,
    val user: String = "",
    val password: String = "",
) {
    /** Ce dossier a-t-il ses propres identifiants ? */
    val ownLogin: Boolean get() = user.isNotBlank()

    companion object {
        /**
         * Une ligne par dossier ; « # » devant l'adresse d'un dossier décoché. Les identifiants
         * propres suivent l'adresse, séparés par une tabulation et encodés.
         */
        fun parse(text: String): List<NetFolder> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val parts = line.split('\t')
            val raw = parts[0].trim()
            val enabled = !raw.startsWith("#")
            NetFolder(
                address = (if (enabled) raw else raw.substring(1)).trim(),
                enabled = enabled,
                user = parts.getOrNull(1)?.let(::decode) ?: "",
                password = parts.getOrNull(2)?.let(::decode) ?: "",
            )
        }.filter { it.address.isNotEmpty() }

        fun serialize(folders: List<NetFolder>): String = folders
            .map { it.copy(address = it.address.trim(), user = it.user.trim()) }
            .filter { it.address.isNotEmpty() }
            .joinToString("\n") {
                val head = if (it.enabled) it.address else "#" + it.address
                if (it.ownLogin) head + "\t" + encode(it.user) + "\t" + encode(it.password) else head
            }

        /** Les dossiers cochés et remplis, sans doublon, le principal en premier. */
        fun enabled(main: String, mainOn: Boolean, extras: List<NetFolder>): List<Pair<String, Boolean>> {
            val out = ArrayList<Pair<String, Boolean>>()
            if (mainOn && main.isNotBlank()) out += main.trim() to true
            for (f in extras) {
                val a = f.address.trim()
                if (f.enabled && a.isNotEmpty() && out.none { it.first == a }) out += a to false
            }
            return out
        }

        /**
         * Utilisateur, mot de passe et domaine pour lire [address] : ceux du dossier supplémentaire
         * s'il a les siens, sinon ceux du dossier principal.
         */
        fun login(address: String, main: String, mainLogin: Login, extras: List<NetFolder>): Login {
            val a = address.trim()
            if (a == main.trim()) return mainLogin
            val own = extras.firstOrNull { it.address.trim() == a && it.ownLogin } ?: return mainLogin
            return Login(own.user.trim(), own.password, "")
        }

        private fun encode(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        private fun decode(v: String) = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
    }
}

/** Identifiants d'un NAS. */
data class Login(val user: String, val password: String, val domain: String)

/** Réglages de l'application, stockés localement sur la TV. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("photodream", Context.MODE_PRIVATE)

    /** Dossier privé de l'appli (crédits des photos du web). */
    val filesDir: File = context.applicationContext.filesDir

    /** Coche « Photos de paysages du web » (Wikimedia Commons). */
    var webPhotos: Boolean
        get() = sp.getBoolean("webPhotos", false)
        set(v) = sp.edit().putBoolean("webPhotos", v).apply()

    /** Ex. smb://192.168.1.20/photo/Vacances ou \\NAS\photo\Vacances */
    var address: String
        get() = sp.getString("address", "") ?: ""
        set(v) = sp.edit().putString("address", v.trim()).apply()

    /** Coche « Utiliser ce dossier » du dossier réseau principal. */
    var addressEnabled: Boolean
        get() = sp.getBoolean("addressEnabled", true)
        set(v) = sp.edit().putBoolean("addressEnabled", v).apply()

    /** Dossiers réseau supplémentaires, lus en plus de [address] (avec leurs propres identifiants s'ils en ont). */
    var extraFolders: List<NetFolder>
        get() = NetFolder.parse(sp.getString("extraAddresses", "") ?: "")
        set(v) = sp.edit().putString("extraAddresses", NetFolder.serialize(v)).apply()

    /** Les dossiers cochés, le principal en premier, avec pour chacun « est-ce le principal ». */
    val enabledFolders: List<Pair<String, Boolean>>
        get() = NetFolder.enabled(address, addressEnabled, extraFolders)

    /** Adresses des dossiers cochés. */
    val allAddresses: List<String>
        get() = enabledFolders.map { it.first }

    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v.trim()).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    var domain: String
        get() = sp.getString("domain", "") ?: ""
        set(v) = sp.edit().putString("domain", v.trim()).apply()

    /** Les identifiants à utiliser pour ce dossier réseau. */
    fun loginFor(address: String): Login = NetFolder.login(address, this.address, Login(user, password, domain), extraFolders)

    /** Mode SMB « compatible » (SMB 2, sans chiffrement), choisi automatiquement si le NAS bloque les lectures. */
    var smbCompat: Boolean
        get() = sp.getBoolean("smbCompat", false)
        set(v) = sp.edit().putBoolean("smbCompat", v).apply()

    var recursive: Boolean
        get() = sp.getBoolean("recursive", true)
        set(v) = sp.edit().putBoolean("recursive", v).apply()

    /** Temps d'affichage en plein écran, en secondes. */
    var holdSeconds: Int
        get() = sp.getInt("hold", 8)
        set(v) = sp.edit().putInt("hold", v).apply()

    /** Temps par photo dans le diaporama simple, en secondes. */
    var simpleSeconds: Int
        get() = sp.getInt("simpleHold", 5)
        set(v) = sp.edit().putInt("simpleHold", v).apply()

    /** Dernière photo montrée par le diaporama simple, pour reprendre au même endroit. */
    var simpleLast: String
        get() = sp.getString("simpleLast", "") ?: ""
        set(v) = sp.edit().putString("simpleLast", v).apply()

    /** Économiseur d'écran : false = mosaïque animée, true = diaporama simple. */
    var simpleScreensaver: Boolean
        get() = sp.getBoolean("simpleScreensaver", false)
        set(v) = sp.edit().putBoolean("simpleScreensaver", v).apply()

    /** Mosaïque : photos en grand dans un ordre aléatoire (sinon dossier par dossier). */
    var mosaicRandom: Boolean
        get() = sp.getBoolean("mosaicRandom", true)
        set(v) = sp.edit().putBoolean("mosaicRandom", v).apply()

    /** Diaporama simple : ordre aléatoire (sinon dossier par dossier). */
    var simpleRandom: Boolean
        get() = sp.getBoolean("simpleRandom", false)
        set(v) = sp.edit().putBoolean("simpleRandom", v).apply()

    /** Effet de passage d'une photo à l'autre dans le diaporama simple (voir [Transition]). */
    var simpleTransition: String
        get() = sp.getString("simpleTransition", Transition.FADE.key) ?: Transition.FADE.key
        set(v) = sp.edit().putString("simpleTransition", v).apply()

    /** Densité de la mosaïque : plus la valeur est grande, plus les vignettes sont petites. */
    var rows: Int
        get() = sp.getInt("rows", 4)
        set(v) = sp.edit().putInt("rows", v).apply()

    /** 0 = net, 1 = léger, 2 = fort. */
    var blurLevel: Int
        get() = sp.getInt("blur", 1)
        set(v) = sp.edit().putInt("blur", v).apply()

    var showFolder: Boolean
        get() = sp.getBoolean("showFolder", true)
        set(v) = sp.edit().putBoolean("showFolder", v).apply()

    var showClock: Boolean
        get() = sp.getBoolean("showClock", false)
        set(v) = sp.edit().putBoolean("showClock", v).apply()

    /** Vidéos du NAS : [VIDEOS_OFF] (pas de vidéo), [VIDEOS_MUTED] (lues sans le son) ou [VIDEOS_SOUND]. */
    var videoMode: Int
        get() = sp.getInt("videoMode", VIDEOS_OFF)
        set(v) = sp.edit().putInt("videoMode", v).apply()

    val videos: Boolean get() = videoMode != VIDEOS_OFF

    /** Durée maximale de lecture d'une vidéo, en secondes ; 0 = jusqu'au bout. */
    var videoMaxSeconds: Int
        get() = sp.getInt("videoMax", 60)
        set(v) = sp.edit().putInt("videoMax", v).apply()

    /** Taille maximale du cache local de photos, en Mo. */
    var cacheMb: Int
        get() = sp.getInt("cacheMb", 500)
        set(v) = sp.edit().putInt("cacheMb", v).apply()

    companion object {
        const val VIDEOS_OFF = 0
        const val VIDEOS_MUTED = 1
        const val VIDEOS_SOUND = 2
    }
}
