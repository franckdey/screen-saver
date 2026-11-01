package com.maxime.photodream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var domain: EditText
    private lateinit var recursive: CheckBox
    private lateinit var showFolder: CheckBox
    private lateinit var showClock: CheckBox
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var updateButton: Button
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

        address = field(root, "Dossier réseau (ex. smb://192.168.1.20/photo/Vacances)", prefs.address)
        user = field(root, "Utilisateur (laisser vide pour un accès invité)", prefs.user)
        password = field(root, "Mot de passe", prefs.password, secret = true)
        domain = field(root, "Domaine / groupe de travail (généralement vide)", prefs.domain)

        recursive = check(root, "Inclure les sous-dossiers", prefs.recursive)
        showFolder = check(root, "Afficher le lieu et la date sur la photo (sinon son dossier et son nom)", prefs.showFolder)
        showClock = check(root, "Afficher l'heure", prefs.showClock)

        root.addView(cycler("Durée en plein écran", listOf(5, 8, 12, 20, 30), prefs.holdSeconds, { "$it s" }) {
            prefs.holdSeconds = it
        })
        root.addView(cycler("Durée par photo dans le diaporama simple", listOf(3, 5, 8, 10, 15, 30), prefs.simpleSeconds, { "$it s" }) {
            prefs.simpleSeconds = it
        })
        root.addView(cycler("Économiseur d'écran", listOf(false, true), prefs.simpleScreensaver, {
            if (it) "diaporama simple" else "mosaïque animée"
        }) { prefs.simpleScreensaver = it })
        root.addView(cycler("Densité des vignettes (plus = plus petites)", listOf(3, 4, 5, 6), prefs.rows, { "$it" }) {
            prefs.rows = it
        })
        root.addView(cycler("Flou de l'arrière-plan", listOf(0, 1, 2), prefs.blurLevel, {
            listOf("aucun", "léger", "fort")[it]
        }) { prefs.blurLevel = it })
        root.addView(cycler("Taille du cache sur la TV", listOf(200, 500, 1000, 2000), prefs.cacheMb, { "$it Mo" }) {
            prefs.cacheMb = it
        })

        val buttons = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(button("Tester la connexion") { testConnection() })
        buttons.addView(button("Lancer le diaporama") {
            save()
            startActivity(Intent(this, SlideshowActivity::class.java))
        })
        buttons.addView(button("Lancer le diaporama simple") {
            save()
            startActivity(Intent(this, SlideshowActivity::class.java).putExtra(SlideshowActivity.EXTRA_SIMPLE, true))
        })
        buttons.addView(button("Choisir comme économiseur") { openDreamSettings() })
        buttons.addView(button("Tester une photo") { testOnePhoto() })
        buttons.addView(button("Vider le cache") {
            PhotoRepository(this, prefs).clearCache()
            status.text = "Cache vidé."
        })
        buttons.addView(button("Rechercher une mise à jour") { checkUpdate(manual = true) })
        root.addView(buttons)

        status = text("", 16f).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(status)
        preview = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(preview, LinearLayout.LayoutParams(dp(480), LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 24, 32))
            addView(root)
        })
        address.requestFocus()
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
        prefs.user = user.text.toString()
        prefs.password = password.text.toString()
        prefs.domain = domain.text.toString()
        prefs.recursive = recursive.isChecked
        prefs.showFolder = showFolder.isChecked
        prefs.showClock = showClock.isChecked
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
}
