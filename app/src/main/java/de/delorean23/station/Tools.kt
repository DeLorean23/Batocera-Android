package de.delorean23.station

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** BIOS-Prüfung wie "Fehlende BIOS prüfen" bei Batocera (Liste aus batocera-systems). */
object BiosCheck {

    enum class State { OK, MISSING, WRONG }
    class Entry(val file: String, val state: State, val md5: List<String> = emptyList())
    class SystemResult(val system: GameSystem, val entries: List<Entry>) {
        val missing get() = entries.count { it.state != State.OK }
    }

    fun run(context: Context, cfg: Config): List<SystemResult> {
        val json = JSONObject(context.assets.open("bios.json").bufferedReader().use { it.readText() })
        val out = ArrayList<SystemResult>()
        for (sys in Systems.installedSystems(context)) {
            val arr = json.optJSONArray(sys.id) ?: continue
            val entries = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val file = o.getString("file")
                val md5s = when (val m = o.opt("md5")) {
                    is JSONArray -> (0 until m.length()).map { m.getString(it).lowercase() }
                    is String -> listOf(m.lowercase())
                    else -> emptyList()
                }.filter { it.isNotEmpty() }
                val f = File(cfg.biosDir, file)
                val state = when {
                    !f.isFile -> State.MISSING
                    md5s.isEmpty() -> State.OK
                    else -> {
                        val sum = if (o.has("zipped")) zippedMd5(f, o.getString("zipped")) else md5(f)
                        if (sum != null && sum in md5s) State.OK else State.WRONG
                    }
                }
                Entry(file, state, md5s)
            }
            out.add(SystemResult(sys, entries))
        }
        try { writeReport(cfg, out) } catch (_: Exception) {}
        return out
    }

    /** bios/fehlende_bios.txt: was fehlt, wohin es gehört, welche Prüfsumme passt */
    private fun writeReport(cfg: Config, res: List<SystemResult>) {
        val sb = StringBuilder()
        val now = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(java.util.Date())
        sb.append("DeLorean Station – Fehlende BIOS-Dateien (Stand: $now)\n")
        sb.append("Ordner: ${cfg.biosDir.path}\n")
        sb.append("Liste wie bei Batocera. Bei manchen Systemen reicht eine der Dateien (z. B. PlayStation).\n\n")
        val bad = res.filter { it.missing > 0 }
        if (bad.isEmpty()) sb.append("Alle BIOS-Dateien sind vorhanden.\n")
        for (r in bad) {
            sb.append("== ${r.system.name} (${r.system.id}) ==\n")
            for (e in r.entries.filter { it.state != State.OK }) {
                val what = if (e.state == State.MISSING) "FEHLT" else "FALSCHE PRÜFSUMME"
                sb.append("  $what: bios/${e.file}\n")
                if (e.md5.isNotEmpty()) sb.append("      MD5: ${e.md5.joinToString(" oder ")}\n")
            }
            sb.append('\n')
        }
        val ok = res.filter { it.missing == 0 }
        if (ok.isNotEmpty()) sb.append("Vollständig: ${ok.joinToString(", ") { it.system.name }}\n")
        cfg.biosDir.mkdirs()
        File(cfg.biosDir, "fehlende_bios.txt").writeText(sb.toString())
    }

    private fun md5(f: File): String? = try {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    private fun zippedMd5(zip: File, entry: String): String? = try {
        ZipFile(zip).use { z ->
            val e = z.getEntry(entry) ?: return null
            val md = MessageDigest.getInstance("MD5")
            z.getInputStream(e).use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) { null }
}

/** Theme-Downloader mit der Theme-Liste von Batocera. */
object ThemeDownloader {

    class ThemeInfo(val name: String, val url: String, val author: String, val sizeMb: Int, val lastUpdate: String)

    private const val LIST = "https://updates.batocera.org/themes.json"

    fun list(): List<ThemeInfo> {
        val data = JSONObject(Http.text(LIST)).getJSONArray("data")
        return (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            ThemeInfo(o.optString("theme"), o.optString("theme_url"), o.optString("author"), o.optInt("size"), o.optString("last_update"))
        }.filter { it.name.isNotEmpty() && it.url.startsWith("https://github.com/") }.sortedBy { it.name.lowercase() }
    }

    /** Lädt das Theme als ZIP von GitHub und entpackt es nach themes/<name> */
    fun install(t: ThemeInfo, themesDir: File, progress: (String) -> Unit) {
        val repo = t.url.removePrefix("https://github.com/").trimEnd('/').removeSuffix(".git")
        val branch = try {
            JSONObject(Http.text("https://api.github.com/repos/$repo")).optString("default_branch", "master")
        } catch (e: Exception) { "master" }
        val tmp = File(themesDir, ".${t.name}.zip")
        progress("Lade ${t.name} …")
        Http.download("https://github.com/$repo/archive/refs/heads/$branch.zip", tmp) { done ->
            progress("Lade ${t.name} … ${done / (1024 * 1024)} MB" + if (t.sizeMb > 0) " von ca. ${t.sizeMb} MB" else "")
        }
        progress("Entpacke …")
        val target = File(themesDir, t.name)
        val staging = File(themesDir, ".${t.name}.new")
        staging.deleteRecursively(); staging.mkdirs()
        ZipInputStream(tmp.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                // obersten Ordner (repo-branch/) abschneiden
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = File(staging, rel)
                if (!f.canonicalPath.startsWith(staging.canonicalPath)) continue
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { zin.copyTo(it) }
            }
        }
        tmp.delete()
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IllegalStateException("Konnte Theme nicht speichern")
    }
}

/** Update-Prüfung über die GitHub-Releases von DeLorean23/Batocera-Android. */
object Updater {

    class Update(val build: Int, val url: String, val name: String)

    private const val API = "https://api.github.com/repos/DeLorean23/Batocera-Android/releases/latest"

    fun check(): Update? {
        val o = JSONObject(Http.text(API))
        val build = o.optString("tag_name").substringAfterLast('-').toIntOrNull() ?: return null
        if (build <= BuildConfig.BUILD_NUMBER) return null
        val want = if (is64()) "DeLoreanStation-64bit.apk" else "DeLoreanStation-32bit.apk"
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == want) return Update(build, a.optString("browser_download_url"), want)
        }
        return null
    }

    fun is64(): Boolean = if (Build.VERSION.SDK_INT >= 23) android.os.Process.is64Bit()
    else Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    fun download(context: Context, u: Update, progress: (Long) -> Unit): File {
        val f = File(context.cacheDir, "update.apk")
        Http.download(u.url, f, progress)
        return f
    }

    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

object Http {
    fun text(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "DeLoreanStation")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun download(url: String, dst: File, progress: (Long) -> Unit) {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 20000; c.readTimeout = 120000
            c.setRequestProperty("User-Agent", "DeLoreanStation")
            try {
                val code = c.responseCode
                if (code in 300..399) { u = c.getHeaderField("Location") ?: throw IllegalStateException("Weiterleitung ohne Ziel"); return@repeat }
                if (code != 200) throw IllegalStateException("HTTP $code")
                dst.parentFile?.mkdirs()
                val tmp = File(dst.path + ".part")
                c.inputStream.use { inp ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(256 * 1024); var done = 0L; var last = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done - last > 1024 * 1024) { progress(done); last = done }
                        }
                    }
                }
                if (dst.exists()) dst.delete()
                tmp.renameTo(dst)
                return
            } finally { c.disconnect() }
        }
        throw IllegalStateException("Zu viele Weiterleitungen")
    }
}
