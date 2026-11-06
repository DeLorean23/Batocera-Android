package de.delorean23.station

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Content-Downloader wie bei Batocera: freie Spiele aus dem offiziellen
 * Batocera-Store (store.batocera.org). Die Pakete enthalten Dateien unter
 * userdata/roms/<system>/…, die hier nach <Basis>/roms/… installiert werden.
 */
object Store {

    private const val BASE = "https://store.batocera.org/"

    class Package(
        val name: String, val version: String, val desc: String, val system: String,
        val file: String, val size: Long, val url: String
    ) {
        /** Anzeigename: Teil der Beschreibung vor " - " */
        val title: String get() = desc.substringBefore(" - ").ifEmpty { name }
        val info: String get() = desc.substringAfter(" - ", "")
    }

    /** Lädt die Paketliste und behält nur Spiele für Systeme, die in dieser APK laufen */
    fun list(installedSystems: Set<String>): List<Package> {
        val tmp = File.createTempFile("store", ".db")
        try {
            Http.download(BASE + "batocera.db", tmp) {}
            val out = ArrayList<Package>()
            TarArchiveInputStream(GZIPInputStream(tmp.inputStream().buffered())).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    if (!e.name.endsWith("/desc")) continue
                    val fields = parseDesc(tar.readBytes().toString(Charsets.UTF_8))
                    val group = fields["GROUPS"].orEmpty()
                    if (!group.startsWith("sys-")) continue
                    if (fields["ARCH"] != "any") continue
                    val sys = group.removePrefix("sys-")
                    if (sys !in installedSystems) continue
                    out += Package(
                        fields["NAME"].orEmpty(), fields["VERSION"].orEmpty(), fields["DESC"].orEmpty(), sys,
                        fields["FILENAME"].orEmpty(), fields["CSIZE"]?.toLongOrNull() ?: 0, fields["URL"].orEmpty()
                    )
                }
            }
            return out.filter { it.name.isNotEmpty() && it.file.isNotEmpty() }.sortedWith(compareBy({ it.system }, { it.title.lowercase() }))
        } finally { tmp.delete() }
    }

    private fun parseDesc(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        var key: String? = null
        val sb = StringBuilder()
        for (line in text.lines()) {
            if (line.startsWith("%") && line.endsWith("%") && line.length > 2) {
                key?.let { m[it] = sb.toString().trim() }
                key = line.trim('%'); sb.setLength(0)
            } else if (key != null && line.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        key?.let { m[it] = sb.toString().trim() }
        return m
    }

    private fun recordFile(cfg: Config, p: Package) = File(cfg.base, "system/store/${p.name}.files")

    fun isInstalled(cfg: Config, p: Package) = recordFile(cfg, p).isFile

    /** Lädt und installiert ein Paket. Trägt das Spiel in die gamelist.xml ein. */
    fun install(cfg: Config, p: Package, progress: (String) -> Unit) {
        val pkg = File(cfg.base, "cache/store/${p.file}")
        progress("Lade ${p.title} …")
        Http.download(BASE + p.file, pkg) { done ->
            val pct = if (p.size > 0) " ${done * 100 / p.size} %" else " ${done / 1048576} MB"
            progress("Lade ${p.title} …$pct")
        }
        progress("Installiere ${p.title} …")
        val written = ArrayList<String>()
        try {
            TarArchiveInputStream(ZstdCompressorInputStream(pkg.inputStream().buffered(256 * 1024))).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val target = mapPath(cfg, e.name) ?: continue
                    if (e.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { tar.copyTo(it, 256 * 1024) }
                    written += target.absolutePath
                }
            }
        } finally { pkg.delete() }
        if (written.isEmpty()) throw IllegalStateException("Das Paket enthält keine Spieldateien")

        // gamelist.xml: Spieldatei mit Name, Beschreibung, Bild und Video eintragen
        val sysDir = File(cfg.romsDir, p.system)
        val sys = Systems.byId(p.system)
        val game = written.map { File(it) }.firstOrNull { f ->
            f.parentFile == sysDir && sys != null && f.extension.lowercase() in sys.extensions
        } ?: written.map { File(it) }.firstOrNull { it.parentFile == sysDir && !it.name.endsWith(".xml") }
        if (game != null) {
            val base = game.nameWithoutExtension
            val image = written.map { File(it) }.firstOrNull { it.parentFile?.name == "images" && it.parentFile?.parentFile == sysDir && it.nameWithoutExtension.startsWith(base) }
                ?: written.map { File(it) }.firstOrNull { it.parentFile?.name == "images" && it.parentFile?.parentFile == sysDir }
            val video = written.map { File(it) }.firstOrNull { it.parentFile?.name == "videos" && it.parentFile?.parentFile == sysDir }
            try {
                GameList.updateEntry(File(sysDir, "gamelist.xml"), game.name, p.title) { m ->
                    if (m["name"].isNullOrEmpty() || m["name"] == base) m["name"] = p.title
                    if (m["desc"].isNullOrEmpty() && p.info.isNotEmpty()) m["desc"] = p.info
                    if (m["image"].isNullOrEmpty() && image != null) m["image"] = "./images/${image.name}"
                    if (m["video"].isNullOrEmpty() && video != null) m["video"] = "./videos/${video.name}"
                }
            } catch (_: Exception) {}
        }
        val rec = recordFile(cfg, p)
        rec.parentFile?.mkdirs()
        rec.writeText(written.joinToString("\n"))
    }

    /** Entfernt alle Dateien, die das Paket installiert hat. */
    fun uninstall(cfg: Config, p: Package) {
        val rec = recordFile(cfg, p)
        if (!rec.isFile) return
        rec.readLines().filter { it.isNotBlank() }.forEach { File(it).delete() }
        // leere Ordner aufräumen
        rec.readLines().mapNotNull { File(it).parentFile }.distinct().sortedByDescending { it.path.length }.forEach { d ->
            if (d.isDirectory && d.list()?.isEmpty() == true && d.canonicalPath.startsWith(cfg.romsDir.canonicalPath) && d != cfg.romsDir) d.delete()
        }
        rec.delete()
    }

    /** userdata/roms/... → <Basis>/roms/..., userdata/bios/... → <Basis>/bios/..., Rest wird ignoriert */
    private fun mapPath(cfg: Config, name: String): File? {
        val n = name.removePrefix("./")
        val rel = when {
            n.startsWith("userdata/roms/") -> "roms/" + n.removePrefix("userdata/roms/")
            n.startsWith("userdata/bios/") -> "bios/" + n.removePrefix("userdata/bios/")
            else -> return null
        }
        if (rel.contains("..")) return null
        val f = File(cfg.base, rel)
        return if (f.canonicalPath.startsWith(cfg.base.canonicalPath)) f else null
    }
}
