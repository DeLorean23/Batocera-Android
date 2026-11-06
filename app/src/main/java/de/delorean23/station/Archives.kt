package de.delorean23.station

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Entpackt ZIP/7z-Spiele in einen Zwischenordner (batocera/cache/entpackt),
 * so wie RetroArch bei Batocera. Die Original-Archive bleiben unverändert.
 * Bereits entpackte Spiele werden wiederverwendet; über 4 GB werden die am
 * längsten nicht gespielten gelöscht.
 */
object Archives {

    const val LIMIT_BYTES = 4L * 1024 * 1024 * 1024

    /** Reihenfolge bei mehreren Dateien: Spiel-Listen und Disc-Beschreibungen zuerst */
    private val preferred = listOf("m3u", "cue", "gdi", "ccd", "toc", "chd", "iso", "cdi", "pbp", "cso")

    fun extract(archive: File, sys: GameSystem, cacheRoot: File, progress: (String, Long, Long) -> Unit): File {
        val outDir = File(File(cacheRoot, sys.id), archive.name)
        val marker = File(outDir, ".fertig-${archive.length()}")
        if (!marker.exists()) {
            outDir.deleteRecursively()
            outDir.mkdirs()
            trim(cacheRoot, keep = outDir, incoming = archive.length() * 2)
            val total = archive.length()
            if (archive.extension.equals("7z", true)) extract7z(archive, outDir, total, progress)
            else extractZip(archive, outDir, total, progress)
            marker.createNewFile()
        }
        marker.setLastModified(System.currentTimeMillis())
        outDir.setLastModified(System.currentTimeMillis())
        return pick(outDir, sys) ?: throw IllegalStateException("Im Archiv ${archive.name} wurde keine passende Spieldatei gefunden.")
    }

    private fun safeTarget(outDir: File, name: String): File? {
        val f = File(outDir, name.replace('\\', '/'))
        return if (f.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) f else null
    }

    private fun extractZip(zip: File, outDir: File, total: Long, progress: (String, Long, Long) -> Unit) {
        var done = 0L
        ZipInputStream(zip.inputStream().buffered(256 * 1024)).use { zin ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val e = zin.nextEntry ?: break
                val f = safeTarget(outDir, e.name) ?: continue
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { out ->
                    while (true) {
                        val n = zin.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                    }
                }
                progress("Entpacke", done.coerceAtMost(total), total)
            }
        }
    }

    private fun extract7z(file: File, outDir: File, total: Long, progress: (String, Long, Long) -> Unit) {
        var done = 0L
        @Suppress("DEPRECATION")
        SevenZFile(file).use { z ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val e = z.nextEntry ?: break
                val f = safeTarget(outDir, e.name) ?: continue
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { out ->
                    while (true) {
                        val n = z.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                    }
                }
                progress("Entpacke", done.coerceAtMost(total), total)
            }
        }
    }

    /** Wählt die Datei, die an den Emulator geht. */
    private fun pick(dir: File, sys: GameSystem): File? {
        val files = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList()
        if (files.isEmpty()) return null
        for (ext in preferred) {
            if (ext in sys.extensions) files.filter { it.extension.equals(ext, true) }.minByOrNull { it.name }?.let { return it }
        }
        val playable = files.filter { val e = it.extension.lowercase(); e in sys.extensions && e != "zip" && e != "7z" }
        return playable.maxByOrNull { it.length() } ?: files.maxByOrNull { it.length() }
    }

    fun size(cacheRoot: File): Long = cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear(cacheRoot: File) { cacheRoot.deleteRecursively() }

    /** Löscht die ältesten entpackten Spiele, bis Platz für das neue ist. */
    private fun trim(cacheRoot: File, keep: File, incoming: Long) {
        val games = cacheRoot.listFiles()?.flatMap { s -> s.listFiles()?.toList() ?: emptyList() }
            ?.filter { it.isDirectory && it != keep }?.sortedBy { it.lastModified() } ?: return
        var used = games.sumOf { g -> g.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (g in games) {
            if (used + incoming <= LIMIT_BYTES) break
            val sz = g.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            g.deleteRecursively()
            used -= sz
        }
    }
}
