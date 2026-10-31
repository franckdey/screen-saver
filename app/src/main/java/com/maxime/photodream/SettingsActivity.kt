package com.maxime.photodream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
        root.addView(text("Économiseur d'écran avec les photos de ton NAS", 16f).apply { alpha = 0.7f })

        address = field(root, "Dossier réseau (ex. smb://192.168.1.20/photo/Vacances)", prefs.address)
        user = field(root, "Utilisateur (laisser vide pour un accès invité)", prefs.user)
        password = field(root, "Mot de passe", prefs.password, secret = true)
        domain = field(root, "Domaine / groupe de travail (généralement vide)", prefs.domain)

        recursive = check(root, "Inclure les sous-dossiers", prefs.recursive)
        showFolder = check(root, "Afficher le nom du dossier sous la photo", prefs.showFolder)
        showClock = check(root, "Afficher l'heure", prefs.showClock)

        root.addView(cycler("Durée en plein écran", listOf(5, 8, 12, 20, 30), prefs.holdSeconds, { "$it s" }) {
            prefs.holdSeconds = it
        })
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
        buttons.addView(button("Choisir comme économiseur") { openDreamSettings() })
        buttons.addView(button("Vider le cache") {
            PhotoRepository(this, prefs).clearCache()
            status.text = "Cache vidé."
        })
        root.addView(buttons)

        status = text("", 16f).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(status)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 24, 32))
            addView(root)
        })
        address.requestFocus()
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
