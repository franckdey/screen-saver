package com.maxime.photodream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Écran de réglages, pensé pour la télécommande (tout se fait aux flèches + OK). */
class SettingsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private lateinit var address: EditText
    /** Dossiers réseau supplémentaires, un champ chacun. */
    private val extraFields = ArrayList<Pair<EditText, CheckBox>>()
    private lateinit var addressEnabled: CheckBox
    private lateinit var extraBox: LinearLayout
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var domain: EditText
    private lateinit var recursive: CheckBox
    private lateinit var showFolder: CheckBox
    private lateinit var showClock: CheckBox
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var updateButton: Button
    private lateinit var launchButton: Button
    private val updater = Updater(BuildConfig.UPDATE_BASE_URL, BuildConfig.VERSION_CODE)
    private var available: UpdateInfo? = null
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Sur un téléphone (écran étroit), marges réduites et boutons les uns sous les autres.
        val narrow = resources.configuration.screenWidthDp < 720
        val margin = if (narrow) dp(16) else dp(56)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, dp(32), margin, dp(32))
        }
        root.addView(text("PhotoDream", 28f, bold = true))
        root.addView(text("Économiseur d'écran avec les photos de ton NAS · version ${BuildConfig.VERSION_NAME}", 16f).apply { alpha = 0.7f })

        // Bouton mis en avant, visible seulement quand une nouvelle version existe.
        updateButton = button("") { installUpdate() }.apply {
            visibility = View.GONE
            setBackgroundColor(Color.rgb(46, 125, 50))
            setTextColor(Color.WHITE)
        }
        root.addView(updateButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        // Partie haute : lancer le diaporama et régler son allure.
        val top = section(root, "Diaporama")
        val launch = row(narrow)
        launch.addView(button("Lancer le diaporama") {
            save()
            startActivity(Intent(this, SlideshowActivity::class.java))
        }.also { launchButton = it })
        launch.addView(button("Lancer le diaporama simple") {
            save()
            startActivity(Intent(this, SlideshowActivity::class.java).putExtra(SlideshowActivity.EXTRA_SIMPLE, true))
        })
        launch.addView(button("Choisir comme économiseur") { openDreamSettings() })
        top.addView(launch)

        top.addView(cycler("Économiseur d'écran", listOf(false, true), prefs.simpleScreensaver, {
            if (it) "diaporama simple" else "mosaïque animée"
        }) { prefs.simpleScreensaver = it })
        top.addView(cycler("Durée en plein écran", listOf(5, 8, 12, 20, 30), prefs.holdSeconds, { "$it s" }) {
            prefs.holdSeconds = it
        })
        top.addView(cycler("Durée par photo dans le diaporama simple", listOf(3, 5, 8, 10, 15, 30), prefs.simpleSeconds, { "$it s" }) {
            prefs.simpleSeconds = it
        })
        top.addView(cycler("Densité des vignettes (plus = plus petites)", listOf(3, 4, 5, 6), prefs.rows, { "$it" }) {
            prefs.rows = it
        })
        top.addView(cycler("Flou de l'arrière-plan", listOf(0, 1, 2), prefs.blurLevel, {
            listOf("aucun", "léger", "fort")[it]
        }) { prefs.blurLevel = it })
        showFolder = check(top, "Afficher le lieu et la date sur la photo (sinon son dossier et son nom)", prefs.showFolder)
        showClock = check(top, "Afficher l'heure", prefs.showClock)

        // Partie basse : le dossier réseau, les identifiants et les tests.
        val bottom = section(root, "Dossier réseau et tests")
        address = field(bottom, "Dossier réseau (ex. smb://192.168.1.254/Freebox/etc...)", prefs.address)
        addressEnabled = check(bottom, "Utiliser ce dossier", prefs.addressEnabled)
        // autres dossiers réseau, lus en plus du premier (mêmes identifiants)
        extraBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(extraBox)
        prefs.extraFolders.forEach { addExtraField(it.address, it.enabled) }
        bottom.addView(button("+ Ajouter un dossier réseau") {
            // prérempli avec le dossier Photos de la Freebox : il ne reste qu'à compléter
            addExtraField(NEW_FOLDER_PREFILL, true).apply { setSelection(text.length) }.requestFocus()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        user = field(bottom, "Utilisateur (laisser vide pour un accès invité)", prefs.user)
        password = field(bottom, "Mot de passe", prefs.password, secret = true)
        domain = field(bottom, "Domaine / groupe de travail (généralement vide)", prefs.domain)
        recursive = check(bottom, "Inclure les sous-dossiers", prefs.recursive)
        bottom.addView(cycler("Taille du cache sur la TV", listOf(200, 500, 1000, 2000), prefs.cacheMb, { "$it Mo" }) {
            prefs.cacheMb = it
        })

        val tests = row(narrow)
        tests.addView(button("Tester la connexion") { testConnection() })
        tests.addView(button("Tester une photo") { testOnePhoto() })
        tests.addView(button("Vider le cache") {
            PhotoRepository(this, prefs).clearCache()
            status.text = "Cache vidé."
        })
        tests.addView(button("Rechercher une mise à jour") { checkUpdate(manual = true) })
        bottom.addView(tests)

        // résultat des tests, juste sous leurs boutons
        status = text("", 16f).apply { setPadding(0, dp(16), 0, 0) }
        bottom.addView(status)
        preview = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        bottom.addView(preview, LinearLayout.LayoutParams(dp(480), LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 24, 32))
            addView(root)
        })
        highlightFocus(root)
        // dossier pas encore réglé : on commence par l'adresse, sinon par « Lancer le diaporama »
        if (prefs.address.isBlank()) address.requestFocus() else launchButton.requestFocus()
        checkUpdate(manual = false)
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun save() {
        prefs.address = address.text.toString()
        prefs.addressEnabled = addressEnabled.isChecked
        prefs.extraFolders = extraFields.map { (edit, box) -> NetFolder(edit.text.toString(), box.isChecked) }
        prefs.user = user.text.toString()
        prefs.password = password.text.toString()
        prefs.domain = domain.text.toString()
        prefs.recursive = recursive.isChecked
        prefs.showFolder = showFolder.isChecked
        prefs.showClock = showClock.isChecked
    }

    /** Un champ pour un dossier réseau de plus, avec sa coche et son bouton pour le retirer. */
    private fun addExtraField(value: String, enabled: Boolean): EditText {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val edit = field(block, "Autre dossier réseau", value)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val box = check(line, "Utiliser ce dossier", enabled)
        val entry = edit to box
        val remove = button("Retirer ce dossier") {
            val i = extraBox.indexOfChild(block)
            extraFields.remove(entry)
            extraBox.removeView(block)
            save()
            // le focus passe au dossier suivant (ou précédent), pas en haut de l'écran
            val next = extraBox.getChildAt(i) ?: extraBox.getChildAt(i - 1)
            (next as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is EditText } }
                ?.requestFocus() ?: address.requestFocus()
        }
        line.addView(remove, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(24)
        })
        block.addView(line)
        extraBox.addView(block)
        extraFields += entry
        highlightFocus(block)
        return edit
    }

    private fun testConnection() {
        save()
        status.text = "Connexion en cours…"
        scope.launch {
            val repo = PhotoRepository(this@SettingsActivity, prefs)
            val count = repo.load()
            status.text = when {
                repo.offline -> "Échec : ${repo.lastError}\n($count photos déjà en cache restent utilisables)"
                repo.lastError != null -> "Échec : ${repo.lastError}"
                else -> "OK : $count photos trouvées."
            }
            // plusieurs dossiers : le détail de chacun, pour repérer celui qui ne répond pas
            val addresses = prefs.allAddresses
            if (addresses.size > 1) {
                val details = withContext(Dispatchers.IO) {
                    addresses.map { address ->
                        val src = PhotoRepository.smbSourceFor(prefs, address)
                            ?: return@map "• $address : adresse non reconnue"
                        try {
                            "• $address : ${src.listPhotos(prefs.recursive).size} photos"
                        } catch (e: Exception) {
                            "• $address : échec (${SmbPhotoSource.describe(e)})"
                        } finally {
                            runCatching { src.close() }
                        }
                    }
                }
                status.text = status.text.toString() + "\n" + details.joinToString("\n")
            }
            Thread { repo.close() }.start()
        }
    }

    /** Charge une seule photo en affichant chaque étape, pour trouver ce qui bloque. */
    private fun testOnePhoto() {
        save()
        preview.visibility = View.GONE
        status.text = "Test d'une photo…"
        scope.launch {
            val result = PhotoDiagnostic(prefs, File(cacheDir, "diagnostic")).run { report ->
                runOnUiThread { status.text = report }
            }
            status.text = result.report
            result.bitmap?.let {
                preview.setImageBitmap(it)
                preview.visibility = View.VISIBLE
            }
        }
    }

    // --- Mise à jour intégrée ---------------------------------------------------------------

    private fun checkUpdate(manual: Boolean) {
        if (updating) return
        if (manual) status.text = "Recherche d'une mise à jour…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { updater.check() } }
            available = result.getOrNull()
            val info = available
            if (info != null) {
                updateButton.text = "Installer la mise à jour (version ${info.versionName})"
                updateButton.visibility = View.VISIBLE
                if (manual) {
                    status.text = "Nouvelle version ${info.versionName} disponible."
                    updateButton.requestFocus()
                }
            } else if (manual) {
                status.text = result.exceptionOrNull()?.let { "Impossible de vérifier : ${Updater.describe(it)}" }
                    ?: "PhotoDream est à jour (version ${BuildConfig.VERSION_NAME})."
            }
        }
    }

    private fun installUpdate() {
        val info = available ?: return
        if (updating) return
        if (!ApkInstaller.canInstall(this)) {
            askInstallPermission()
            return
        }
        updating = true
        updateButton.isEnabled = false
        status.text = "Téléchargement de la version ${info.versionName}…"
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    updater.download(File(cacheDir, "updates/${Updater.APK_NAME}")) { percent ->
                        if (percent >= 0) runOnUiThread { status.text = "Téléchargement de la version ${info.versionName} : $percent %" }
                    }
                }
                status.text = "Installation… confirme sur l'écran qui s'affiche."
                withContext(Dispatchers.IO) { ApkInstaller.install(this@SettingsActivity, apk) }
            } catch (e: Exception) {
                status.text = "Mise à jour impossible : ${Updater.describe(e)}"
            } finally {
                updating = false
                updateButton.isEnabled = true
            }
        }
    }

    private fun askInstallPermission() {
        status.text = "Autorise PhotoDream à installer des applis (« Sources inconnues »), " +
            "puis appuie de nouveau sur « Installer la mise à jour »."
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            status.text = "Autorise PhotoDream dans Paramètres › Applications › Sécurité et restrictions › " +
                "Sources inconnues, puis appuie de nouveau sur « Installer la mise à jour »."
        }
    }

    private fun openDreamSettings() {
        save()
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            status.text = "Cette TV (Google TV ?) cache le choix de l'économiseur. Depuis un ordinateur :\n" +
                "adb shell settings put secure screensaver_components $packageName/.PhotoDreamService"
        }
    }

    // --- Petits utilitaires d'interface ---------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(value: String, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /**
     * Rend bien visible l'élément sélectionné à la télécommande : épais cadre jaune et fond
     * légèrement teinté, posés par-dessus (le style d'origine du bouton est conservé).
     */
    private fun highlightFocus(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) highlightFocus(view.getChildAt(i))
            return
        }
        if (!view.isFocusable) return
        val frame = GradientDrawable().apply {
            setColor(Color.argb(60, 255, 193, 7))
            setStroke(dp(4), Color.rgb(255, 193, 7))
            cornerRadius = dp(6).toFloat()
        }
        view.setOnFocusChangeListener { v, hasFocus ->
            v.foreground = if (hasFocus) frame else null
        }
        if (view.isFocused) view.foreground = frame
    }

    /** Un bloc encadré avec son titre, pour séparer l'écran en parties. */
    private fun section(root: LinearLayout, title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
            setBackgroundColor(Color.rgb(30, 38, 50))
        }
        box.addView(text(title, 20f, bold = true))
        root.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        return box
    }

    /** Boutons côte à côte (les uns sous les autres sur un téléphone). */
    private fun row(narrow: Boolean) = LinearLayout(this).apply {
        orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun field(root: LinearLayout, label: String, value: String, secret: Boolean = false): EditText {
        root.addView(text(label, 14f).apply {
            alpha = 0.75f
            setPadding(0, dp(14), 0, 0)
        })
        val edit = EditText(this).apply {
            setText(value)
            setSingleLine()
            textSize = 18f
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        }
        edit.maxWidth = dp(720)
        root.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return edit
    }

    private fun check(root: LinearLayout, label: String, value: Boolean): CheckBox {
        val box = CheckBox(this).apply {
            text = label
            textSize = 16f
            isChecked = value
            setTextColor(Color.WHITE)
        }
        root.addView(box)
        return box
    }

    private fun <T> cycler(label: String, values: List<T>, initial: T, format: (T) -> String, onChange: (T) -> Unit): View {
        var index = values.indexOf(initial).coerceAtLeast(0)
        return Button(this).apply {
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = "$label : ${format(values[index])}"
            setOnClickListener {
                index = (index + 1) % values.size
                text = "$label : ${format(values[index])}"
                onChange(values[index])
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    companion object {
        /** Adresse proposée pour chaque nouveau dossier réseau. */
        const val NEW_FOLDER_PREFILL = "smb://192.168.1.254/Freebox/Photos/"
    }
}
