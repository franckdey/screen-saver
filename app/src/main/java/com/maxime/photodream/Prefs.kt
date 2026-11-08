package com.maxime.photodream

import android.content.Context

/** Un dossier réseau supplémentaire et sa coche « Utiliser ce dossier ». */
data class NetFolder(val address: String, val enabled: Boolean = true) {
    companion object {
        /** Une ligne par dossier ; « # » devant l'adresse d'un dossier décoché. */
        fun parse(text: String): List<NetFolder> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map {
            if (it.startsWith("#")) NetFolder(it.substring(1).trim(), false) else NetFolder(it)
        }.filter { it.address.isNotEmpty() }

        fun serialize(folders: List<NetFolder>): String = folders
            .map { it.copy(address = it.address.trim()) }
            .filter { it.address.isNotEmpty() }
            .joinToString("\n") { if (it.enabled) it.address else "#" + it.address }

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
    }
}

/** Réglages de l'application, stockés localement sur la TV. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("photodream", Context.MODE_PRIVATE)

    /** Ex. smb://192.168.1.20/photo/Vacances ou \\NAS\photo\Vacances */
    var address: String
        get() = sp.getString("address", "") ?: ""
        set(v) = sp.edit().putString("address", v.trim()).apply()

    /** Coche « Utiliser ce dossier » du dossier réseau principal. */
    var addressEnabled: Boolean
        get() = sp.getBoolean("addressEnabled", true)
        set(v) = sp.edit().putBoolean("addressEnabled", v).apply()

    /** Dossiers réseau supplémentaires, lus en plus de [address] avec les mêmes identifiants. */
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

    /** Taille maximale du cache local de photos, en Mo. */
    var cacheMb: Int
        get() = sp.getInt("cacheMb", 500)
        set(v) = sp.edit().putInt("cacheMb", v).apply()
}
