package de.delorean23.station

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.util.LruCache
import com.caverock.androidsvg.SVG
import java.io.File

/**
 * Lädt Grafiken, Farben und Schriften aus einem EmulationStation-Theme im
 * Ordner themes/<name> (z. B. es-theme-carbon). Die Theme-Dateien liegen beim
 * Nutzer und werden nicht mit der App ausgeliefert. Ohne Theme wird ein
 * schlichter Ersatz-Look gezeichnet.
 */
class Theme(val dir: File) {

    val exists = File(dir, "theme.xml").isFile

    private val vars = HashMap<String, String>()
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()

    val baseColor: Int
    val backgroundColor: Int
    val bottomBarColor: Int
    val selectorColor: Int
    val textColor = Color.WHITE
    val infoColor: Int
    val fontBold: Typeface
    val fontRegular: Typeface

    init {
        if (exists) {
            try {
                val xml = File(dir, "theme.xml").readText()
                val block = Regex("<variables>(.*?)</variables>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: ""
                Regex("<([A-Za-z0-9_.]+)>([^<]*)</\\1>").findAll(block).forEach { m ->
                    vars[m.groupValues[1]] = m.groupValues[2].trim()
                }
            } catch (_: Exception) {}
        }
        baseColor = color(vars["baseColor"], 0xFF8B0000.toInt())
        backgroundColor = color(vars["backgroundColor"], 0xFF200000.toInt())
        bottomBarColor = color(vars["bottomBarColor"], 0xC08B0000.toInt())
        selectorColor = color(vars["groupColor"], 0xFFBD4747.toInt())
        infoColor = color(vars["systemInfoColor"], 0xD8DDDDDD.toInt())
        fontBold = font("art/fonts/Cabin-Bold.ttf") ?: Typeface.DEFAULT_BOLD
        fontRegular = font("art/fonts/Cabin-Regular.ttf") ?: Typeface.DEFAULT
    }

    private fun font(rel: String): Typeface? {
        val f = File(dir, rel)
        return if (f.isFile) try { Typeface.createFromFile(f) } catch (e: Exception) { null } else null
    }

    /** Hintergrund des Systemmenüs (art/bg.png bzw. fiber.png) */
    fun background(maxW: Int, maxH: Int): Bitmap? =
        firstImage(listOf("art/bg.png", "art/fiber.png", "art/background.jpg"), maxW, maxH)

    /** Großes System-Bild (art/background/<system>.jpg) */
    fun systemArt(system: String, maxW: Int, maxH: Int): Bitmap? =
        firstImage(listOf("art/background/$system.jpg", "art/background/$system.png"), maxW, maxH)

    fun logo(system: String, maxW: Int, maxH: Int): Bitmap? = firstImage(
        listOf("art/logos/$system.svg", "art/logos/$system.png", "art/logos/$system-w.svg",
            "$system/art/logo.svg", "$system/art/logo.png", "$system/logo.svg", "$system/logo.png"), maxW, maxH)

    fun console(system: String, maxW: Int, maxH: Int): Bitmap? = firstImage(
        listOf("art/consoles/$system.png", "art/consoles/$system.svg", "$system/art/console.png"), maxW, maxH)

    fun controller(system: String, maxW: Int, maxH: Int): Bitmap? = firstImage(
        listOf("art/controllers/$system.svg", "art/controllers/default.svg"), maxW, maxH)

    private fun firstImage(paths: List<String>, maxW: Int, maxH: Int): Bitmap? {
        if (!exists) return null
        for (p in paths) {
            val f = File(dir, p)
            if (f.isFile) return image(f, maxW, maxH) ?: continue
        }
        return null
    }

    fun image(f: File, maxW: Int, maxH: Int): Bitmap? {
        val key = "${f.path}@$maxW x $maxH"
        cache.get(key)?.let { return it }
        if (key in missing) return null
        val bmp = try {
            if (f.name.endsWith(".svg", true)) loadSvg(f, maxW, maxH) else loadBitmap(f, maxW, maxH)
        } catch (e: Throwable) { null }
        if (bmp == null) missing.add(key) else cache.put(key, bmp)
        return bmp
    }

    companion object {

        fun color(hex: String?, fallback: Int): Int {
            if (hex == null) return fallback
            val h = hex.trim().removePrefix("#")
            return try {
                when (h.length) {
                    6 -> (0xFF000000.toInt() or h.toLong(16).toInt())
                    8 -> {
                        val v = h.toLong(16)
                        val rgb = ((v shr 8) and 0xFFFFFF).toInt()
                        val a = (v and 0xFF).toInt()
                        (a shl 24) or rgb
                    }
                    else -> fallback
                }
            } catch (e: Exception) { fallback }
        }

        fun loadBitmap(f: File, maxW: Int, maxH: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0) return null
            var sample = 1
            while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        fun loadSvg(f: File, maxW: Int, maxH: Int): Bitmap? {
            val svg = f.inputStream().use { SVG.getFromInputStream(it) }
            var w = svg.documentWidth
            var h = svg.documentHeight
            val vb = svg.documentViewBox
            if ((w <= 0 || h <= 0) && vb != null) { w = vb.width(); h = vb.height() }
            if (w <= 0 || h <= 0) { w = maxW.toFloat(); h = maxH.toFloat() }
            val scale = minOf(maxW / w, maxH / h)
            val bw = (w * scale).toInt().coerceAtLeast(1)
            val bh = (h * scale).toInt().coerceAtLeast(1)
            if (vb == null) svg.setDocumentViewBox(0f, 0f, w, h)
            svg.setDocumentWidth(bw.toFloat())
            svg.setDocumentHeight(bh.toFloat())
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bmp))
            return bmp
        }
    }
}
