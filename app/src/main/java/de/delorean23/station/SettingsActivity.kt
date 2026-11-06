package de.delorean23.station

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Hauptmenü im Stil von Batocera (Start-Taste). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var list: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xF0101010.toInt())
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((32 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
        }
        scroll.addView(list)
        setContentView(scroll)
        build()
    }

    private fun build() {
        list.removeAllViews()
        title("HAUPTMENÜ")

        section("SPEICHERMEDIUM")
        choice("ROMs laden von", if (cfg.storage == Config.STORAGE_NETWORK) "Netzwerk (SMB)" else "Intern",
            listOf("Intern", "Netzwerk (SMB)")) {
            cfg.storage = if (it == 1) Config.STORAGE_NETWORK else Config.STORAGE_LOCAL
            build()
        }
        item("Basis-Ordner", cfg.baseDir) {
            input("Basis-Ordner (enthält roms, bios, saves, themes)", cfg.baseDir) { cfg.baseDir = it; build() }
        }

        section("NETZWERK-FREIGABE (NAS)")
        item("Server (IP oder Name)", cfg.smbHost.ifEmpty { "nicht gesetzt" }) {
            input("Server, z. B. 192.168.178.20", cfg.smbHost) { cfg.smbHost = it; build() }
        }
        item("Freigabe", cfg.smbShare.ifEmpty { "nicht gesetzt" }) {
            input("Name der Freigabe, z. B. batocera", cfg.smbShare) { cfg.smbShare = it; build() }
        }
        item("Ordner mit den Systemen", cfg.smbPath.ifEmpty { "(Wurzel der Freigabe)" }) {
            input("Pfad in der Freigabe, z. B. roms", cfg.smbPath) { cfg.smbPath = it; build() }
        }
        item("Benutzer", cfg.smbUser.ifEmpty { "Gast" }) {
            input("Benutzername (leer = Gast)", cfg.smbUser) { cfg.smbUser = it; build() }
        }
        item("Passwort", if (cfg.smbPassword.isEmpty()) "kein" else "••••••") {
            input("Passwort", cfg.smbPassword, password = true) { cfg.smbPassword = it; build() }
        }
        item("Domäne / Arbeitsgruppe", cfg.smbDomain.ifEmpty { "keine" }) {
            input("Domäne (meist leer lassen)", cfg.smbDomain) { cfg.smbDomain = it; build() }
        }
        item("Verbindung testen", "") { testConnection() }

        section("OBERFLÄCHE")
        val themes = (cfg.themesDir.listFiles()?.filter { File(it, "theme.xml").isFile }?.map { it.name } ?: emptyList()).sorted()
        item("Theme", cfg.theme + if (File(File(cfg.themesDir, cfg.theme), "theme.xml").isFile) "" else " (nicht gefunden)") {
            if (themes.isEmpty()) {
                message("Keine Themes", "Lege den Theme-Ordner (z. B. es-theme-carbon) in\n${cfg.themesDir.path}")
            } else pick("Theme wählen", themes, themes.indexOf(cfg.theme)) { cfg.theme = themes[it]; build() }
        }
        toggle("Versteckte Spiele anzeigen", cfg.showHidden) { cfg.showHidden = it }

        section("STEUERUNG")
        val modes = listOf("auto", "an", "aus")
        val modeNames = listOf("Automatisch (aus bei Controller)", "Immer an", "Aus")
        choice("Touch-Controller", modeNames[modes.indexOf(cfg.touchMode).coerceAtLeast(0)], modeNames) {
            cfg.touchMode = modes[it]; build()
        }
        slider("Touch-Deckkraft", cfg.touchOpacity) { cfg.touchOpacity = it }
        toggle("Vibration bei Touch", cfg.touchVibrate) { cfg.touchVibrate = it }
        toggle("Bestätigen/Zurück tauschen (A/B)", cfg.swapAB) { cfg.swapAB = it }

        section("EMULATOREN")
        val shaders = listOf("sharp", "default", "crt", "lcd", "smooth")
        val shaderNames = listOf("Scharf (Pixel)", "Weich (bilinear)", "Röhrenfernseher (CRT)", "LCD-Raster", "Hochskaliert")
        choice("Bildfilter", shaderNames[shaders.indexOf(cfg.shader).coerceAtLeast(0)], shaderNames) {
            cfg.shader = shaders[it]; build()
        }
        item("Emulator pro System", "") { pickSystemCore() }

        section("INFO")
        val installed = Systems.installedSystems(this)
        item("Eingebaute Systeme", "${installed.size} von ${Systems.all.size}") {
            message("Eingebaute Systeme", installed.joinToString("\n") { s ->
                "${s.name} (${s.id}) – ${s.cores.filter { Systems.coreFile(this, it).exists() }.joinToString(", ")}"
            })
        }
        item("Ordner", cfg.base.path) {
            message("Ordnerstruktur", "${cfg.romsDir.path}/<system>\n${cfg.biosDir.path}\n${cfg.savesDir.path}/<system>\n${cfg.themesDir.path}")
        }
        label("DeLorean Station 1.0  ·  Created by DeLorean23")

        section("")
        item("Zurück", "") { finish() }
        item("App beenden", "") { finishAffinity() }

        list.getChildAt(2)?.requestFocus()
    }

    // ---------------------------------------------------------------- Bausteine

    private fun title(t: String) {
        list.addView(TextView(this).apply {
            text = t
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })
    }

    private fun section(t: String) {
        list.addView(TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(0xFFBD4747.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding((8 * dp).toInt(), (18 * dp).toInt(), 0, (4 * dp).toInt())
        })
    }

    private fun label(t: String) {
        list.addView(TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(0xFF909090.toInt())
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
        })
    }

    private fun rowBackground(): StateListDrawable {
        val sel = GradientDrawable().apply { setColor(0xFF8B0000.toInt()); cornerRadius = 8 * dp }
        val norm = GradientDrawable().apply { setColor(0x20FFFFFF); cornerRadius = 8 * dp }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), sel)
            addState(intArrayOf(android.R.attr.state_pressed), sel)
            addState(intArrayOf(), norm)
        }
    }

    private fun item(name: String, value: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = rowBackground()
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (4 * dp).toInt() }
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = name; textSize = 17f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value; textSize = 15f; setTextColor(0xFFC0C0C0.toInt())
            maxLines = 1
            gravity = Gravity.END
        })
        list.addView(row)
        return row
    }

    private fun choice(name: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
        item(name, value) { pick(name, options, options.indexOf(value)) { onPick(it) } }
    }

    private fun toggle(name: String, value: Boolean, onChange: (Boolean) -> Unit) {
        var v = value
        lateinit var row: LinearLayout
        row = item(name, if (v) "AN" else "AUS") {
            v = !v
            onChange(v)
            (row.getChildAt(1) as TextView).text = if (v) "AN" else "AUS"
        }
    }

    private fun slider(name: String, value: Int, onChange: (Int) -> Unit) {
        val row = item(name, "$value %") {}
        val valueView = row.getChildAt(1) as TextView
        val bar = SeekBar(this).apply {
            max = 90
            progress = value - 10
            layoutParams = LinearLayout.LayoutParams((180 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    valueView.text = "${p + 10} %"; onChange(p + 10)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(bar)
        row.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { bar.progress = (bar.progress - 5).coerceAtLeast(0); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { bar.progress = (bar.progress + 5).coerceAtMost(90); true }
                else -> false
            }
        }
    }

    private fun pick(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { d, which -> d.dismiss(); onPick(which) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun input(title: String, value: String, password: Boolean = false, onDone: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(value)
            setSingleLine()
            setTextColor(Color.WHITE)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = LinearLayout(this).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onDone(edit.text.toString()) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun message(title: String, text: String) {
        AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun testConnection() {
        if (cfg.smbHost.isEmpty() || cfg.smbShare.isEmpty()) {
            message("Netzwerk", "Bitte zuerst Server und Freigabe eintragen.")
            return
        }
        val wait = AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle("Netzwerk").setMessage("Verbinde mit \\\\${cfg.smbHost}\\${cfg.smbShare} …").show()
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { SmbSource(cfg).test() }
            wait.dismiss()
            message("Netzwerk", err ?: "Verbindung OK. System-Ordner wurden gefunden.")
        }
    }

    private fun pickSystemCore() {
        val systems = Systems.installedSystems(this)
        pick("System wählen", systems.map { it.name }, -1) { i ->
            val sys = systems[i]
            val cores = sys.cores.filter { Systems.coreFile(this, it).exists() }
            val names = listOf("Standard (${cores.firstOrNull() ?: "-"})") + cores
            val current = cfg.coreFor(sys.id)
            pick(sys.name, names, if (current.isEmpty()) 0 else names.indexOf(current)) { j ->
                cfg.setCoreFor(sys.id, if (j == 0) "" else names[j])
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val confirm = if (cfg.swapAB) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
            val cancel = if (cfg.swapAB) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
            when (event.keyCode) {
                confirm -> { currentFocus?.performClick(); return true }
                cancel, KeyEvent.KEYCODE_BUTTON_START -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("unused")
    private fun View.dummy() = Unit
}
