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
import kotlin.math.roundToInt

/**
 * Oberfläche im Stil des Carbon-Themes: Systemkarussell und Spieleliste mit
 * Cover und Infos. Steuerung per Touch, Controller und Tastatur.
 */
class FrontendView(context: Context) : View(context) {

    interface Listener {
        fun onLaunch(entry: SystemEntry, game: Game)
        fun onMenu()
        fun onSystemChanged(entry: SystemEntry)
        fun onExitRequest()
    }

    var listener: Listener? = null
    var theme: Theme = Theme(File("/"))
        set(v) { field = v; bgBitmap = null; invalidate() }
    var library: Library? = null
    var statusText: String = ""
        set(v) { field = v; invalidate() }

    private var systems: List<SystemEntry> = emptyList()
    var inGameList = false
        private set

    // System-Karussell
    private var sysIndex = 0
    private var sysScroll = 0f           // Position in "Logos", animiert zum Index
    private var sysAnimFrom = 0f
    private var sysAnimStart = 0L

    // Spieleliste
    private var gameIndex = 0
    private var listScroll = 0f
    private val scroller = OverScroller(context)

    private val loader = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val covers = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = HashSet<String>()
    private var bgBitmap: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val src = Rect()

    private val density = resources.displayMetrics.density

    fun setSystems(list: List<SystemEntry>, select: String?) {
        systems = list
        val i = list.indexOfFirst { it.system.id == select }
        sysIndex = if (i >= 0) i else 0
        sysScroll = sysIndex.toFloat()
        if (inGameList && list.isEmpty()) inGameList = false
        gameIndex = gameIndex.coerceIn(0, (current()?.games?.size ?: 1) - 1)
        invalidate()
    }

    fun current(): SystemEntry? = systems.getOrNull(sysIndex)

    // ---------------------------------------------------------------- Navigation

    fun moveHorizontal(d: Int) {
        if (systems.isEmpty()) return
        if (inGameList) {
            // Links/Rechts in der Liste = seitenweise wie bei ES
            moveVertical(d * visibleRows())
            return
        }
        setSystem((sysIndex + d + systems.size) % systems.size)
    }

    fun moveVertical(d: Int) {
        if (!inGameList) { moveHorizontal(d); return }
        val n = current()?.games?.size ?: return
        if (n == 0) return
        gameIndex = (gameIndex + d).let { if (abs(d) == 1) (it + n) % n else it.coerceIn(0, n - 1) }
        ensureVisible()
        invalidate()
    }

    fun confirm() {
        val e = current() ?: return
        if (!inGameList) {
            openGameList()
        } else {
            e.games.getOrNull(gameIndex)?.let { listener?.onLaunch(e, it) }
        }
    }

    /** true, wenn verarbeitet; false = App verlassen */
    fun back(): Boolean {
        if (inGameList) {
            inGameList = false
            invalidate()
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
                gameIndex = i; ensureVisible(); invalidate(); return
            }
        }
    }

    private fun openGameList() {
        if (current() == null) return
        inGameList = true
        gameIndex = 0
        listScroll = 0f
        invalidate()
    }

    private fun setSystem(i: Int) {
        if (i == sysIndex) return
        sysAnimFrom = sysScroll
        sysAnimStart = System.currentTimeMillis()
        sysIndex = i
        gameIndex = 0
        current()?.let { listener?.onSystemChanged(it) }
        invalidate()
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        drawBackground(canvas, w, h)
        if (systems.isEmpty()) {
            drawEmpty(canvas, w, h)
        } else if (inGameList) {
            drawGameList(canvas, w, h)
        } else {
            drawSystemView(canvas, w, h)
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
        text.typeface = theme.fontBold
        text.color = Color.WHITE
        text.textSize = h * 0.05f
        text.textAlign = Paint.Align.CENTER
        c.drawText("Keine Spiele gefunden", w / 2, h * 0.42f, text)
        text.typeface = theme.fontRegular
        text.textSize = h * 0.03f
        text.color = theme.infoColor
        val hint = if (library?.source?.isNetwork == true)
            "Netzwerk: Freigabe und Ordner im Menü prüfen (Start / ☰)"
        else "ROMs in ${File(File(theme.dir.parentFile?.parentFile, "roms"), "<system>").path} ablegen"
        c.drawText(hint, w / 2, h * 0.50f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawSystemView(c: Canvas, w: Float, h: Float) {
        // Animation Karussell
        val t = ((System.currentTimeMillis() - sysAnimStart) / 220f).coerceIn(0f, 1f)
        val target = sysIndex.toFloat()
        var from = sysAnimFrom
        // kürzesten Weg im Kreis nehmen
        if (systems.size > 2) {
            if (target - from > systems.size / 2f) from += systems.size
            if (from - target > systems.size / 2f) from -= systems.size
        }
        sysScroll = if (t >= 1f) target else from + (target - from) * ease(t)
        if (t < 1f) postInvalidateOnAnimation()

        val entry = current() ?: return

        // Großes System-Bild (falls im Theme vorhanden)
        theme.systemArt(entry.system.id, width, height)?.let { art ->
            paint.alpha = 90
            drawCover(c, art, RectF(0f, 0f, w, h))
            paint.alpha = 255
        }

        // Hersteller, Name, Anzahl
        text.typeface = theme.fontBold
        text.color = Color.WHITE
        text.textSize = h * 0.07f
        c.drawText(entry.system.manufacturer, w * 0.03f, h * 0.11f, text)
        text.typeface = theme.fontRegular
        text.textSize = h * 0.032f
        text.color = theme.infoColor
        c.drawText("${entry.system.name}  ·  ${entry.system.year}", w * 0.03f, h * 0.17f, text)

        // Karussell-Band
        val bandTop = h * 0.36f; val bandH = h * 0.30f
        paint.color = 0x99000000.toInt()
        c.drawRect(0f, bandTop, w, bandTop + bandH, paint)
        paint.shader = LinearGradient(0f, bandTop, 0f, bandTop + bandH,
            intArrayOf(theme.baseColor and 0x00FFFFFF or 0x30000000, Color.TRANSPARENT, theme.baseColor and 0x00FFFFFF or 0x30000000),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, bandTop, w, bandTop + bandH, paint)
        paint.shader = null

        val spacing = w * 0.26f
        val cx = w / 2
        val cy = bandTop + bandH / 2
        val n = systems.size
        for (k in -3..3) {
            if (n < 2 && k != 0) continue
            val idxF = sysIndex + k
            val idx = ((idxF % n) + n) % n
            val offset = (sysIndex + k) - sysScroll
            val x = cx + offset * spacing
            if (x < -spacing || x > w + spacing) continue
            val dist = abs(offset).coerceAtMost(2f)
            val scale = 1f - 0.35f * dist.coerceAtMost(1f)
            val alpha = (255 * (1f - 0.55f * dist.coerceAtMost(1f))).roundToInt()
            val lw = spacing * 0.85f * scale; val lh = bandH * 0.62f * scale
            val sys = systems[idx].system
            val logo = theme.logo(sys.id, (spacing * 0.85f).toInt(), (bandH * 0.62f).toInt())
            if (logo != null) {
                paint.alpha = alpha
                drawFit(c, logo, RectF(x - lw / 2, cy - lh / 2, x + lw / 2, cy + lh / 2))
                paint.alpha = 255
            } else {
                text.typeface = theme.fontBold
                text.textAlign = Paint.Align.CENTER
                text.color = Color.WHITE; text.alpha = alpha
                text.textSize = bandH * 0.16f * scale
                val label = TextUtils.ellipsize(sys.name, text, lw, TextUtils.TruncateAt.END).toString()
                c.drawText(label, x, cy + text.textSize / 3, text)
                text.alpha = 255; text.textAlign = Paint.Align.LEFT
            }
        }

        // Konsole und Spieleanzahl
        theme.console(entry.system.id, (w * 0.3f).toInt(), (h * 0.22f).toInt())?.let {
            drawFit(c, it, RectF(w * 0.66f, h * 0.68f, w * 0.97f, h * 0.90f))
        }
        text.typeface = theme.fontRegular
        text.color = theme.infoColor
        text.textSize = h * 0.035f
        text.textAlign = Paint.Align.CENTER
        val count = entry.games.size
        c.drawText(if (count == 1) "1 Spiel verfügbar" else "$count Spiele verfügbar", cx, bandTop + bandH + h * 0.07f, text)
        text.textSize = h * 0.026f
        val src = if (library?.source?.isNetwork == true) "Netzwerk" else "Intern"
        c.drawText("Speicher: $src", cx, bandTop + bandH + h * 0.115f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawGameList(c: Canvas, w: Float, h: Float) {
        val entry = current() ?: return
        val games = entry.games

        // Kopf: Logo bzw. Name
        val headH = h * 0.14f
        paint.color = 0x80000000.toInt()
        c.drawRect(0f, 0f, w, headH, paint)
        val logo = theme.logo(entry.system.id, (w * 0.3f).toInt(), (headH * 0.75f).toInt())
        if (logo != null) drawFit(c, logo, RectF(w * 0.35f, headH * 0.12f, w * 0.65f, headH * 0.88f))
        else {
            text.typeface = theme.fontBold; text.color = Color.WHITE; text.textSize = headH * 0.4f
            text.textAlign = Paint.Align.CENTER
            c.drawText(entry.system.name, w / 2, headH * 0.65f, text)
            text.textAlign = Paint.Align.LEFT
        }

        // Liste links
        val listL = w * 0.03f; val listR = w * 0.50f
        val listT = headH + h * 0.03f; val listB = h * 0.90f
        val rowH = rowHeight()
        if (scroller.computeScrollOffset()) { listScroll = scroller.currY.toFloat(); postInvalidateOnAnimation() }
        val maxScroll = (games.size * rowH - (listB - listT)).coerceAtLeast(0f)
        listScroll = listScroll.coerceIn(0f, maxScroll)

        c.save()
        c.clipRect(listL, listT, listR, listB)
        val first = (listScroll / rowH).toInt().coerceAtLeast(0)
        val last = ((listScroll + (listB - listT)) / rowH).toInt().coerceAtMost(games.size - 1)
        text.typeface = theme.fontRegular
        text.textSize = rowH * 0.55f
        for (i in first..last) {
            val top = listT + i * rowH - listScroll
            if (i == gameIndex) {
                paint.color = theme.selectorColor
                rect.set(listL, top, listR, top + rowH)
                c.drawRoundRect(rect, rowH * 0.15f, rowH * 0.15f, paint)
            }
            val g = games[i]
            text.color = if (i == gameIndex) Color.WHITE else 0xFFDDDDDD.toInt()
            val label = (if (g.favorite) "★ " else "") + g.name
            val s = TextUtils.ellipsize(label, text, listR - listL - rowH * 0.6f, TextUtils.TruncateAt.END).toString()
            c.drawText(s, listL + rowH * 0.3f, top + rowH * 0.70f, text)
        }
        c.restore()

        // Scrollbalken
        if (maxScroll > 0) {
            val frac = (listB - listT) / (games.size * rowH)
            val barH = (listB - listT) * frac
            val barT = listT + (listScroll / maxScroll) * ((listB - listT) - barH)
            paint.color = 0x60FFFFFF
            c.drawRect(listR + w * 0.004f, barT, listR + w * 0.008f, barT + barH, paint)
        }

        // Details rechts
        val g = games.getOrNull(gameIndex) ?: return
        val dL = w * 0.53f; val dR = w * 0.97f
        val coverRect = RectF(dL, listT, dR, listT + h * 0.40f)
        val cover = coverFor(entry, g, (dR - dL).toInt(), (coverRect.height()).toInt())
        if (cover != null) {
            drawFit(c, cover, coverRect, alignLeft = false)
        } else {
            paint.color = 0x40000000
            c.drawRoundRect(coverRect, 12f, 12f, paint)
            text.color = 0x80FFFFFF.toInt(); text.textSize = h * 0.03f; text.textAlign = Paint.Align.CENTER
            c.drawText("Kein Bild", coverRect.centerX(), coverRect.centerY(), text)
            text.textAlign = Paint.Align.LEFT
        }

        var y = coverRect.bottom + h * 0.05f
        text.typeface = theme.fontRegular
        text.textSize = h * 0.028f
        val meta = listOf(
            "Bewertung" to stars(g.rating),
            "Erschienen" to g.year,
            "Entwickler" to g.developer,
            "Publisher" to g.publisher,
            "Genre" to g.genre,
            "Spieler" to g.players
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
            text.color = 0xFFE0E0E0.toInt()
            text.textSize = h * 0.026f
            val layout = buildLayout(g.desc, text, (dR - dL).toInt())
            val maxLines = ((h * 0.90f - y) / (text.fontSpacing)).toInt().coerceAtLeast(0)
            c.save()
            c.clipRect(dL, y, dR, h * 0.90f)
            c.translate(dL, y)
            if (layout.lineCount > maxLines && maxLines > 0) {
                layout.draw(c)
            } else layout.draw(c)
            c.restore()
        }
    }

    private fun drawBottomBar(c: Canvas, w: Float, h: Float) {
        val barH = h * 0.065f
        paint.color = theme.bottomBarColor
        c.drawRect(0f, h - barH, w, h, paint)
        text.typeface = theme.fontRegular
        text.color = Color.WHITE
        text.textSize = barH * 0.42f
        val y = h - barH * 0.33f
        val items = if (inGameList)
            listOf("B" to "Starten", "A" to "Zurück", "LB/RB" to "Buchstabe", "☰" to "Menü")
        else listOf("B" to "Auswählen", "◀ ▶" to "System", "☰" to "Menü")
        var x = w * 0.03f
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
        // Touch-Schaltflächen rechts
        text.textAlign = Paint.Align.RIGHT
        text.color = Color.WHITE
        c.drawText(if (inGameList) "☰ MENÜ    ‹ ZURÜCK" else "☰ MENÜ", w * 0.97f, y, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawStatus(c: Canvas, w: Float, h: Float) {
        text.typeface = theme.fontBold
        text.textSize = h * 0.035f
        val tw = text.measureText(statusText)
        paint.color = 0xE0000000.toInt()
        rect.set(w / 2 - tw / 2 - 40, h * 0.44f, w / 2 + tw / 2 + 40, h * 0.56f)
        c.drawRoundRect(rect, 20f, 20f, paint)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        c.drawText(statusText, w / 2, h * 0.515f, text)
        text.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- Hilfen

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t)

    private fun rowHeight() = (height * 0.058f).coerceAtLeast(28 * density)

    private fun visibleRows(): Int = ((height * 0.73f) / rowHeight()).toInt().coerceAtLeast(1)

    private fun ensureVisible() {
        val rowH = rowHeight()
        val viewH = height * 0.90f - (height * 0.14f + height * 0.03f)
        val top = gameIndex * rowH
        if (top < listScroll) listScroll = top
        if (top + rowH > listScroll + viewH) listScroll = top + rowH - viewH
        scroller.forceFinished(true)
    }

    private fun stars(r: Float): String {
        if (r <= 0f) return ""
        val n = (r * 5).roundToInt().coerceIn(0, 5)
        return "★".repeat(n) + "☆".repeat(5 - n)
    }

    private fun buildLayout(s: String, p: TextPaint, width: Int): StaticLayout =
        if (android.os.Build.VERSION.SDK_INT >= 23)
            StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        else @Suppress("DEPRECATION") StaticLayout(s, p, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)

    private fun drawFit(c: Canvas, b: Bitmap, box: RectF, alignLeft: Boolean = false) {
        val s = minOf(box.width() / b.width, box.height() / b.height)
        val bw = b.width * s; val bh = b.height * s
        val l = if (alignLeft) box.left else box.left + (box.width() - bw) / 2
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

    private fun coverFor(entry: SystemEntry, g: Game, w: Int, h: Int): Bitmap? {
        val rel = g.cover
        if (rel.isEmpty()) return null
        val key = entry.system.id + "/" + rel
        covers.get(key)?.let { return it }
        if (key in loading) return null
        loading.add(key)
        val lib = library ?: return null
        loader.execute {
            val bmp = try {
                lib.coverFile(entry.system, rel)?.let { f ->
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
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (inGameList) {
                listScroll += dy
                invalidate()
            } else {
                if (!dragging) { dragging = true; dragStartX = e2.x + dx; dragStartScroll = sysScroll }
                val spacing = width * 0.26f
                sysScroll = dragStartScroll - (e2.x - dragStartX) / spacing
                sysAnimStart = 0
                invalidate()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (inGameList) {
                scroller.fling(0, listScroll.toInt(), 0, -vy.toInt(), 0, 0, 0, Int.MAX_VALUE)
                invalidate()
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging && !inGameList && systems.isNotEmpty()) {
                dragging = false
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

    private fun handleTap(x: Float, y: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        // Untere Leiste
        if (y > h * 0.935f) {
            if (x > w * 0.82f) { listener?.onMenu(); return }
            if (inGameList && x > w * 0.68f) { back(); return }
            return
        }
        if (systems.isEmpty()) { listener?.onMenu(); return }
        if (!inGameList) {
            val bandTop = h * 0.36f; val bandH = h * 0.30f
            if (y in bandTop..(bandTop + bandH)) {
                val spacing = w * 0.26f
                val k = Math.round((x - w / 2) / spacing)
                if (k == 0) openGameList() else moveHorizontal(k)
            }
            return
        }
        // Spieleliste
        val listT = h * 0.14f + h * 0.03f
        if (x < w * 0.50f && y > listT && y < h * 0.90f) {
            val i = ((y - listT + listScroll) / rowHeight()).toInt()
            val games = current()?.games ?: return
            if (i in games.indices) {
                if (i == gameIndex) confirm() else { gameIndex = i; invalidate() }
            }
        } else if (x > w * 0.53f && y > listT && y < h * 0.57f) {
            confirm()
        } else if (y < h * 0.14f) {
            back()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loader.shutdownNow()
    }
}
