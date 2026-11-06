package de.delorean23.station

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Voller Xbox-One-Controller auf dem Touchscreen:
 * linker Stick, Steuerkreuz, A/B/X/Y als Raute (Y oben, X links, B rechts, A unten),
 * LB/RB, LT/RT, L3/R3, Ansicht- und Menü-Taste, Xbox-Taste (Spielmenü), rechter Stick.
 */
class TouchPadView(context: Context) : View(context) {

    /** Taste einer Xbox-Position */
    enum class Btn { A, B, X, Y, LB, RB, LT, RT, L3, R3, VIEW, MENU, GUIDE, UP, DOWN, LEFT, RIGHT }

    interface Listener {
        fun onButton(btn: Btn, pressed: Boolean)
        /** Werte -1..1 */
        fun onStick(left: Boolean, x: Float, y: Float)
    }

    var listener: Listener? = null
    var opacity = 0.7f
        set(v) { field = v; invalidate() }
    var vibrate = true

    private class Button(val btn: Btn, val label: String, val color: Int, val round: Boolean) {
        val rect = RectF()
        var pressed = false
        var pointer = -1
    }

    private class Stick(val left: Boolean) {
        var cx = 0f; var cy = 0f; var r = 0f
        var kx = 0f; var ky = 0f
        var pointer = -1
    }

    private val buttons = listOf(
        Button(Btn.A, "A", 0xFF3CB043.toInt(), true),
        Button(Btn.B, "B", 0xFFE03C31.toInt(), true),
        Button(Btn.X, "X", 0xFF2D7DD2.toInt(), true),
        Button(Btn.Y, "Y", 0xFFF4C430.toInt(), true),
        Button(Btn.LB, "LB", Color.WHITE, false),
        Button(Btn.RB, "RB", Color.WHITE, false),
        Button(Btn.LT, "LT", Color.WHITE, false),
        Button(Btn.RT, "RT", Color.WHITE, false),
        Button(Btn.L3, "L3", Color.WHITE, true),
        Button(Btn.R3, "R3", Color.WHITE, true),
        Button(Btn.VIEW, "⧉", Color.WHITE, true),
        Button(Btn.MENU, "≡", Color.WHITE, true),
        Button(Btn.GUIDE, "X", 0xFF3CB043.toInt(), true),
        Button(Btn.UP, "", Color.WHITE, false),
        Button(Btn.DOWN, "", Color.WHITE, false),
        Button(Btn.LEFT, "", Color.WHITE, false),
        Button(Btn.RIGHT, "", Color.WHITE, false)
    )
    private val byBtn = buttons.associateBy { it.btn }
    private val leftStick = Stick(true)
    private val rightStick = Stick(false)
    private val dpad = RectF()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutPad(w.toFloat(), h.toFloat())
    }

    private fun layoutPad(w: Float, h: Float) {
        val u = min(h / 9f, w / 18f)          // Grundeinheit
        val face = u * 1.25f                    // Radius A/B/X/Y
        // Schultertasten oben
        place(Btn.LT, 0.6f * u, 0.4f * u, 3.0f * u, 1.3f * u)
        place(Btn.LB, 0.6f * u, 1.9f * u, 3.0f * u, 1.1f * u)
        place(Btn.RT, w - 3.6f * u, 0.4f * u, 3.0f * u, 1.3f * u)
        place(Btn.RB, w - 3.6f * u, 1.9f * u, 3.0f * u, 1.1f * u)

        // Linker Stick (Xbox: oben links), Steuerkreuz darunter rechts versetzt
        leftStick.cx = 3.0f * u; leftStick.cy = h - 4.4f * u; leftStick.r = 1.9f * u
        leftStick.kx = leftStick.cx; leftStick.ky = leftStick.cy
        val dcx = 6.6f * u; val dcy = h - 1.9f * u; val ds = 0.85f * u
        dpad.set(dcx - 3 * ds, dcy - 3 * ds, dcx + 3 * ds, dcy + 3 * ds)
        place(Btn.UP, dcx - ds, dcy - 3 * ds, 2 * ds, 2 * ds)
        place(Btn.DOWN, dcx - ds, dcy + ds, 2 * ds, 2 * ds)
        place(Btn.LEFT, dcx - 3 * ds, dcy - ds, 2 * ds, 2 * ds)
        place(Btn.RIGHT, dcx + ds, dcy - ds, 2 * ds, 2 * ds)
        placeRound(Btn.L3, leftStick.cx + 2.3f * u, leftStick.cy - 2.1f * u, 0.6f * u)

        // A/B/X/Y-Raute rechts oben, rechter Stick darunter links versetzt
        val fcx = w - 3.2f * u; val fcy = h - 4.6f * u; val off = face * 1.55f
        placeRound(Btn.Y, fcx, fcy - off, face)
        placeRound(Btn.X, fcx - off, fcy, face)
        placeRound(Btn.B, fcx + off, fcy, face)
        placeRound(Btn.A, fcx, fcy + off, face)
        rightStick.cx = w - 6.8f * u; rightStick.cy = h - 1.9f * u; rightStick.r = 1.5f * u
        rightStick.kx = rightStick.cx; rightStick.ky = rightStick.cy
        placeRound(Btn.R3, rightStick.cx - 2.1f * u, rightStick.cy - 1.4f * u, 0.6f * u)

        // Mitte: Ansicht, Xbox, Menü
        placeRound(Btn.VIEW, w / 2 - 2.0f * u, 0.95f * u, 0.6f * u)
        placeRound(Btn.GUIDE, w / 2, 0.95f * u, 0.75f * u)
        placeRound(Btn.MENU, w / 2 + 2.0f * u, 0.95f * u, 0.6f * u)
    }

    private fun place(b: Btn, l: Float, t: Float, w: Float, h: Float) {
        byBtn[b]!!.rect.set(l, t, l + w, t + h)
    }

    private fun placeRound(b: Btn, cx: Float, cy: Float, r: Float) {
        byBtn[b]!!.rect.set(cx - r, cy - r, cx + r, cy + r)
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val a = (opacity * 255).toInt()
        drawStick(canvas, leftStick, a)
        drawStick(canvas, rightStick, a)
        drawDpad(canvas, a)
        for (b in buttons) {
            if (b.btn in dpadBtns) continue
            drawButton(canvas, b, a)
        }
    }

    private fun drawButton(c: Canvas, b: Button, a: Int) {
        val r = b.rect
        val pressedBoost = if (b.pressed) 1.0f else 0.55f
        // Körper: dunkel mit weißem Rand (Xbox-One-Look)
        paint.style = Paint.Style.FILL
        paint.color = if (b.pressed) b.color else Color.BLACK
        paint.alpha = (a * pressedBoost).toInt().coerceIn(0, 255)
        if (b.round) c.drawOval(r, paint) else c.drawRoundRect(r, r.height() * 0.35f, r.height() * 0.35f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r.height() * 0.07f
        paint.color = if (b.btn in faceBtns || b.btn == Btn.GUIDE) b.color else Color.WHITE
        paint.alpha = a
        if (b.round) c.drawOval(r, paint) else c.drawRoundRect(r, r.height() * 0.35f, r.height() * 0.35f, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = if (b.pressed) Color.BLACK else (if (b.btn in faceBtns) b.color else Color.WHITE)
        textPaint.alpha = a
        textPaint.textSize = r.height() * if (b.btn in faceBtns) 0.55f else 0.48f
        if (b.btn == Btn.GUIDE) {
            // Xbox-Taste: Kreis mit Kreuz
            paint.color = 0xFF3CB043.toInt(); paint.alpha = a
            paint.style = Paint.Style.STROKE; paint.strokeWidth = r.width() * 0.09f
            val inset = r.width() * 0.3f
            c.drawLine(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, paint)
            c.drawLine(r.right - inset, r.top + inset, r.left + inset, r.bottom - inset, paint)
            paint.style = Paint.Style.FILL
            return
        }
        c.drawText(b.label, r.centerX(), r.centerY() + textPaint.textSize * 0.36f, textPaint)
    }

    private fun drawDpad(c: Canvas, a: Int) {
        val up = byBtn[Btn.UP]!!.rect; val left = byBtn[Btn.LEFT]!!.rect
        val s = up.width()
        val cx = up.centerX(); val cy = left.centerY()
        // Kreuz-Form
        path.reset()
        val h = s / 2; val f = s * 1.5f
        path.moveTo(cx - h, cy - f); path.lineTo(cx + h, cy - f); path.lineTo(cx + h, cy - h)
        path.lineTo(cx + f, cy - h); path.lineTo(cx + f, cy + h); path.lineTo(cx + h, cy + h)
        path.lineTo(cx + h, cy + f); path.lineTo(cx - h, cy + f); path.lineTo(cx - h, cy + h)
        path.lineTo(cx - f, cy + h); path.lineTo(cx - f, cy - h); path.lineTo(cx - h, cy - h); path.close()
        paint.style = Paint.Style.FILL; paint.color = Color.BLACK; paint.alpha = (a * 0.55f).toInt()
        c.drawPath(path, paint)
        for (b in dpadBtns) {
            val btn = byBtn[b]!!
            if (btn.pressed) {
                paint.color = Color.WHITE; paint.alpha = (a * 0.8f).toInt()
                c.drawRect(btn.rect, paint)
            }
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.08f
        paint.color = Color.WHITE; paint.alpha = a
        c.drawPath(path, paint)
        // Pfeile
        paint.style = Paint.Style.FILL
        val t = s * 0.22f
        arrow(c, cx, cy - s, 0f, -1f, t, a); arrow(c, cx, cy + s, 0f, 1f, t, a)
        arrow(c, cx - s, cy, -1f, 0f, t, a); arrow(c, cx + s, cy, 1f, 0f, t, a)
    }

    private fun arrow(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, t: Float, a: Int) {
        path.reset()
        path.moveTo(x + dx * t, y + dy * t)
        path.lineTo(x - dx * t + dy * t, y - dy * t + dx * t)
        path.lineTo(x - dx * t - dy * t, y - dy * t - dx * t)
        path.close()
        paint.color = Color.WHITE; paint.alpha = a
        c.drawPath(path, paint)
    }

    private fun drawStick(c: Canvas, s: Stick, a: Int) {
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK; paint.alpha = (a * 0.45f).toInt()
        c.drawCircle(s.cx, s.cy, s.r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s.r * 0.05f
        paint.color = Color.WHITE; paint.alpha = a
        c.drawCircle(s.cx, s.cy, s.r, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (s.pointer >= 0) 0xFFDDDDDD.toInt() else 0xFF303030.toInt(); paint.alpha = a
        c.drawCircle(s.kx, s.ky, s.r * 0.5f, paint)
        paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.alpha = a
        c.drawCircle(s.kx, s.ky, s.r * 0.5f, paint)
        paint.style = Paint.Style.FILL
    }

    // ---------------------------------------------------------------- Touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) pointerMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        for (s in listOf(leftStick, rightStick)) {
            if (s.pointer < 0 && hypot(x - s.cx, y - s.cy) <= s.r * 1.25f) {
                s.pointer = id; moveStick(s, x, y); return
            }
        }
        // Steuerkreuz: Diagonalen erlauben
        if (dpad.contains(x, y)) { dpadPointer = id; updateDpad(x, y); return }
        for (b in buttons) {
            if (b.btn in dpadBtns) continue
            if (hit(b, x, y)) { press(b, id); return }
        }
    }

    private var dpadPointer = -1

    private fun pointerMove(id: Int, x: Float, y: Float) {
        if (leftStick.pointer == id) { moveStick(leftStick, x, y); return }
        if (rightStick.pointer == id) { moveStick(rightStick, x, y); return }
        if (dpadPointer == id) { updateDpad(x, y); return }
        // Gleiten über die A/B/X/Y-Tasten
        val held = buttons.firstOrNull { it.pointer == id } ?: return
        if (held.btn !in faceBtns) return
        val target = buttons.firstOrNull { it.btn in faceBtns && hit(it, x, y) } ?: return
        if (target !== held) {
            release(held)
            press(target, id)
        }
    }

    private fun pointerUp(id: Int) {
        for (s in listOf(leftStick, rightStick)) {
            if (s.pointer == id) {
                s.pointer = -1; s.kx = s.cx; s.ky = s.cy
                listener?.onStick(s.left, 0f, 0f)
            }
        }
        if (dpadPointer == id) {
            dpadPointer = -1
            dpadBtns.forEach { b -> byBtn[b]!!.let { if (it.pressed) release(it) } }
        }
        buttons.filter { it.pointer == id }.forEach { release(it) }
    }

    fun releaseAll() {
        buttons.filter { it.pressed }.forEach { release(it) }
        for (s in listOf(leftStick, rightStick)) {
            if (s.pointer >= 0) { s.pointer = -1; s.kx = s.cx; s.ky = s.cy; listener?.onStick(s.left, 0f, 0f) }
        }
        dpadPointer = -1
        invalidate()
    }

    private fun updateDpad(x: Float, y: Float) {
        val cx = dpad.centerX(); val cy = dpad.centerY()
        val dead = dpad.width() * 0.12f
        val dx = x - cx; val dy = y - cy
        setDpad(Btn.LEFT, dx < -dead && kotlin.math.abs(dy) < kotlin.math.abs(dx) * 2.4f)
        setDpad(Btn.RIGHT, dx > dead && kotlin.math.abs(dy) < kotlin.math.abs(dx) * 2.4f)
        setDpad(Btn.UP, dy < -dead && kotlin.math.abs(dx) < kotlin.math.abs(dy) * 2.4f)
        setDpad(Btn.DOWN, dy > dead && kotlin.math.abs(dx) < kotlin.math.abs(dy) * 2.4f)
    }

    private fun setDpad(btn: Btn, on: Boolean) {
        val b = byBtn[btn]!!
        if (on && !b.pressed) press(b, dpadPointer)
        else if (!on && b.pressed) release(b)
    }

    private fun moveStick(s: Stick, x: Float, y: Float) {
        var dx = x - s.cx; var dy = y - s.cy
        val d = hypot(dx, dy)
        if (d > s.r) { dx = dx / d * s.r; dy = dy / d * s.r }
        s.kx = s.cx + dx; s.ky = s.cy + dy
        listener?.onStick(s.left, dx / s.r, dy / s.r)
    }

    private fun hit(b: Button, x: Float, y: Float): Boolean {
        val r = b.rect
        val grow = r.height() * 0.15f
        return x >= r.left - grow && x <= r.right + grow && y >= r.top - grow && y <= r.bottom + grow
    }

    private fun press(b: Button, id: Int) {
        if (b.pressed) return
        b.pressed = true; b.pointer = id
        listener?.onButton(b.btn, true)
        if (vibrate) buzz()
    }

    private fun release(b: Button) {
        if (!b.pressed) return
        b.pressed = false; b.pointer = -1
        listener?.onButton(b.btn, false)
    }

    private fun buzz() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(12, 80))
            } else {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } catch (_: Exception) {}
    }

    companion object {
        val faceBtns = setOf(Btn.A, Btn.B, Btn.X, Btn.Y)
        val dpadBtns = setOf(Btn.UP, Btn.DOWN, Btn.LEFT, Btn.RIGHT)
    }
}
