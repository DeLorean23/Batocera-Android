package de.delorean23.station

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
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

/** Hauptmenü im Stil von Batocera (START). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private val dp by lazy { resources.displayMetrics.density }
    private var page = "main"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        scroll = ScrollView(this)
        scroll.setBackgroundColor(0xF0101010.toInt())
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((32 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
        }
        scroll.addView(list)
        setContentView(scroll)
        show("main")
    }

    private fun show(p: String) {
        page = p
        list.removeAllViews()
        when (p) {
            "main" -> buildMain()
            "network" -> buildNetwork()
            "theme" -> buildTheme()
            "controls" -> buildControls()
            "emulators" -> buildEmulators()
            "scraper" -> buildScraper()
            "system" -> buildSystem()
        }
        list.post { firstFocusable()?.requestFocus(); scroll.scrollTo(0, 0) }
    }

    private fun firstFocusable() = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }

    // ---------------------------------------------------------------- Seiten

    private fun buildMain() {
        title("HAUPTMENÜ")
        item("Speichermedium & Netzwerk", if (cfg.storage == Config.STORAGE_NETWORK) "Netzwerk" else "Intern") { show("network") }
        item("Theme-Einstellungen", cfg.theme) { show("theme") }
        item("Steuerung", "") { show("controls") }
        item("Emulatoren & Bild", "") { show("emulators") }
        item("Scraper", if (cfg.scrapeSource == "libretro") "Libretro-Cover" else "ScreenScraper") { show("scraper") }
        item("Netplay: Spiel beitreten", "") { MainActivity.pendingJoin = true; finish() }
        item("Fehlende BIOS prüfen", "") { biosCheck() }
        item("System & Updates", "Build ${BuildConfig.BUILD_NUMBER}") { show("system") }
        section("")
        item("Zurück", "") { finish() }
        item("App beenden", "") { finishAffinity() }
        label("DeLorean Station ${BuildConfig.VERSION_NAME}  ·  Created by DeLorean23")
    }

    private fun buildNetwork() {
        title("SPEICHERMEDIUM")
        choice("ROMs laden von", if (cfg.storage == Config.STORAGE_NETWORK) "Netzwerk (SMB)" else "Intern",
            listOf("Intern", "Netzwerk (SMB)")) {
            cfg.storage = if (it == 1) Config.STORAGE_NETWORK else Config.STORAGE_LOCAL; show(page)
        }
        item("Basis-Ordner", cfg.baseDir) {
            input("Basis-Ordner (enthält roms, bios, saves, themes)", cfg.baseDir) { cfg.baseDir = it; show(page) }
        }
        section("NETZWERK-FREIGABE (NAS)")
        item("Server (IP oder Name)", cfg.smbHost.ifEmpty { "nicht gesetzt" }) { input("Server, z. B. 192.168.178.20", cfg.smbHost) { cfg.smbHost = it; show(page) } }
        item("Freigabe", cfg.smbShare.ifEmpty { "nicht gesetzt" }) { input("Name der Freigabe", cfg.smbShare) { cfg.smbShare = it; show(page) } }
        item("Ordner mit den Systemen", cfg.smbPath.ifEmpty { "(Wurzel der Freigabe)" }) { input("Pfad in der Freigabe, z. B. roms", cfg.smbPath) { cfg.smbPath = it; show(page) } }
        item("Benutzer", cfg.smbUser.ifEmpty { "Gast" }) { input("Benutzername (leer = Gast)", cfg.smbUser) { cfg.smbUser = it; show(page) } }
        item("Passwort", if (cfg.smbPassword.isEmpty()) "kein" else "••••••") { input("Passwort", cfg.smbPassword, password = true) { cfg.smbPassword = it; show(page) } }
        item("Domäne / Arbeitsgruppe", cfg.smbDomain.ifEmpty { "keine" }) { input("Domäne (meist leer lassen)", cfg.smbDomain) { cfg.smbDomain = it; show(page) } }
        item("Verbindung testen", "") { testConnection() }
        back()
    }

    private fun buildTheme() {
        title("THEME-EINSTELLUNGEN")
        val themes = (cfg.themesDir.listFiles()?.filter { File(it, "theme.xml").isFile }?.map { it.name } ?: emptyList()).sorted()
        item("Theme", cfg.theme + if (File(File(cfg.themesDir, cfg.theme), "theme.xml").isFile) "" else " (nicht gefunden)") {
            if (themes.isEmpty()) message("Keine Themes", "Lege einen Theme-Ordner in\n${cfg.themesDir.path}\noder lade eines mit dem Theme-Downloader.")
            else Ui.choice(this, cfg, "Theme wählen", themes, themes.indexOf(cfg.theme)) { cfg.theme = themes[it]; cfg.colorset = ""; show(page) }
        }
        val theme = Theme(File(cfg.themesDir, cfg.theme))
        val sets = theme.colorsets()
        val setNames = listOf("Standard") + sets
        choice("Farbschema", cfg.colorset.ifEmpty { "Standard" }, setNames) { cfg.colorset = if (it == 0) "" else sets[it - 1]; show(page) }
        val sv = listOf("horizontal", "vertical", "wheel", "simple")
        val svNames = listOf("Horizontal", "Vertikal", "Rad", "Einfache Liste")
        choice("Systemansicht", svNames[sv.indexOf(cfg.systemView).coerceAtLeast(0)], svNames) { cfg.systemView = sv[it]; show(page) }
        val gv = listOf("detailed", "basic", "tiles", "icons")
        val gvNames = listOf("Liste mit Details", "Einfache Liste", "Kacheln", "Icons")
        choice("Spieleansicht", gvNames[gv.indexOf(cfg.gameView).coerceAtLeast(0)], gvNames) { cfg.gameView = gv[it]; show(page) }
        toggle("Video-Vorschau", cfg.videoPreview) { cfg.videoPreview = it }
        toggle("Versteckte Spiele anzeigen", cfg.showHidden) { cfg.showHidden = it }
        section("")
        item("Themes herunterladen", "") { themeDownloader() }
        back()
    }

    private fun buildControls() {
        title("STEUERUNG")
        val modes = listOf("auto", "an", "aus")
        val modeNames = listOf("Automatisch (aus bei Controller)", "Immer an", "Aus")
        choice("Touch-Controller", modeNames[modes.indexOf(cfg.touchMode).coerceAtLeast(0)], modeNames) { cfg.touchMode = modes[it]; show(page) }
        slider("Touch-Deckkraft", cfg.touchOpacity) { cfg.touchOpacity = it }
        toggle("Vibration bei Touch", cfg.touchVibrate) { cfg.touchVibrate = it }
        toggle("Bestätigen/Zurück tauschen (A/B)", cfg.swapAB) { cfg.swapAB = it }
        section("CONTROLLER")
        item("Controller einstellen", "") { startActivity(Intent(this, ControllerMapActivity::class.java)) }
        label("Spieler 2: zweiten Controller verbinden. Ist der Touch-Controller sichtbar, ist er Spieler 1 und der erste echte Controller Spieler 2.")
        section("HOTKEYS (SELECT HALTEN)")
        label("SELECT + START = Beenden   ·   SELECT + A = Spielmenü\nSELECT + B = Neustart   ·   SELECT + X = Speichern   ·   SELECT + Y = Laden\nSELECT + Hoch/Runter = Slot   ·   SELECT + Rechts = Schnellvorlauf")
        back()
    }

    private fun buildEmulators() {
        title("EMULATOREN & BILD")
        val shaders = listOf("sharp", "default", "crt", "lcd", "smooth")
        val shaderNames = listOf("Scharf (Pixel)", "Weich (bilinear)", "Röhrenfernseher (CRT)", "LCD-Raster", "Hochskaliert")
        choice("Bildfilter", shaderNames[shaders.indexOf(cfg.shader).coerceAtLeast(0)], shaderNames) { cfg.shader = shaders[it]; show(page) }
        val asp = listOf("core", "4:3", "16:9", "voll")
        val aspNames = listOf("Original (Emulator)", "4:3", "16:9", "Bild füllen")
        choice("Seitenverhältnis", aspNames[asp.indexOf(cfg.aspect).coerceAtLeast(0)], aspNames) { cfg.aspect = asp[it]; show(page) }
        toggle("Ganzzahlige Skalierung", cfg.integerScale) { cfg.integerScale = it }
        item("Emulator pro System", "") { pickSystemCore() }
        label("Einstellungen nur für ein Spiel: in der Spieleliste SELECT drücken oder lange auf das Spiel tippen → Spiel-Einstellungen.")
        val installed = Systems.installedSystems(this)
        item("Eingebaute Systeme", "${installed.size} von ${Systems.all.size}") {
            message("Eingebaute Systeme", installed.joinToString("\n") { s ->
                "${s.name} (${s.id}) – ${s.cores.filter { Systems.coreFile(this, it).exists() }.joinToString(", ")}"
            })
        }
        back()
    }

    private fun buildScraper() {
        title("SCRAPER")
        val src = listOf("screenscraper", "libretro")
        val srcNames = listOf("ScreenScraper.fr", "Libretro-Cover (ohne Account)")
        choice("Quelle", srcNames[src.indexOf(cfg.scrapeSource).coerceAtLeast(0)], srcNames) { cfg.scrapeSource = src[it]; show(page) }
        if (cfg.scrapeSource == "screenscraper") {
            section("SCREENSCRAPER-LOGIN")
            item("Benutzer", cfg.ssUser.ifEmpty { "nicht gesetzt" }) { input("ScreenScraper-Benutzer", cfg.ssUser) { cfg.ssUser = it; show(page) } }
            item("Passwort", if (cfg.ssPassword.isEmpty()) "kein" else "••••••") { input("ScreenScraper-Passwort", cfg.ssPassword, password = true) { cfg.ssPassword = it; show(page) } }
            item("Entwickler-ID (devid)", cfg.ssDevId.ifEmpty { "nicht gesetzt" }) { input("devid", cfg.ssDevId) { cfg.ssDevId = it; show(page) } }
            item("Entwickler-Passwort", if (cfg.ssDevPassword.isEmpty()) "nicht gesetzt" else "••••••") { input("devpassword", cfg.ssDevPassword, password = true) { cfg.ssDevPassword = it; show(page) } }
            label("Die Entwickler-Zugangsdaten bekommst du kostenlos über das Forum von screenscraper.fr.")
            val img = listOf("mixrbv2", "box-2D", "ss")
            val imgNames = listOf("Mix (Box + Screenshot)", "Box (Cover)", "Screenshot")
            choice("Bild", imgNames[img.indexOf(cfg.scrapeImage).coerceAtLeast(0)], imgNames) { cfg.scrapeImage = img[it]; show(page) }
            toggle("Videos laden", cfg.scrapeVideos) { cfg.scrapeVideos = it }
        }
        toggle("Nur Spiele ohne Daten", cfg.scrapeOnlyMissing) { cfg.scrapeOnlyMissing = it }
        label("Alles wird lokal gespeichert (roms/<system>/images, gamelist.xml), auch im Netzwerk-Modus.")
        section("")
        item("Alle Systeme scrapen", "") { startScrape("") }
        item("System wählen und scrapen", "") {
            val sys = Systems.installedSystems(this)
            Ui.list(this, cfg, "System scrapen", sys.map { it.name }) { startScrape(sys[it].id) }
        }
        back()
    }

    private fun buildSystem() {
        title("SYSTEM & UPDATES")
        item("Version", BuildConfig.VERSION_NAME) {}
        item("Nach Update suchen", "") { checkUpdate() }
        toggle("Automatisch nach Updates suchen", cfg.autoUpdateCheck) { cfg.autoUpdateCheck = it }
        item("Netplay-Name", cfg.netplayName) { input("Name im Netplay", cfg.netplayName) { cfg.netplayName = it; show(page) } }
        item("Ordnerstruktur", cfg.base.path) {
            message("Ordnerstruktur", "${cfg.romsDir.path}/<system>\n${cfg.biosDir.path}\n${cfg.savesDir.path}/<system>\n${cfg.themesDir.path}")
        }
        back()
    }

    // ---------------------------------------------------------------- Aktionen

    private fun startScrape(systems: String) {
        if (cfg.scrapeSource == "screenscraper" && (cfg.ssUser.isEmpty() || cfg.ssDevId.isEmpty())) {
            message("Scraper", "Bitte zuerst Benutzer und Entwickler-Zugang für ScreenScraper eintragen oder auf Libretro-Cover umstellen.")
            return
        }
        MainActivity.scrapeStamp = System.currentTimeMillis().toString()
        startActivity(Intent(this, ScrapeActivity::class.java).putExtra("systems", systems))
    }

    private fun biosCheck() {
        val wait = message("BIOS", "Prüfe BIOS-Dateien …")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { try { BiosCheck.run(this@SettingsActivity, cfg) } catch (e: Exception) { emptyList() } }
            wait.dismiss()
            if (res.isEmpty()) { message("BIOS", "Keine BIOS-Liste für die eingebauten Systeme."); return@launch }
            val sb = StringBuilder()
            val missing = res.filter { it.missing > 0 }
            sb.append(if (missing.isEmpty()) "Alle BIOS-Dateien sind vorhanden.\n\n" else "${missing.size} System(e) mit fehlenden oder falschen Dateien.\n\n")
            for (r in res.sortedByDescending { it.missing }) {
                sb.append(if (r.missing == 0) "✓ " else "✗ ").append(r.system.name).append('\n')
                for (e in r.entries) {
                    val s = when (e.state) { BiosCheck.State.OK -> "vorhanden"; BiosCheck.State.MISSING -> "FEHLT"; BiosCheck.State.WRONG -> "FALSCHE PRÜFSUMME" }
                    if (e.state != BiosCheck.State.OK || r.missing == 0) sb.append("    bios/").append(e.file).append(" – ").append(s).append('\n')
                }
            }
            sb.append("\nHinweis: Bei manchen Systemen reicht eine der Dateien (z. B. PlayStation).")
            message("Fehlende BIOS", sb.toString())
        }
    }

    private fun themeDownloader() {
        val wait = message("Themes", "Lade Theme-Liste …")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { try { Result.success(ThemeDownloader.list()) } catch (e: Exception) { Result.failure<List<ThemeDownloader.ThemeInfo>>(e) } }
            wait.dismiss()
            r.onFailure { message("Themes", "Theme-Liste konnte nicht geladen werden: ${it.message}") }
            r.onSuccess { themes ->
                val names = themes.map { t ->
                    val inst = File(File(cfg.themesDir, t.name), "theme.xml").isFile
                    (if (inst) "✓ " else "") + t.name + "  –  " + t.author + if (t.sizeMb > 0) "  (${t.sizeMb} MB)" else ""
                }
                Ui.list(this@SettingsActivity, cfg, "Themes herunterladen", names) { i ->
                    val t = themes[i]
                    Ui.confirm(this@SettingsActivity, cfg, t.name, "Von ${t.author}\nZuletzt geändert: ${t.lastUpdate}\n\nHerunterladen und installieren?", "Installieren") {
                        installTheme(t)
                    }
                }
            }
        }
    }

    private fun installTheme(t: ThemeDownloader.ThemeInfo) {
        val dlg = message("Themes", "Lade ${t.name} …")
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    cfg.themesDir.mkdirs()
                    ThemeDownloader.install(t, cfg.themesDir) { p -> runOnUiThread { dlg.setMessage(p) } }
                    null
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            dlg.dismiss()
            if (err != null) message("Themes", "Fehler: $err")
            else Ui.confirm(this@SettingsActivity, cfg, "Fertig", "${t.name} ist installiert. Jetzt verwenden?", "Verwenden") {
                cfg.theme = t.name; cfg.colorset = ""; show(page)
            }
        }
    }

    private fun checkUpdate() {
        val wait = message("Update", "Suche nach Updates …")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { try { Result.success(Updater.check()) } catch (e: Exception) { Result.failure<Updater.Update?>(e) } }
            wait.dismiss()
            r.onFailure { message("Update", "Fehler: ${it.message}") }
            r.onSuccess { u ->
                if (u == null) message("Update", "Du hast die neueste Version (Build ${BuildConfig.BUILD_NUMBER}).")
                else Ui.confirm(this@SettingsActivity, cfg, "Update verfügbar", "Build ${u.build} (${u.name}) ist verfügbar.\nJetzt installieren?", "Installieren") {
                    downloadUpdate(this@SettingsActivity, cfg, u)
                }
            }
        }
    }

    private fun testConnection() {
        if (cfg.smbHost.isEmpty() || cfg.smbShare.isEmpty()) { message("Netzwerk", "Bitte zuerst Server und Freigabe eintragen."); return }
        val wait = message("Netzwerk", "Verbinde mit \\\\${cfg.smbHost}\\${cfg.smbShare} …")
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { SmbSource(cfg).test() }
            wait.dismiss()
            message("Netzwerk", err ?: "Verbindung OK. System-Ordner wurden gefunden.")
        }
    }

    private fun pickSystemCore() {
        val systems = Systems.installedSystems(this)
        Ui.list(this, cfg, "System wählen", systems.map { it.name }) { i ->
            val sys = systems[i]
            val cores = sys.cores.filter { Systems.coreFile(this, it).exists() }
            val names = listOf("Standard (${cores.firstOrNull() ?: "-"})") + cores
            val current = cfg.coreFor(sys.id)
            Ui.choice(this, cfg, sys.name, names, if (current.isEmpty()) 0 else names.indexOf(current)) { j ->
                cfg.setCoreFor(sys.id, if (j == 0) "" else names[j])
            }
        }
    }

    // ---------------------------------------------------------------- Bausteine

    private fun title(t: String) {
        list.addView(TextView(this).apply {
            text = t; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })
    }

    private fun section(t: String) {
        list.addView(TextView(this).apply {
            text = t; textSize = 14f; setTextColor(0xFFBD4747.toInt()); setTypeface(typeface, Typeface.BOLD)
            setPadding((8 * dp).toInt(), (18 * dp).toInt(), 0, (4 * dp).toInt())
        })
    }

    private fun label(t: String) {
        list.addView(TextView(this).apply {
            text = t; textSize = 14f; setTextColor(0xFF909090.toInt())
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
        })
    }

    private fun back() {
        section("")
        item("Zurück", "") { show("main") }
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
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true; background = rowBackground()
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() }
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = name; textSize = 17f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply { text = value; textSize = 15f; setTextColor(0xFFC0C0C0.toInt()); maxLines = 1; gravity = Gravity.END })
        list.addView(row)
        return row
    }

    private fun choice(name: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
        item(name, value) { Ui.choice(this, cfg, name, options, options.indexOf(value)) { onPick(it) } }
    }

    private fun toggle(name: String, value: Boolean, onChange: (Boolean) -> Unit) {
        var v = value
        lateinit var row: LinearLayout
        row = item(name, if (v) "AN" else "AUS") {
            v = !v; onChange(v)
            (row.getChildAt(1) as TextView).text = if (v) "AN" else "AUS"
        }
    }

    private fun slider(name: String, value: Int, onChange: (Int) -> Unit) {
        val row = item(name, "$value %") {}
        val valueView = row.getChildAt(1) as TextView
        val bar = SeekBar(this).apply {
            max = 90; progress = value - 10
            layoutParams = LinearLayout.LayoutParams((180 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { valueView.text = "${p + 10} %"; onChange(p + 10) }
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

    private fun input(title: String, value: String, password: Boolean = false, onDone: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(value); setSingleLine(); setTextColor(Color.WHITE)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = LinearLayout(this).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val d = AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onDone(edit.text.toString()) }
            .setNegativeButton("Abbrechen", null)
            .setNeutralButton("Tastatur") { _, _ -> Ui.keyboard(this, cfg, title, edit.text.toString()) { onDone(it) } }
            .create()
        d.show()
    }

    private fun message(title: String, text: String): AlertDialog = Ui.message(this, cfg, title, text)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val code = Remap.translate(this, event)
            val confirm = if (cfg.swapAB) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
            val cancel = if (cfg.swapAB) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
            when (code) {
                confirm -> { currentFocus?.performClick(); return true }
                cancel, KeyEvent.KEYCODE_BUTTON_START -> { if (page == "main") finish() else show("main"); return true }
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && page != "main") { show("main"); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun downloadUpdate(activity: AppCompatActivity, cfg: Config, u: Updater.Update) {
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                Ui.confirm(activity, cfg, "Erlaubnis nötig", "Damit die App Updates installieren kann, musst du \"Unbekannte Apps installieren\" für DeLorean Station erlauben. Danach erneut auf Update tippen.", "Einstellungen") {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                return
            }
            val dlg = Ui.message(activity, cfg, "Update", "Lade Build ${u.build} …")
            activity.lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) {
                    try { Updater.download(activity, u) { done -> activity.runOnUiThread { dlg.setMessage("Lade Build ${u.build} … ${done / (1024 * 1024)} MB") } } }
                    catch (e: Exception) { null }
                }
                dlg.dismiss()
                if (f == null) Ui.message(activity, cfg, "Update", "Download fehlgeschlagen.")
                else Updater.install(activity, f)
            }
        }

        @Suppress("unused")
        private fun Activity.dummy() = Unit
    }
}
