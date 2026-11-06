package de.delorean23.station

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Scraper wie bei Batocera. Ergebnisse (Bilder, Videos, gamelist.xml) werden
 * immer lokal in roms/<system>/ gespeichert.
 *  - ScreenScraper.fr: Login des Nutzers + Entwickler-Zugang (devid/devpassword)
 *  - Libretro-Thumbnails: nur Cover, ohne Account
 */
class Scraper(private val cfg: Config, private val library: Library) {

    class Result(val ok: Boolean, val message: String)

    @Volatile var cancelled = false

    fun scrape(game: Game, log: (String) -> Unit): Result {
        val sys = Systems.byId(game.system) ?: return Result(false, "Unbekanntes System")
        return try {
            if (cfg.scrapeSource == "libretro") libretro(sys, game) else screenScraper(sys, game, log)
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun needsScrape(game: Game): Boolean = !cfg.scrapeOnlyMissing || game.image.isEmpty() ||
            (cfg.scrapeSource != "libretro" && game.desc.isEmpty())

    // ---------------------------------------------------------------- ScreenScraper

    private fun screenScraper(sys: GameSystem, game: Game, log: (String) -> Unit): Result {
        if (cfg.ssDevId.isEmpty() || cfg.ssDevPassword.isEmpty())
            return Result(false, "Entwickler-Zugang (devid/devpassword) fehlt – im Menü unter SCRAPER eintragen")
        val ssId = SystemInfo.screenScraperId[sys.id] ?: return Result(false, "System wird von ScreenScraper nicht unterstützt")
        val size = try { library.source.size(sys.id, game.path) } catch (e: Exception) { -1L }
        val params = linkedMapOf(
            "devid" to cfg.ssDevId, "devpassword" to cfg.ssDevPassword,
            "softname" to "DeLoreanStation", "output" to "json",
            "ssid" to cfg.ssUser, "sspassword" to cfg.ssPassword,
            "systemeid" to ssId.toString(), "romtype" to "rom",
            "romnom" to game.fileName
        )
        if (size > 0) params["romtaille"] = size.toString()
        // Prüfsummen nur für Dateien bis 64 MB (über das Netzwerk sonst zu langsam)
        if (size in 1..(64L * 1024 * 1024)) {
            hashes(sys, game)?.let { (crc, md5) -> params["crc"] = crc; params["md5"] = md5 }
        }
        val url = "https://api.screenscraper.fr/api2/jeuInfos.php?" + params.entries.joinToString("&") { (k, v) -> k + "=" + enc(v) }
        val (code, body) = get(url)
        if (code != 200) {
            val msg = when {
                body.contains("Erreur de login", true) || code == 401 -> "Login falsch (Benutzer/Passwort prüfen)"
                code == 404 || body.contains("non trouvée", true) -> "Spiel nicht gefunden"
                code == 423 -> "ScreenScraper ist gerade gesperrt/überlastet"
                code == 426 -> "Entwickler-Zugang wird nicht akzeptiert"
                code == 429 || code == 430 || body.contains("quota", true) -> "Tageslimit von ScreenScraper erreicht"
                else -> "Fehler $code: ${body.take(120)}"
            }
            return Result(false, msg)
        }
        val jeu = JSONObject(body).getJSONObject("response").getJSONObject("jeu")
        val fields = HashMap<String, String>()
        pickRegion(jeu.optJSONArray("noms"), "region")?.let { fields["name"] = it }
        pickLang(jeu.optJSONArray("synopsis"))?.let { fields["desc"] = it }
        pickRegion(jeu.optJSONArray("dates"), "region")?.let { fields["releasedate"] = toEsDate(it) }
        jeu.optJSONObject("developpeur")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { fields["developer"] = it }
        jeu.optJSONObject("editeur")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { fields["publisher"] = it }
        jeu.optJSONObject("joueurs")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { fields["players"] = it }
        jeu.optJSONObject("note")?.optString("text")?.toFloatOrNull()?.let { fields["rating"] = "%.2f".format(java.util.Locale.US, it / 20f) }
        jeu.optJSONArray("genres")?.let { arr ->
            val names = (0 until arr.length()).mapNotNull { pickLang(arr.getJSONObject(it).optJSONArray("noms")) }
            if (names.isNotEmpty()) fields["genre"] = names.joinToString(", ")
        }
        val medias = jeu.optJSONArray("medias")
        val base = game.fileName.substringBeforeLast('.')
        val dir = File(cfg.romsDir, sys.id)
        if (medias != null) {
            mediaUrl(medias, cfg.scrapeImage)?.let { (u, fmt) ->
                download(u, File(dir, "images/$base-image.$fmt"))?.let { fields["image"] = "./images/$base-image.$fmt" }
            }
            if (cfg.scrapeImage != "box-2D") mediaUrl(medias, "box-2D")?.let { (u, fmt) ->
                download(u, File(dir, "images/$base-thumb.$fmt"))?.let { fields["thumbnail"] = "./images/$base-thumb.$fmt" }
            }
            mediaUrl(medias, "wheel")?.let { (u, fmt) ->
                download(u, File(dir, "images/$base-marquee.$fmt"))?.let { fields["marquee"] = "./images/$base-marquee.$fmt" }
            }
            if (cfg.scrapeVideos) {
                (mediaUrl(medias, "video-normalized") ?: mediaUrl(medias, "video"))?.let { (u, fmt) ->
                    log("Lade Video …")
                    download(u, File(dir, "videos/$base-video.$fmt"))?.let { fields["video"] = "./videos/$base-video.$fmt" }
                }
            }
        }
        library.updateGame(game) { m -> fields.forEach { (k, v) -> m[k] = v } }
        return Result(true, fields["name"] ?: game.name)
    }

    private val regions = listOf("de", "eu", "wor", "ss", "us", "uk", "fr", "jp")
    private val langs = listOf("de", "en", "fr")

    private fun pickRegion(arr: JSONArray?, key: String): String? {
        if (arr == null || arr.length() == 0) return null
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
        for (r in regions) items.firstOrNull { it.optString(key) == r }?.let { return it.optString("text") }
        return items.first().optString("text").takeIf { it.isNotEmpty() }
    }

    private fun pickLang(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
        for (l in langs) items.firstOrNull { it.optString("langue") == l }?.let { return it.optString("text") }
        return items.first().optString("text").takeIf { it.isNotEmpty() }
    }

    private fun mediaUrl(medias: JSONArray, type: String): Pair<String, String>? {
        val items = (0 until medias.length()).map { medias.getJSONObject(it) }.filter { it.optString("type") == type }
        if (items.isEmpty()) return null
        val m = regions.firstNotNullOfOrNull { r -> items.firstOrNull { it.optString("region") == r } } ?: items.first()
        val url = m.optString("url").takeIf { it.isNotEmpty() } ?: return null
        return url to m.optString("format", "png").ifEmpty { "png" }
    }

    private fun toEsDate(s: String): String {
        // "1994-11-21" oder "1994" → "19941121T000000"
        val digits = s.filter { it.isDigit() }
        return when {
            digits.length >= 8 -> digits.substring(0, 8) + "T000000"
            digits.length >= 4 -> digits.substring(0, 4) + "0101T000000"
            else -> ""
        }
    }

    private fun hashes(sys: GameSystem, game: Game): Pair<String, String>? = try {
        val input: InputStream = library.source.open(sys.id, game.path) ?: return null
        val crc = CRC32(); val md = MessageDigest.getInstance("MD5")
        input.use {
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (cancelled) return null
                val n = it.read(buf); if (n < 0) break
                crc.update(buf, 0, n); md.update(buf, 0, n)
            }
        }
        "%08X".format(crc.value) to md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    // ---------------------------------------------------------------- Libretro

    private fun libretro(sys: GameSystem, game: Game): Result {
        val folder = SystemInfo.libretroName[sys.id] ?: return Result(false, "Für dieses System gibt es keine Libretro-Cover")
        val names = listOf(game.fileName.substringBeforeLast('.'), game.name).distinct()
        val base = game.fileName.substringBeforeLast('.')
        val dst = File(File(cfg.romsDir, sys.id), "images/$base-image.png")
        for (n in names) {
            val clean = n.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")
            val url = "https://thumbnails.libretro.com/" + encPath(folder) + "/Named_Boxarts/" + encPath(clean) + ".png"
            if (download(url, dst) != null) {
                library.updateGame(game) { it["image"] = "./images/$base-image.png" }
                return Result(true, game.name)
            }
        }
        return Result(false, "Kein Cover gefunden")
    }

    // ---------------------------------------------------------------- HTTP

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun encPath(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "DeLoreanStation/1.2")
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    private fun download(url: String, dst: File): File? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 120000
        c.setRequestProperty("User-Agent", "DeLoreanStation/1.2")
        try {
            if (c.responseCode != 200) return null
            dst.parentFile?.mkdirs()
            val tmp = File(dst.path + ".part")
            c.inputStream.use { inp -> FileOutputStream(tmp).use { inp.copyTo(it, 64 * 1024) } }
            if (tmp.length() < 64) { tmp.delete(); return null }
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
            return dst
        } catch (e: Exception) {
            return null
        } finally { c.disconnect() }
    }
}
