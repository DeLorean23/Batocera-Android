package de.delorean23.station

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Ein System mit seinen Spielen aus der aktuellen Quelle. */
class SystemEntry(val system: GameSystem, val games: List<Game>)

/**
 * Liest Systeme und Spiele aus der aktiven Quelle (intern oder Netzwerk) und
 * bereitet ein Spiel für den Start vor (kopiert es aus dem Netzwerk in roms/<system>).
 */
class Library(private val context: Context, private val cfg: Config) {

    val source: RomSource = if (cfg.isNetwork) SmbSource(cfg) else LocalSource(cfg.romsDir)
    private val coverCache = File(context.cacheDir, "covers")

    fun loadSystems(): List<SystemEntry> {
        val folders = source.systemFolders()
        val out = ArrayList<SystemEntry>()
        for (sys in Systems.installedSystems(context)) {
            if (sys.id !in folders) continue
            val games = loadGames(sys)
            if (games.isNotEmpty()) out.add(SystemEntry(sys, games))
        }
        return out
    }

    private fun loadGames(sys: GameSystem): List<Game> {
        val files = try { source.list(sys.id) } catch (e: Exception) { emptyList() }
        val gamelist: List<Game> = try {
            source.open(sys.id, "gamelist.xml")?.use { GameList.parse(it) } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        val present = HashSet<String>()
        files.forEach { present.add(it.path) }

        val result = LinkedHashMap<String, Game>()
        // Einträge aus der gamelist.xml, deren Datei existiert
        for (g in gamelist) {
            if (g.path in present || g.path.contains('/')) {
                if (g.hidden && !cfg.showHidden) continue
                result[g.path] = g
            }
        }
        // Dateien ohne gamelist-Eintrag
        val skipDirs = setOf("images", "videos", "manuals", "media", "downloaded_images", "downloaded_videos")
        val hiddenByPlaylist = HashSet<String>()
        files.filter { it.path.endsWith(".m3u", true) || it.path.endsWith(".cue", true) || it.path.endsWith(".gdi", true) }
            .forEach { /* Einzelne Disc-Dateien werden ausgeblendet, wenn eine m3u/cue sie listet */ }
        for (f in files) {
            val ext = f.path.substringAfterLast('.', "").lowercase()
            if (f.isDir) {
                if (f.path in skipDirs) continue
                // Ordner-Spiele (z. B. "Spiel.pc" bei DOS)
                if (ext.isNotEmpty() && ext in sys.extensions && !result.containsKey(f.path)) {
                    result[f.path] = Game(f.path, f.path.substringBeforeLast('.'))
                }
                continue
            }
            if (ext !in sys.extensions) continue
            if (f.path in hiddenByPlaylist) continue
            if (!result.containsKey(f.path)) {
                result[f.path] = Game(f.path, f.path.substringBeforeLast('.'))
            }
        }
        // .bin neben .cue nicht doppelt anzeigen
        val cueNames = result.keys.filter { it.endsWith(".cue", true) }.map { it.substringBeforeLast('.').lowercase() }.toSet()
        val m3uNames = result.keys.filter { it.endsWith(".m3u", true) }.map { it.substringBeforeLast('.').lowercase() }.toSet()
        return result.values.filter { g ->
            val base = g.path.substringBeforeLast('.').lowercase()
            val ext = g.path.substringAfterLast('.', "").lowercase()
            when {
                ext == "bin" && base in cueNames && sys.id in cdSystems -> false
                ext in setOf("cue", "chd", "iso") && m3uNames.any { base.startsWith(it) && base != it } -> false
                else -> true
            }
        }.sortedBy { it.name.lowercase() }
    }

    /** Liefert eine lokale Bilddatei (Cover), aus dem Netzwerk wird sie zwischengespeichert. */
    fun coverFile(sys: GameSystem, rel: String): File? {
        if (rel.isEmpty()) return null
        if (!source.isNetwork) {
            val f = File(File(cfg.romsDir, sys.id), rel)
            return if (f.isFile) f else null
        }
        // Bereits lokal vorhanden (Spiel wurde schon einmal geladen)?
        val local = File(File(cfg.romsDir, sys.id), rel)
        if (local.isFile) return local
        val cached = File(File(coverCache, sys.id), rel.replace('/', '_'))
        if (cached.isFile && cached.length() > 0) return cached
        return try {
            source.open(sys.id, rel)?.use { input ->
                cached.parentFile?.mkdirs()
                val tmp = File(cached.path + ".part")
                FileOutputStream(tmp).use { input.copyTo(it, 64 * 1024) }
                tmp.renameTo(cached)
                cached
            }
        } catch (e: Exception) { null }
    }

    /**
     * Bereitet das Spiel vor und gibt den lokalen Pfad zurück.
     * Netzwerk: kopiert ROM (+ zugehörige Disc-Dateien), Bilder und den
     * gamelist-Eintrag in den lokalen Ordner roms/<system>.
     */
    fun prepare(sys: GameSystem, game: Game, progress: (String, Long, Long) -> Unit): File {
        val localDir = File(cfg.romsDir, sys.id)
        localDir.mkdirs()
        val target = File(localDir, game.path)

        if (source.isNetwork) {
            val needed = LinkedHashSet<String>()
            collectFiles(sys, game.path, needed)
            for (p in needed) {
                val dst = File(localDir, p)
                val remoteSize = source.size(sys.id, p)
                if (dst.exists() && (remoteSize < 0 || dst.length() == remoteSize)) continue
                copyRemote(sys, p, dst, remoteSize, progress)
            }
            // Bilder mitnehmen
            listOf(game.image, game.thumbnail, game.marquee).filter { it.isNotEmpty() }.forEach { rel ->
                val dst = File(localDir, rel)
                if (!dst.exists()) {
                    try { copyRemote(sys, rel, dst, source.size(sys.id, rel)) { _, _, _ -> } } catch (_: Exception) {}
                }
            }
            // gamelist.xml lokal ergänzen
            if (game.raw.isNotEmpty()) {
                try { GameList.mergeInto(File(localDir, "gamelist.xml"), game) } catch (_: Exception) {}
            }
        }

        // ZIP entpacken, wenn der Core das nicht selbst kann
        if (game.path.endsWith(".zip", true) && !sys.zipNative) {
            return extractZip(target, sys)
        }
        return target
    }

    /** Sammelt ROM-Datei und alle Dateien, die eine .cue/.m3u/.gdi/.ccd braucht. */
    private fun collectFiles(sys: GameSystem, path: String, out: MutableSet<String>) {
        if (!out.add(path)) return
        val dir = if (path.contains('/')) path.substringBeforeLast('/') + "/" else ""
        val ext = path.substringAfterLast('.', "").lowercase()
        when (ext) {
            "cue", "m3u", "gdi", "toc" -> {
                val text = try {
                    source.open(sys.id, path)?.use { it.readBytes().toString(Charsets.ISO_8859_1) } ?: ""
                } catch (e: Exception) { "" }
                for (line in text.lines()) {
                    val ref = when (ext) {
                        "cue", "toc" -> Regex("FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
                            ?: Regex("FILE\\s+(\\S+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
                        "m3u" -> line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
                        "gdi" -> {
                            val q = Regex("\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                            q ?: line.trim().split(Regex("\\s+")).getOrNull(4)
                        }
                        else -> null
                    } ?: continue
                    collectFiles(sys, dir + ref.replace('\\', '/'), out)
                }
            }
            "ccd" -> {
                val base = path.substringBeforeLast('.')
                listOf("img", "sub").forEach { e -> if (source.exists(sys.id, "$base.$e")) out.add("$base.$e") }
            }
        }
        // Ordner-Spiel: kompletten Ordner kopieren
        try {
            val entries = source.list(sys.id, path)
            if (entries.isNotEmpty()) {
                out.remove(path)
                entries.forEach { e -> if (e.isDir) collectFiles(sys, e.path, out) else out.add(e.path) }
            }
        } catch (_: Exception) {}
    }

    private fun copyRemote(sys: GameSystem, rel: String, dst: File, size: Long, progress: (String, Long, Long) -> Unit) {
        val input: InputStream = source.open(sys.id, rel) ?: throw IllegalStateException("Datei nicht gefunden: $rel")
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".part")
        input.use { inp ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 512 * 1024) { progress(rel, done, size); last = done }
                }
                progress(rel, done, size)
            }
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("Konnte $rel nicht speichern")
    }

    private fun extractZip(zip: File, sys: GameSystem): File {
        val outDir = File(context.cacheDir, "unzip/${sys.id}/${zip.nameWithoutExtension}")
        val marker = File(outDir, ".from-${zip.length()}")
        if (!marker.exists()) {
            outDir.deleteRecursively(); outDir.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val f = File(outDir, e.name.substringAfterLast('/'))
                    if (!f.canonicalPath.startsWith(outDir.canonicalPath)) continue
                    FileOutputStream(f).use { zin.copyTo(it) }
                }
            }
            marker.createNewFile()
        }
        val files = outDir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        return files.firstOrNull { it.extension.lowercase() in sys.extensions && it.extension.lowercase() != "zip" }
            ?: files.maxByOrNull { it.length() }
            ?: zip
    }

    fun close() = source.close()

    companion object {
        val cdSystems = setOf("psx", "megacd", "saturn", "pcenginecd", "pcfx", "neogeocd", "3do", "dreamcast", "jaguar")
    }
}
