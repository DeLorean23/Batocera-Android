package de.delorean23.station

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/**
 * Einstellungen und Ordner. Aufbau wie /userdata bei Batocera:
 *   <Basis>/roms/<system>/…  (mit gamelist.xml, images/, videos/)
 *   <Basis>/bios/
 *   <Basis>/saves/<system>/
 *   <Basis>/themes/<theme>/
 *   <Basis>/screenshots/
 */
class Config(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("station", Context.MODE_PRIVATE)

    var baseDir: String
        get() = prefs.getString("baseDir", null)
            ?: File(Environment.getExternalStorageDirectory(), "batocera").absolutePath
        set(v) = prefs.edit().putString("baseDir", v).apply()

    /** "intern" oder "netzwerk" – wie "Speichermedium" bei Batocera */
    var storage: String
        get() = prefs.getString("storage", STORAGE_LOCAL) ?: STORAGE_LOCAL
        set(v) = prefs.edit().putString("storage", v).apply()

    var smbHost: String
        get() = prefs.getString("smbHost", "") ?: ""
        set(v) = prefs.edit().putString("smbHost", v.trim()).apply()

    var smbShare: String
        get() = prefs.getString("smbShare", "") ?: ""
        set(v) = prefs.edit().putString("smbShare", v.trim().trim('/', '\\')).apply()

    /** Unterordner in der Freigabe, in dem die System-Ordner liegen (z. B. "roms") */
    var smbPath: String
        get() = prefs.getString("smbPath", "roms") ?: "roms"
        set(v) = prefs.edit().putString("smbPath", v.trim().trim('/', '\\')).apply()

    var smbUser: String
        get() = prefs.getString("smbUser", "") ?: ""
        set(v) = prefs.edit().putString("smbUser", v.trim()).apply()

    var smbPassword: String
        get() = prefs.getString("smbPassword", "") ?: ""
        set(v) = prefs.edit().putString("smbPassword", v).apply()

    var smbDomain: String
        get() = prefs.getString("smbDomain", "") ?: ""
        set(v) = prefs.edit().putString("smbDomain", v.trim()).apply()

    var theme: String
        get() = prefs.getString("theme", "es-theme-carbon") ?: "es-theme-carbon"
        set(v) = prefs.edit().putString("theme", v).apply()

    /** "auto" (aus bei angeschlossenem Controller), "an", "aus" */
    var touchMode: String
        get() = prefs.getString("touchMode", "auto") ?: "auto"
        set(v) = prefs.edit().putString("touchMode", v).apply()

    var touchOpacity: Int
        get() = prefs.getInt("touchOpacity", 70)
        set(v) = prefs.edit().putInt("touchOpacity", v.coerceIn(10, 100)).apply()

    var touchVibrate: Boolean
        get() = prefs.getBoolean("touchVibrate", true)
        set(v) = prefs.edit().putBoolean("touchVibrate", v).apply()

    /** Batocera-Standard: Taste rechts (Xbox B) bestätigt, Taste unten (Xbox A) zurück */
    var swapAB: Boolean
        get() = prefs.getBoolean("swapAB", false)
        set(v) = prefs.edit().putBoolean("swapAB", v).apply()

    var shader: String
        get() = prefs.getString("shader", "sharp") ?: "sharp"
        set(v) = prefs.edit().putString("shader", v).apply()

    var showHidden: Boolean
        get() = prefs.getBoolean("showHidden", false)
        set(v) = prefs.edit().putBoolean("showHidden", v).apply()

    var lastSystem: String
        get() = prefs.getString("lastSystem", "") ?: ""
        set(v) = prefs.edit().putString("lastSystem", v).apply()

    // ---- Theme-Einstellungen (wie Batocera: Systemansicht, Spieleansicht, Farbschema)
    var systemView: String
        get() = prefs.getString("systemView", "horizontal") ?: "horizontal"
        set(v) = prefs.edit().putString("systemView", v).apply()

    var gameView: String
        get() = prefs.getString("gameView", "detailed") ?: "detailed"
        set(v) = prefs.edit().putString("gameView", v).apply()

    var colorset: String
        get() = prefs.getString("colorset", "") ?: ""
        set(v) = prefs.edit().putString("colorset", v).apply()

    var videoPreview: Boolean
        get() = prefs.getBoolean("videoPreview", true)
        set(v) = prefs.edit().putBoolean("videoPreview", v).apply()

    // ---- Standard-Bild (alle Spiele), pro Spiel überschreibbar
    var aspect: String
        get() = prefs.getString("aspect", "core") ?: "core"
        set(v) = prefs.edit().putString("aspect", v).apply()

    var integerScale: Boolean
        get() = prefs.getBoolean("integerScale", false)
        set(v) = prefs.edit().putBoolean("integerScale", v).apply()

    // ---- Scraper
    var scrapeSource: String
        get() = prefs.getString("scrapeSource", "screenscraper") ?: "screenscraper"
        set(v) = prefs.edit().putString("scrapeSource", v).apply()
    var ssUser: String
        get() = prefs.getString("ssUser", "") ?: ""
        set(v) = prefs.edit().putString("ssUser", v.trim()).apply()
    var ssPassword: String
        get() = prefs.getString("ssPassword", "") ?: ""
        set(v) = prefs.edit().putString("ssPassword", v).apply()
    var ssDevId: String
        get() = prefs.getString("ssDevId", "") ?: ""
        set(v) = prefs.edit().putString("ssDevId", v.trim()).apply()
    var ssDevPassword: String
        get() = prefs.getString("ssDevPassword", "") ?: ""
        set(v) = prefs.edit().putString("ssDevPassword", v.trim()).apply()
    /** mixrbv2, box-2D, ss */
    var scrapeImage: String
        get() = prefs.getString("scrapeImage", "mixrbv2") ?: "mixrbv2"
        set(v) = prefs.edit().putString("scrapeImage", v).apply()
    var scrapeVideos: Boolean
        get() = prefs.getBoolean("scrapeVideos", false)
        set(v) = prefs.edit().putBoolean("scrapeVideos", v).apply()
    var scrapeOnlyMissing: Boolean
        get() = prefs.getBoolean("scrapeOnlyMissing", true)
        set(v) = prefs.edit().putBoolean("scrapeOnlyMissing", v).apply()

    // ---- Updates
    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean("autoUpdateCheck", true)
        set(v) = prefs.edit().putBoolean("autoUpdateCheck", v).apply()

    // ---- Netplay
    var netplayName: String
        get() = prefs.getString("netplayName", android.os.Build.MODEL ?: "Spieler") ?: "Spieler"
        set(v) = prefs.edit().putString("netplayName", v.trim()).apply()

    // ---- Einstellungen pro Spiel ("" = Standard)
    private fun gameKey(system: String, path: String, key: String) = "g|$system|$path|$key"
    fun gameSetting(system: String, path: String, key: String): String =
        prefs.getString(gameKey(system, path, key), "") ?: ""
    fun setGameSetting(system: String, path: String, key: String, value: String) {
        if (value.isEmpty()) prefs.edit().remove(gameKey(system, path, key)).apply()
        else prefs.edit().putString(gameKey(system, path, key), value).apply()
    }

    /** Wirksamer Wert: Spiel → System → global */
    fun effective(system: String, path: String, key: String): String {
        gameSetting(system, path, key).takeIf { it.isNotEmpty() }?.let { return it }
        return when (key) {
            "core" -> coreFor(system)
            "aspect" -> aspect
            "scale" -> if (integerScale) "integer" else "normal"
            "shader" -> shader
            else -> ""
        }
    }

    /** Pro System gewählter Core ("" = Standard) */
    fun coreFor(system: String): String = prefs.getString("core_$system", "") ?: ""
    fun setCoreFor(system: String, core: String) =
        prefs.edit().putString("core_$system", core).apply()

    val isNetwork get() = storage == STORAGE_NETWORK && smbHost.isNotEmpty() && smbShare.isNotEmpty()

    val base get() = File(baseDir)
    val romsDir get() = File(base, "roms")
    val biosDir get() = File(base, "bios")
    val savesDir get() = File(base, "saves")
    val themesDir get() = File(base, "themes")
    val screenshotsDir get() = File(base, "screenshots")

    fun ensureFolders() {
        listOf(romsDir, biosDir, savesDir, themesDir, screenshotsDir).forEach { it.mkdirs() }
        Systems.all.forEach { File(romsDir, it.id).mkdirs() }
    }

    companion object {
        const val STORAGE_LOCAL = "intern"
        const val STORAGE_NETWORK = "netzwerk"
    }
}
