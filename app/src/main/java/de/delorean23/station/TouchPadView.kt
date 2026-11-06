package de.delorean23.station

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touch-Controller im Stil von Neon Racer: runde, dunkle Tasten mit dünnem
 * Rand und farbiger Beschriftung in den Bildrändern. Welche Tasten es gibt,
 * legt das [PadLayouts.Layout] fest (pro System).
 */
class TouchPadView(context: Context) : View(context) {

    interface Listener {
        /** key = RetroPad-Code (KeyEvent.KEYCODE_BUTTON_*/DPAD_*) oder PadLayouts.K_* */
        fun onKey(key: Int, pressed: Boolean)
        /** Werte -1..1 */
        fun onStick(left: Boolean, x: Float, y: Float)
    }

    var listener: Listener? = null
    var opacity = 0.7f
        set(v) { field = v; invalidate() }
    var vibrate = true
    /** Berührungen neben den Tasten an die Ansicht darunter weitergeben */
    var passThrough = true

    var layout: PadLayouts.Layout = PadLayouts.frontend
        set(v) { releaseAll(); field = v; build(); invalidate() }

    private class Button(val def: PadLayouts.Btn) {
        var cx = 0f; var cy = 0f; var r = 0f
        var pressed = false
        var pointer = -1
    }

    private class Stick(val def: PadLayouts.Stick) {
        var cx = 0f; var cy = 0f; var r = 0f
        var kx = 0f; var ky = 0f
        var pointer = -1
    }

    private class DpadBtn(val key: Int, val dx: Int, val dy: Int) {
        var cx = 0f; var cy = 0f; var r = 0f
        var pressed = false
    }

    private var buttons: List<Button> = emptyList()
    private var sticks: List<Stick> = emptyList()
    private val dpadBtns = listOf(
        DpadBtn(android.view.KeyEvent.KEYCODE_DPAD_UP, 0, -1),
        DpadBtn(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0, 1),
        DpadBtn(android.view.KeyEvent.KEYCODE_DPAD_LEFT, -1, 0),
        DpadBtn(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 1, 0)
    )
    private var dpadCx = 0f; private var dpadCy = 0f; private var dpadR = 0f
    private var dpadPointer = -1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = build()

    private fun xPos(side: PadLayouts.Side, x: Float, h: Float): Float = when (side) {
        PadLayouts.Side.LEFT -> x * h
        PadLayouts.Side.RIGHT -> width - x * h
        PadLayouts.Side.CENTER -> width / 2f + x * h
    }

    private fun build() {
        val h = height.toFloat()
        if (h <= 0) return
        buttons = layout.buttons.map { d ->
            Button(d).also { it.cx = xPos(d.side, d.x, h); it.cy = d.y * h; it.r = d.r * h }
        }
        sticks = layout.sticks.map { d ->
            Stick(d).also { it.cx = xPos(d.side, d.x, h); it.cy = d.y * h; it.r = d.r * h; it.kx = it.cx; it.ky = it.cy }
        }
        layout.dpad?.let { d ->
            dpadCx = xPos(d.side, d.x, h); dpadCy = d.y * h
            val off = d.offset * h
            dpadR = off + d.r * h
            dpadBtns.forEach { b -> b.cx = dpadCx + b.dx * off; b.cy = dpadCy + b.dy * off; b.r = d.r * h }
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val a = (opacity * 255).toInt()
        if (layout.dpad != null) drawDpad(canvas, a)
        sticks.forEach { drawStick(canvas, it, a) }
        buttons.forEach { drawButton(canvas, it, a) }
    }

    private fun drawCircleButton(c: Canvas, cx: Float, cy: Float, r: Float, pressed: Boolean, a: Int) {
        fill.color = if (pressed) 0xFF5A5A5A.toInt() else 0xFF141414.toInt()
        fill.alpha = (a * if (pressed) 0.95f else 0.75f).toInt()
        c.drawCircle(cx, cy, r, fill)
        stroke.color = 0xFF8A8A8A.toInt(); stroke.alpha = a
        stroke.strokeWidth = (r * 0.06f).coerceAtLeast(2f)
        c.drawCircle(cx, cy, r, stroke)
    }

    private fun drawButton(c: Canvas, b: Button, a: Int) {
        drawCircleButton(c, b.cx, b.cy, b.r, b.pressed, a)
        val len = b.def.label.length
        text.textSize = b.r * when {
            len <= 1 -> 0.95f
            len == 2 -> 0.72f
            len <= 4 -> 0.5f
            else -> 0.38f
        }
        text.color = b.def.color; text.alpha = a
        c.drawText(b.def.label, b.cx, b.cy + text.textSize * 0.36f, text)
    }

    private fun drawDpad(c: Canvas, a: Int) {
        fill.color = Color.BLACK; fill.alpha = (a * 0.35f).toInt()
        c.drawCircle(dpadCx, dpadCy, dpadR * 1.05f, fill)
        stroke.color = 0xFF505050.toInt(); stroke.alpha = a; stroke.strokeWidth = (dpadR * 0.02f).coerceAtLeast(2f)
        c.drawCircle(dpadCx, dpadCy, dpadR * 1.05f, stroke)
        for (b in dpadBtns) {
            drawCircleButton(c, b.cx, b.cy, b.r, b.pressed, a)
            // cyanfarbenes Dreieck
            val t = b.r * 0.42f
            path.reset()
            path.moveTo(b.cx + b.dx * t, b.cy + b.dy * t)
            path.lineTo(b.cx - b.dx * t * 0.6f + b.dy * t, b.cy - b.dy * t * 0.6f + b.dx * t)
            path.lineTo(b.cx - b.dx * t * 0.6f - b.dy * t, b.cy - b.dy * t * 0.6f - b.dx * t)
            path.close()
            fill.color = PadLayouts.COL_CYAN; fill.alpha = a
            c.drawPath(path, fill)
        }
    }

    private fun drawStick(c: Canvas, s: Stick, a: Int) {
        fill.color = Color.BLACK; fill.alpha = (a * 0.4f).toInt()
        c.drawCircle(s.cx, s.cy, s.r, fill)
        stroke.color = 0xFF8A8A8A.toInt(); stroke.alpha = a; stroke.strokeWidth = (s.r * 0.03f).coerceAtLeast(2f)
        c.drawCircle(s.cx, s.cy, s.r, stroke)
        drawCircleButton(c, s.kx, s.ky, s.r * 0.45f, s.pointer >= 0, a)
    }

    // ---------------------------------------------------------------- Touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!pointerDown(e.getPointerId(0), e.getX(0), e.getY(0)) && passThrough) return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) pointerMove(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float): Boolean {
        for (s in sticks) {
            if (s.pointer < 0 && hypot(x - s.cx, y - s.cy) <= s.r * 1.2f) {
                s.pointer = id; moveStick(s, x, y); return true
            }
        }
        for (b in buttons) {
            if (hypot(x - b.cx, y - b.cy) <= b.r * 1.15f) { press(b, id); return true }
        }
        if (layout.dpad != null && hypot(x - dpadCx, y - dpadCy) <= dpadR * 1.1f) {
            dpadPointer = id; updateDpad(x, y); return true
        }
        return false
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        sticks.firstOrNull { it.pointer == id }?.let { moveStick(it, x, y); return }
        if (dpadPointer == id) { updateDpad(x, y); return }
        // Über die Aktionstasten gleiten
        val held = buttons.firstOrNull { it.pointer == id } ?: return
        val target = buttons.firstOrNull { hypot(x - it.cx, y - it.cy) <= it.r } ?: return
        if (target !== held && target.def.r >= 0.06f && held.def.r >= 0.06f) {
            release(held); press(target, id)
        }
    }

    private fun pointerUp(id: Int) {
        sticks.filter { it.pointer == id }.forEach { resetStick(it) }
        if (dpadPointer == id) {
            dpadPointer = -1
            dpadBtns.forEach { setDpad(it, false) }
        }
        buttons.filter { it.pointer == id }.forEach { release(it) }
    }

    fun releaseAll() {
        buttons.filter { it.pressed }.forEach { release(it) }
        sticks.filter { it.pointer >= 0 }.forEach { resetStick(it) }
        dpadPointer = -1
        dpadBtns.forEach { setDpad(it, false) }
        invalidate()
    }

    private fun resetStick(s: Stick) {
        s.pointer = -1; s.kx = s.cx; s.ky = s.cy
        listener?.onStick(s.def.left, 0f, 0f)
    }

    private fun updateDpad(x: Float, y: Float) {
        val dx = x - dpadCx; val dy = y - dpadCy
        val dead = dpadR * 0.18f
        for (b in dpadBtns) {
            val on = when {
                b.dx < 0 -> dx < -dead && abs(dy) < abs(dx) * 2.4f
                b.dx > 0 -> dx > dead && abs(dy) < abs(dx) * 2.4f
                b.dy < 0 -> dy < -dead && abs(dx) < abs(dy) * 2.4f
                else -> dy > dead && abs(dx) < abs(dy) * 2.4f
            }
            setDpad(b, on)
        }
    }

    private fun setDpad(b: DpadBtn, on: Boolean) {
        if (on == b.pressed) return
        b.pressed = on
        listener?.onKey(b.key, on)
        if (on) buzz()
    }

    private fun moveStick(s: Stick, x: Float, y: Float) {
        var dx = x - s.cx; var dy = y - s.cy
        val d = hypot(dx, dy)
        if (d > s.r) { dx = dx / d * s.r; dy = dy / d * s.r }
        s.kx = s.cx + dx; s.ky = s.cy + dy
        listener?.onStick(s.def.left, dx / s.r, dy / s.r)
    }

    private fun press(b: Button, id: Int) {
        if (b.pressed) return
        b.pressed = true; b.pointer = id
        listener?.onKey(b.def.key, true)
        buzz()
    }

    private fun release(b: Button) {
        if (!b.pressed) return
        b.pressed = false; b.pointer = -1
        listener?.onKey(b.def.key, false)
    }

    private fun buzz() {
        if (!vibrate) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                    ?.vibrate(VibrationEffect.createOneShot(12, 80))
            } else performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {}
    }

    companion object {
        /** Ist ein echter Controller angeschlossen? */
        fun hasGamepad(): Boolean {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                if (d.isVirtual) continue
                val s = d.sources
                if ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true
            }
            return false
        }

        fun shouldShow(mode: String): Boolean = when (mode) {
            "an" -> true
            "aus" -> false
            else -> !hasGamepad()
        }
    }
}
