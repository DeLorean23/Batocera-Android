package de.delorean23.station

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Oberfläche im Stil des Carbon-Themes.
 * Systemansichten: horizontal, vertical, wheel, simple
 * Spieleansichten: detailed, basic, tiles, icons
 */
class FrontendView(context: Context) : View(context) {

    interface Listener {
        fun onLaunch(entry: SystemEntry, game: Game)
        fun onMenu()
        fun onSystemChanged(entry: SystemEntry)
        fun onGameOptions(entry: SystemEntry, game: Game)
        fun onSelectionChanged(entry: SystemEntry?, game: Game?)
    }

    var listener: Listener? = null
    var theme: Theme = Theme(File("/"))
        set(v) { field = v; bgBitmap = null; invalidate() }
    var library: Library? = null
    var statusText: String = ""
        set(v) { field = v; invalidate() }
    var systemView = "horizontal"
        set(v) { field = v; invalidate() }
    var swapAB = false
        set(v) { field = v; invalidate() }
    var gameView = "detailed"
        set(v) { field = v; listScroll = 0f; invalidate() }

    private var systems: List<SystemEntry> = emptyList()
    var inGameList = false
        private set

    private var sysIndex = 0
    private var sysScroll = 0f
    private var sysAnimFrom = 0f
    private var sysAnimStart = 0L

    private var gameIndex = 0
    private var listScroll = 0f
    private val scroller = OverScroller(context)

    private val loader = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val covers = object : android.util.LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = HashSet<String>()
    private var bgBitmap: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val src = Rect()
    private val density = resources.displayMetrics.density

    /** Bereich, in dem die Video-Vorschau gezeigt wird (null = keiner) */
    val videoRect = RectF()
    var videoActive = false

    fun setSystems(list: List<SystemEntry>, select: String?, keepGame: Boolean = false) {
        val prevGame = currentGame()
        systems = list
        val i = list.indexOfFirst { it.system.id == select }
        sysIndex = if (i >= 0) i else 0
        sysScroll = sysIndex.toFloat()
        if (inGameList && list.isEmpty()) inGameList = false
        val games = current()?.games ?: emptyList()
        gameIndex = if (keepGame && prevGame != null) games.indexOfFirst { it.path == prevGame.path && it.system == prevGame.system }.coerceAtLeast(0)
        else gameIndex.coerceIn(0, (games.size - 1).coerceAtLeast(0))
        if (!keepGame) inGameList = inGameList && list.isNotEmpty()
        notifySelection()
        invalidate()
    }

    fun current(): SystemEntry? = systems.getOrNull(sysIndex)
    fun currentGame(): Game? = if (inGameList) current()?.games?.getOrNull(gameIndex) else null

    /** Zeigt eine Liste (z. B. Suchergebnis) direkt als Spieleliste */
    fun showEntry(entry: SystemEntry) {
        val list = systems.filter { it.system.id != Library.SEARCH.id }.toMutableList()
        list.add(0, entry)
        systems = list
        sysIndex = 0; sysScroll = 0f
        inGameList = true; gameIndex = 0; listScroll = 0f
        notifySelection(); invalidate()
    }

    // ---------------------------------------------------------------- Navigation

    private fun isGrid() = gameView == "tiles" || gameView == "icons"
    private fun gridCols() = if (gameView == "tiles") 5 else 7

    fun moveHorizontal(d: Int) {
        if (systems.isEmpty()) return
        if (inGameList) {
            if (isGrid()) moveGame(d, wrap = true) else moveGame(d * visibleRows(), wrap = false)
            return
        }
        setSystem((sysIndex + d + systems.size) % systems.size)
    }

    fun moveVertical(d: Int) {
        if (!inGameList) { moveHorizontal(d); return }
        if (isGrid() && abs(d) == 1) moveGame(d * gridCols(), wrap = false) else moveGame(d, wrap = abs(d) == 1)
    }

    private fun moveGame(d: Int, wrap: Boolean) {
        val n = current()?.games?.size ?: return
        if (n == 0) return
        gameIndex = if (wrap) ((gameIndex + d) % n + n) % n else (gameIndex + d).coerceIn(0, n - 1)
        ensureVisible(); notifySelection(); invalidate()
    }

    fun confirm() {
        val e = current() ?: return
        if (!inGameList) openGameList()
        else e.games.getOrNull(gameIndex)?.let { listener?.onLaunch(e, it) }
    }

    fun options() {
        val e = current() ?: return
        val g = currentGame() ?: return
        listener?.onGameOptions(e, g)
    }

    fun back(): Boolean {
        if (inGameList) {
            inGameList = false
            if (current()?.system?.id == Library.SEARCH.id) {
                systems = systems.drop(1); sysIndex = 0; sysScroll = 0f
            }
            notifySelection(); invalidate()
            return true
        }
        return false
    }

    fun jumpLetter(d: Int) {
        val games = current()?.games ?: return
        if (!inGameList || games.isEmpty()) return
        val c = games[gameIndex].name.firstOrNull()?.uppercaseChar() ?: return
        var i = gameIndex
        repeat(games.size) {
            i = (i + d + games.size) % games.size
            if (games[i].name.firstOrNull()?.uppercaseChar() != c) {
                if (d < 0) {
                    // an den Anfang dieses Buchstabens
                    val c2 = games[i].name.firstOrNull()?.uppercaseChar()
                    while (i > 0 && games[i - 1].name.firstOrNull()?.uppercaseChar() == c2) i--
                }
                gameIndex = i; ensureVisible(); notifySelection(); invalidate(); return
            }
        }
    }

    private fun openGameList() {
        if (current() == null) return
        inGameList = true; gameIndex = 0; listScroll = 0f
        notifySelection(); invalidate()
    }

    private fun setSystem(i: Int) {
        if (i == sysIndex) return
        sysAnimFrom = sysScroll
        sysAnimStart = System.currentTimeMillis()
        sysIndex = i; gameIndex = 0
        current()?.let { listener?.onSystemChanged(it) }
        invalidate()
    }

    private fun notifySelection() = listener?.onSelectionChanged(current(), currentGame())

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        drawBackground(canvas, w, h)
        videoRect.setEmpty()
        when {
            systems.isEmpty() -> drawEmpty(canvas, w, h)
            inGameList -> when (gameView) {
                "basic" -> drawGameList(canvas, w, h, details = false)
                "tiles", "icons" -> drawGameGrid(canvas, w, h)
                else -> drawGameList(canvas, w, h, details = true)
            }
            else -> when (systemView) {
                "vertical" -> drawSystemVertical(canvas, w, h)
                "wheel" -> drawSystemWheel(canvas, w, h)
                "simple" -> drawSystemSimple(canvas, w, h)
                else -> drawSystemHorizontal(canvas, w, h)
            }
        }
        drawBottomBar(canvas, w, h)
        if (statusText.isNotEmpty()) drawStatus(canvas, w, h)
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float) {
        c.drawColor(theme.backgroundColor)
        if (bgBitmap == null) bgBitmap = theme.background(width, height)
        bgBitmap?.let { b ->
            paint.alpha = 255
            drawCover(c, b, RectF(0f, 0f, w, h))
            paint.color = theme.backgroundColor; paint.alpha = 170
            c.drawRect(0f, 0f, w, h, paint)
            paint.alpha = 255
        }
    }

    private fun drawEmpty(c: Canvas, w: Float, h: Float) {
        text.typeface = theme.fontBold; text.color = Color.WHITE; text.textSize = h * 0.05f
        text.textAlign = Paint.Align.CENTER
        c.drawText("Keine Spiele gefunden", w / 2, h * 0.42f, text)
        text.typeface = theme.fontRegular; text.textSize = h * 0.03f; text.color = theme.infoColor
        val hint = if (library?.source?.isNetwork == true) "Netzwerk: Freigabe und Ordner im Menü prüfen"
        else "ROMs in batocera/roms/<system> ablegen"
        c.drawText(hint, w / 2, h * 0.50f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun animateSystem(): Float {
        val t = ((System.currentTimeMillis() - sysAnimStart) / 220f).coerceIn(0f, 1f)
        val target = sysIndex.toFloat()
        var from = sysAnimFrom
        if (systems.size > 2) {
            if (target - from > systems.size / 2f) from += systems.size
            if (from - target > systems.size / 2f) from -= systems.size
        }
        sysScroll = if (t >= 1f) target else from + (target - from) * ease(t)
        if (t < 1f) postInvalidateOnAnimation()
        return sysScroll
    }

    private fun systemHeader(c: Canvas, entry: SystemEntry, x: Float, y: Float, h: Float, align: Paint.Align = Paint.Align.LEFT) {
        text.textAlign = align
        text.typeface = theme.fontBold; text.color = Color.WHITE; text.textSize = h * 0.07f
        c.drawText(entry.system.manufacturer, x, y, text)
        text.typeface = theme.fontRegular; text.textSize = h * 0.032f; text.color = theme.infoColor
        val sub = if (entry.collection) entry.system.name else "${entry.system.name}  ·  ${entry.system.year}"
        c.drawText(sub, x, y + h * 0.06f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun gameCount(entry: SystemEntry): String {
        val n = entry.games.size
        return if (n == 1) "1 Spiel verfügbar" else "$n Spiele verfügbar"
    }

    private fun drawLogoOrName(c: Canvas, sys: GameSystem, box: RectF, alpha: Int) {
        val logo = theme.logo(sys.id, box.width().toInt().coerceAtLeast(1), box.height().toInt().coerceAtLeast(1))
        if (logo != null) {
            paint.alpha = alpha; drawFit(c, logo, box); paint.alpha = 255
        } else {
            text.typeface = theme.fontBold; text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE; text.alpha = alpha
            text.textSize = box.height() * 0.32f
            val label = TextUtils.ellipsize(sys.name, text, box.width(), TextUtils.TruncateAt.END).toString()
            c.drawText(label, box.centerX(), box.centerY() + text.textSize / 3, text)
            text.alpha = 255; text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawSystemHorizontal(c: Canvas, w: Float, h: Float) {
        animateSystem()
        val entry = current() ?: return
        theme.systemArt(entry.system.id, width, height)?.let { art ->
            paint.alpha = 90; drawCover(c, art, RectF(0f, 0f, w, h)); paint.alpha = 255
        }
        systemHeader(c, entry, w * 0.03f, h * 0.11f, h)

        val bandTop = h * 0.36f; val bandH = h * 0.30f
        paint.color = 0x99000000.toInt()
        c.drawRect(0f, bandTop, w, bandTop + bandH, paint)
        paint.shader = LinearGradient(0f, bandTop, 0f, bandTop + bandH,
            intArrayOf(theme.baseColor and 0x00FFFFFF or 0x30000000, Color.TRANSPARENT, theme.baseColor and 0x00FFFFFF or 0x30000000),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, bandTop, w, bandTop + bandH, paint)
        paint.shader = null

        val spacing = w * 0.26f
        val cy = bandTop + bandH / 2
        val n = systems.size
        for (k in -3..3) {
            if (n < 2 && k != 0) continue
            val idx = (((sysIndex + k) % n) + n) % n
            val offset = (sysIndex + k) - sysScroll
            val x = w / 2 + offset * spacing
            if (x < -spacing || x > w + spacing) continue
            val dist = abs(offset).coerceAtMost(1f)
            val scale = 1f - 0.35f * dist
            val lw = spacing * 0.85f * scale; val lh = bandH * 0.62f * scale
            drawLogoOrName(c, systems[idx].system, RectF(x - lw / 2, cy - lh / 2, x + lw / 2, cy + lh / 2), (255 * (1f - 0.55f * dist)).roundToInt())
        }
        theme.console(entry.system.id, (w * 0.3f).toInt(), (h * 0.22f).toInt())?.let {
            drawFit(c, it, RectF(w * 0.66f, h * 0.68f, w * 0.97f, h * 0.90f))
        }
        text.typeface = theme.fontRegular; text.color = theme.infoColor; text.textSize = h * 0.035f
        text.textAlign = Paint.Align.CENTER
        c.drawText(gameCount(entry), w / 2, bandTop + bandH + h * 0.07f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawSystemVertical(c: Canvas, w: Float, h: Float) {
        animateSystem()
        val entry = current() ?: return
        theme.systemArt(entry.system.id, width, height)?.let { art ->
            paint.alpha = 120; drawCover(c, art, RectF(w * 0.4f, 0f, w, h)); paint.alpha = 255
        }
        // Logos senkrecht links
        paint.color = 0xAA000000.toInt()
        c.drawRect(0f, 0f, w * 0.38f, h, paint)
        val spacing = h * 0.2f
        val n = systems.size
        for (k in -3..3) {
            if (n < 2 && k != 0) continue
            val idx = (((sysIndex + k) % n) + n) % n
            val offset = (sysIndex + k) - sysScroll
            val y = h * 0.47f + offset * spacing
            if (y < -spacing || y > h + spacing) continue
            val dist = abs(offset).coerceAtMost(1f)
            val scale = 1f - 0.3f * dist
            val lw = w * 0.3f * scale; val lh = spacing * 0.75f * scale
            if (k == 0) {
                paint.color = theme.selectorColor and 0x00FFFFFF or 0x50000000
                c.drawRect(0f, y - spacing / 2, w * 0.38f, y + spacing / 2, paint)
            }
            drawLogoOrName(c, systems[idx].system, RectF(w * 0.19f - lw / 2, y - lh / 2, w * 0.19f + lw / 2, y + lh / 2), (255 * (1f - 0.5f * dist)).roundToInt())
        }
        systemHeader(c, entry, w * 0.42f, h * 0.13f, h)
        theme.console(entry.system.id, (w * 0.45f).toInt(), (h * 0.45f).toInt())?.let {
            drawFit(c, it, RectF(w * 0.45f, h * 0.30f, w * 0.95f, h * 0.78f))
        }
        text.typeface = theme.fontRegular; text.color = theme.infoColor; text.textSize = h * 0.035f
        c.drawText(gameCount(entry), w * 0.42f, h * 0.86f, text)
    }

    private fun drawSystemWheel(c: Canvas, w: Float, h: Float) {
        animateSystem()
        val entry = current() ?: return
        theme.systemArt(entry.system.id, width, height)?.let { art ->
            paint.alpha = 130; drawCover(c, art, RectF(0f, 0f, w, h)); paint.alpha = 255
        }
        paint.shader = LinearGradient(w * 0.35f, 0f, w, 0f, Color.TRANSPARENT, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint); paint.shader = null
        // Logos auf einem Bogen rechts
        val cx = w * 1.25f; val cy = h * 0.47f; val radius = w * 0.5f
        val n = systems.size
        for (k in -4..4) {
            if (n < 2 && k != 0) continue
            val idx = (((sysIndex + k) % n) + n) % n
            val offset = (sysIndex + k) - sysScroll
            val ang = Math.PI + offset * 0.28
            val x = cx + (cos(ang) * radius).toFloat()
            val y = cy + (sin(ang) * radius).toFloat() * -1f
            val dist = abs(offset).coerceAtMost(1.5f)
            val scale = 1f - 0.3f * dist.coerceAtMost(1f)
            val lw = w * 0.22f * scale; val lh = h * 0.13f * scale
            drawLogoOrName(c, systems[idx].system, RectF(x - lw / 2, y - lh / 2, x + lw / 2, y + lh / 2), (255 * (1f - 0.45f * dist.coerceAtMost(1f))).roundToInt())
        }
        systemHeader(c, entry, w * 0.03f, h * 0.11f, h)
        text.typeface = theme.fontRegular; text.color = theme.infoColor; text.textSize = h * 0.035f
        c.drawText(gameCount(entry), w * 0.03f, h * 0.88f, text)
    }

    private fun drawSystemSimple(c: Canvas, w: Float, h: Float) {
        animateSystem()
        val entry = current() ?: return
        systemHeader(c, entry, w / 2, h * 0.12f, h, Paint.Align.CENTER)
        val rowH = h * 0.075f
        val top = h * 0.5f - sysScroll * rowH
        text.typeface = theme.fontRegular; text.textSize = rowH * 0.55f; text.textAlign = Paint.Align.CENTER
        c.save(); c.clipRect(0f, h * 0.22f, w, h * 0.9f)
        systems.forEachIndexed { i, e ->
            val y = top + i * rowH
            if (y < h * 0.15f || y > h * 0.95f) return@forEachIndexed
            if (i == sysIndex) {
                paint.color = theme.selectorColor
                rect.set(w * 0.25f, y - rowH * 0.5f, w * 0.75f, y + rowH * 0.5f)
                c.drawRoundRect(rect, rowH * 0.2f, rowH * 0.2f, paint)
            }
            text.color = if (i == sysIndex) Color.WHITE else 0xFFBBBBBB.toInt()
            c.drawText("${e.system.name}  (${e.games.size})", w / 2, y + rowH * 0.2f, text)
        }
        c.restore(); text.textAlign = Paint.Align.LEFT
    }

    private fun drawHeader(c: Canvas, entry: SystemEntry, w: Float, h: Float): Float {
        val headH = h * 0.14f
        paint.color = 0x80000000.toInt()
        c.drawRect(0f, 0f, w, headH, paint)
        val logo = if (entry.system.id == Library.SEARCH.id) null
        else theme.logo(entry.system.id, (w * 0.3f).toInt(), (headH * 0.75f).toInt())
        if (logo != null) drawFit(c, logo, RectF(w * 0.35f, headH * 0.12f, w * 0.65f, headH * 0.88f))
        else {
            text.typeface = theme.fontBold; text.color = Color.WHITE; text.textSize = headH * 0.4f
            text.textAlign = Paint.Align.CENTER
            c.drawText(entry.system.name, w / 2, headH * 0.65f, text)
            text.textAlign = Paint.Align.LEFT
        }
        return headH
    }

    private fun drawGameList(c: Canvas, w: Float, h: Float, details: Boolean) {
        val entry = current() ?: return
        val games = entry.games
        val headH = drawHeader(c, entry, w, h)

        val listL = w * 0.03f; val listR = if (details) w * 0.50f else w * 0.97f
        val listT = headH + h * 0.03f; val listB = h * 0.90f
        val rowH = rowHeight()
        if (scroller.computeScrollOffset()) { listScroll = scroller.currY.toFloat(); postInvalidateOnAnimation() }
        val maxScroll = (games.size * rowH - (listB - listT)).coerceAtLeast(0f)
        listScroll = listScroll.coerceIn(0f, maxScroll)

        c.save(); c.clipRect(listL, listT, listR, listB)
        val first = (listScroll / rowH).toInt().coerceAtLeast(0)
        val last = ((listScroll + (listB - listT)) / rowH).toInt().coerceAtMost(games.size - 1)
        text.typeface = theme.fontRegular; text.textSize = rowH * 0.55f
        for (i in first..last) {
            val top = listT + i * rowH - listScroll
            if (i == gameIndex) {
                paint.color = theme.selectorColor
                rect.set(listL, top, listR, top + rowH)
                c.drawRoundRect(rect, rowH * 0.15f, rowH * 0.15f, paint)
            }
            val g = games[i]
            text.color = if (i == gameIndex) Color.WHITE else 0xFFDDDDDD.toInt()
            val label = (if (g.favorite) "★ " else "") + g.name + if (entry.collection || entry.system.id == Library.SEARCH.id) "  [${g.system}]" else ""
            c.drawText(TextUtils.ellipsize(label, text, listR - listL - rowH * 0.6f, TextUtils.TruncateAt.END).toString(),
                listL + rowH * 0.3f, top + rowH * 0.70f, text)
        }
        c.restore()
        if (maxScroll > 0) {
            val frac = (listB - listT) / (games.size * rowH)
            val barH = (listB - listT) * frac
            val barT = listT + (listScroll / maxScroll) * ((listB - listT) - barH)
            paint.color = 0x60FFFFFF
            c.drawRect(listR + w * 0.004f, barT, listR + w * 0.008f, barT + barH, paint)
        }
        if (details) games.getOrNull(gameIndex)?.let { drawDetails(c, entry, it, w * 0.53f, w * 0.97f, listT, h) }
    }

    private fun drawDetails(c: Canvas, entry: SystemEntry, g: Game, dL: Float, dR: Float, top: Float, h: Float) {
        val coverRect = RectF(dL, top, dR, top + h * 0.40f)
        videoRect.set(coverRect)
        if (!videoActive) {
            val cover = coverFor(g, (dR - dL).toInt(), coverRect.height().toInt())
            if (cover != null) drawFit(c, cover, coverRect)
            else {
                paint.color = 0x40000000
                c.drawRoundRect(coverRect, 12f, 12f, paint)
                text.color = 0x80FFFFFF.toInt(); text.textSize = h * 0.03f; text.textAlign = Paint.Align.CENTER
                c.drawText("Kein Bild", coverRect.centerX(), coverRect.centerY(), text)
                text.textAlign = Paint.Align.LEFT
            }
        }
        var y = coverRect.bottom + h * 0.05f
        text.typeface = theme.fontRegular; text.textSize = h * 0.028f
        val meta = listOf(
            "Bewertung" to stars(g.rating),
            "Erschienen" to g.year,
            "Entwickler" to g.developer,
            "Publisher" to g.publisher,
            "Genre" to g.genre,
            "Spieler" to g.players,
            "Gestartet" to if (g.playcount > 0) "${g.playcount}×" else "",
            "Spielzeit" to playTime(g.gametime)
        ).filter { it.second.isNotEmpty() }
        val colW = (dR - dL) / 2
        meta.forEachIndexed { i, (k, v) ->
            val x = dL + (i % 2) * colW
            val yy = y + (i / 2) * h * 0.038f
            text.color = theme.selectorColor
            c.drawText("$k: ", x, yy, text)
            val kw = text.measureText("$k: ")
            text.color = Color.WHITE
            c.drawText(TextUtils.ellipsize(v, text, colW - kw - 8, TextUtils.TruncateAt.END).toString(), x + kw, yy, text)
        }
        y += ((meta.size + 1) / 2) * h * 0.038f + h * 0.01f
        if (g.desc.isNotEmpty() && y < h * 0.88f) {
            text.color = 0xFFE0E0E0.toInt(); text.textSize = h * 0.026f
            val layout = buildLayout(g.desc, text, (dR - dL).toInt())
            c.save(); c.clipRect(dL, y, dR, h * 0.90f); c.translate(dL, y)
            layout.draw(c)
            c.restore()
        }
    }

    private fun drawGameGrid(c: Canvas, w: Float, h: Float) {
        val entry = current() ?: return
        val games = entry.games
        val headH = drawHeader(c, entry, w, h)
        val cols = gridCols()
        val withNames = gameView == "tiles"
        val gl = w * 0.03f; val gr = w * 0.97f
        val gt = headH + h * 0.06f; val gb = h * 0.90f
        val cellW = (gr - gl) / cols
        val cellH = cellW * if (withNames) 1.15f else 0.95f
        if (scroller.computeScrollOffset()) { listScroll = scroller.currY.toFloat(); postInvalidateOnAnimation() }
        val rows = (games.size + cols - 1) / cols
        val maxScroll = (rows * cellH - (gb - gt)).coerceAtLeast(0f)
        listScroll = listScroll.coerceIn(0f, maxScroll)

        // Name des gewählten Spiels oben (Icons)
        if (!withNames) games.getOrNull(gameIndex)?.let {
            text.typeface = theme.fontBold; text.textSize = h * 0.035f; text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
            c.drawText(TextUtils.ellipsize(it.name, text, w * 0.9f, TextUtils.TruncateAt.END).toString(), w / 2, headH + h * 0.045f, text)
            text.textAlign = Paint.Align.LEFT
        }
        c.save(); c.clipRect(gl - 8, gt - 8, gr + 8, gb)
        val firstRow = (listScroll / cellH).toInt().coerceAtLeast(0)
        val lastRow = ((listScroll + (gb - gt)) / cellH).toInt() + 1
        for (r in firstRow..lastRow) for (col in 0 until cols) {
            val i = r * cols + col
            if (i >= games.size) break
            val g = games[i]
            val x = gl + col * cellW; val y = gt + r * cellH - listScroll
            val pad = cellW * 0.06f
            val box = RectF(x + pad, y + pad, x + cellW - pad, y + (if (withNames) cellH * 0.78f else cellH) - pad)
            if (i == gameIndex) {
                paint.color = theme.selectorColor
                c.drawRoundRect(RectF(x + 2, y + 2, x + cellW - 2, y + cellH - 2), 14f, 14f, paint)
            }
            val cover = coverFor(g, box.width().toInt(), box.height().toInt())
            if (cover != null) drawFit(c, cover, box) else {
                paint.color = 0x50000000; c.drawRoundRect(box, 10f, 10f, paint)
                text.textSize = cellW * 0.09f; text.color = 0xCCFFFFFF.toInt(); text.textAlign = Paint.Align.CENTER
                val lay = buildLayout(g.name, text, box.width().toInt())
                c.save(); c.clipRect(box); c.translate(box.left, box.top + 6); lay.draw(c); c.restore()
                text.textAlign = Paint.Align.LEFT
            }
            if (withNames) {
                text.typeface = theme.fontRegular; text.textSize = cellW * 0.085f
                text.color = if (i == gameIndex) Color.WHITE else 0xFFDDDDDD.toInt()
                text.textAlign = Paint.Align.CENTER
                c.drawText(TextUtils.ellipsize(g.name, text, cellW - pad * 2, TextUtils.TruncateAt.END).toString(), x + cellW / 2, y + cellH * 0.92f, text)
                text.textAlign = Paint.Align.LEFT
            }
        }
        c.restore()
    }

    private fun drawBottomBar(c: Canvas, w: Float, h: Float) {
        val barH = h * 0.065f
        paint.color = theme.bottomBarColor
        c.drawRect(0f, h - barH, w, h, paint)
        text.typeface = theme.fontRegular; text.color = Color.WHITE; text.textSize = barH * 0.42f
        val y = h - barH * 0.33f
        val ok = if (swapAB) "B" else "A"
        val no = if (swapAB) "A" else "B"
        val items = if (inGameList)
            listOf(ok to "Starten", no to "Zurück", "X" to "Suche", "Y" to "Favorit", "SELECT" to "Optionen", "START" to "Menü")
        else listOf(ok to "Auswählen", "◀ ▶" to "System", "X" to "Suche", "START" to "Menü")
        // mittig ausrichten
        var total = 0f
        for ((key, label) in items) total += text.measureText(key) + barH * 0.6f + text.measureText(label) + barH * 0.8f
        var x = ((w - total) / 2).coerceAtLeast(w * 0.02f)
        for ((key, label) in items) {
            paint.color = 0x50FFFFFF
            val kw = text.measureText(key) + barH * 0.4f
            rect.set(x, h - barH * 0.82f, x + kw, h - barH * 0.18f)
            c.drawRoundRect(rect, barH * 0.2f, barH * 0.2f, paint)
            text.color = Color.WHITE
            c.drawText(key, x + barH * 0.2f, y, text)
            x += kw + barH * 0.2f
            c.drawText(label, x, y, text)
            x += text.measureText(label) + barH * 0.8f
        }
    }

    private fun drawStatus(c: Canvas, w: Float, h: Float) {
        text.typeface = theme.fontBold; text.textSize = h * 0.035f
        val tw = text.measureText(statusText)
        paint.color = 0xE0000000.toInt()
        rect.set(w / 2 - tw / 2 - 40, h * 0.44f, w / 2 + tw / 2 + 40, h * 0.56f)
        c.drawRoundRect(rect, 20f, 20f, paint)
        text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
        c.drawText(statusText, w / 2, h * 0.515f, text)
        text.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- Hilfen

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t)
    private fun rowHeight() = (height * 0.058f).coerceAtLeast(28 * density)
    private fun visibleRows(): Int = ((height * 0.73f) / rowHeight()).toInt().coerceAtLeast(1)

    private fun ensureVisible() {
        val h = height.toFloat(); val w = width.toFloat()
        if (isGrid()) {
            val cols = gridCols()
            val cellW = (w * 0.94f) / cols
            val cellH = cellW * if (gameView == "tiles") 1.15f else 0.95f
            val viewH = h * 0.90f - (h * 0.14f + h * 0.06f)
            val top = (gameIndex / cols) * cellH
            if (top < listScroll) listScroll = top
            if (top + cellH > listScroll + viewH) listScroll = top + cellH - viewH
        } else {
            val rowH = rowHeight()
            val viewH = h * 0.90f - (h * 0.14f + h * 0.03f)
            val top = gameIndex * rowH
            if (top < listScroll) listScroll = top
            if (top + rowH > listScroll + viewH) listScroll = top + rowH - viewH
        }
        scroller.forceFinished(true)
    }

    private fun stars(r: Float): String {
        if (r <= 0f) return ""
        val n = (r * 5).roundToInt().coerceIn(0, 5)
        return "★".repeat(n) + "☆".repeat(5 - n)
    }

    private fun playTime(sec: Long): String = when {
        sec <= 0 -> ""
        sec < 3600 -> "${sec / 60} Min."
        else -> "%d Std. %02d Min.".format(sec / 3600, (sec % 3600) / 60)
    }

    private fun buildLayout(s: String, p: TextPaint, width: Int): StaticLayout =
        if (android.os.Build.VERSION.SDK_INT >= 23)
            StaticLayout.Builder.obtain(s, 0, s.length, p, width.coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        else @Suppress("DEPRECATION") StaticLayout(s, p, width.coerceAtLeast(1), Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)

    private fun drawFit(c: Canvas, b: Bitmap, box: RectF) {
        val s = minOf(box.width() / b.width, box.height() / b.height)
        val bw = b.width * s; val bh = b.height * s
        val l = box.left + (box.width() - bw) / 2
        val t = box.top + (box.height() - bh) / 2
        rect.set(l, t, l + bw, t + bh)
        c.drawBitmap(b, null, rect, paint)
    }

    private fun drawCover(c: Canvas, b: Bitmap, box: RectF) {
        val s = maxOf(box.width() / b.width, box.height() / b.height)
        val sw = (box.width() / s).toInt(); val sh = (box.height() / s).toInt()
        val l = (b.width - sw) / 2; val t = (b.height - sh) / 2
        src.set(l, t, l + sw, t + sh)
        c.drawBitmap(b, src, box, paint)
    }

    fun clearCover(g: Game) { covers.remove(g.system + "/" + g.cover) }

    private fun coverFor(g: Game, w: Int, h: Int): Bitmap? {
        val rel = g.cover
        if (rel.isEmpty()) return null
        val key = g.system + "/" + rel
        covers.get(key)?.let { return it }
        if (key in loading) return null
        loading.add(key)
        val lib = library ?: return null
        loader.execute {
            val bmp = try {
                lib.mediaFile(g.system, rel)?.let { f ->
                    if (f.name.endsWith(".svg", true)) Theme.loadSvg(f, w, h) else Theme.loadBitmap(f, w, h)
                }
            } catch (e: Throwable) { null }
            mainHandler.post {
                loading.remove(key)
                if (bmp != null) { covers.put(key, bmp); invalidate() }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- Touch

    private var dragStartX = 0f
    private var dragStartScroll = 0f
    private var dragging = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean { handleTap(e.x, e.y, long = false); return true }
        override fun onLongPress(e: MotionEvent) { handleTap(e.x, e.y, long = true) }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (inGameList) { listScroll += dy; invalidate() }
            else {
                val vertical = systemView == "vertical" || systemView == "simple"
                if (!dragging) { dragging = true; dragStartX = if (vertical) e2.y + dy else e2.x + dx; dragStartScroll = sysScroll }
                val spacing = if (vertical) height * (if (systemView == "simple") 0.075f else 0.2f) else width * 0.26f
                val pos = if (vertical) e2.y else e2.x
                val dir = if (systemView == "wheel") -1f else 1f
                sysScroll = dragStartScroll - dir * (pos - dragStartX) / spacing
                sysAnimStart = 0
                invalidate()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (inGameList) { scroller.fling(0, listScroll.toInt(), 0, -vy.toInt(), 0, 0, 0, Int.MAX_VALUE); invalidate() }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging && !inGameList && systems.isNotEmpty()) {
                val n = systems.size
                val target = Math.round(sysScroll)
                sysAnimFrom = sysScroll - (target - ((target % n) + n) % n)
                sysIndex = ((target % n) + n) % n
                sysAnimStart = System.currentTimeMillis()
                current()?.let { listener?.onSystemChanged(it) }
                invalidate()
            }
            dragging = false
        }
        return handled || super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float, long: Boolean) {
        val w = width.toFloat(); val h = height.toFloat()
        if (systems.isEmpty()) { listener?.onMenu(); return }
        if (!inGameList) {
            if (long) return
            when (systemView) {
                "vertical" -> if (x < w * 0.38f) {
                    val k = Math.round((y - h * 0.47f) / (h * 0.2f))
                    if (k == 0) openGameList() else moveHorizontal(k)
                } else openGameList()
                "simple" -> {
                    val k = Math.round((y - h * 0.5f) / (h * 0.075f))
                    if (k == 0) openGameList() else moveHorizontal(k)
                }
                "wheel" -> openGameList()
                else -> {
                    val bandTop = h * 0.36f; val bandH = h * 0.30f
                    if (y in bandTop..(bandTop + bandH)) {
                        val k = Math.round((x - w / 2) / (w * 0.26f))
                        if (k == 0) openGameList() else moveHorizontal(k)
                    }
                }
            }
            return
        }
        val games = current()?.games ?: return
        val headH = h * 0.14f
        if (y < headH) { if (!long) back(); return }
        val hit: Int = if (isGrid()) {
            val cols = gridCols()
            val cellW = (w * 0.94f) / cols
            val cellH = cellW * if (gameView == "tiles") 1.15f else 0.95f
            val gt = headH + h * 0.06f
            val col = ((x - w * 0.03f) / cellW).toInt(); val row = ((y - gt + listScroll) / cellH).toInt()
            if (col in 0 until cols && y > gt) row * cols + col else -1
        } else {
            val listT = headH + h * 0.03f
            val listR = if (gameView == "basic") w * 0.97f else w * 0.50f
            if (x < listR && y > listT && y < h * 0.90f) ((y - listT + listScroll) / rowHeight()).toInt()
            else if (!long && gameView != "basic" && x > w * 0.53f && y < h * 0.57f) { confirm(); return } else -1
        }
        if (hit !in games.indices) return
        if (long) { gameIndex = hit; notifySelection(); invalidate(); options(); return }
        if (hit == gameIndex) confirm() else { gameIndex = hit; notifySelection(); invalidate() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loader.shutdownNow()
    }
}
