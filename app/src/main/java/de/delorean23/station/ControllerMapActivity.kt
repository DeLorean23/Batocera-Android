package de.delorean23.station

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** "Controller einstellen": jede Taste nacheinander drücken (wie bei Batocera). */
class ControllerMapActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var info: TextView
    private var device: InputDevice? = null
    private var step = -1
    private val map = HashMap<Int, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastAxisKey = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF101010.toInt())
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
        }
        title = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        info = TextView(this).apply { textSize = 18f; setTextColor(0xFFC0C0C0.toInt()); gravity = Gravity.CENTER; setPadding(0, (16 * dp).toInt(), 0, (24 * dp).toInt()) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val skip = Button(this).apply { text = "Überspringen"; setOnClickListener { next(null) } }
        val reset = Button(this).apply {
            text = "Belegung löschen"
            setOnClickListener { device?.let { Remap.reset(this@ControllerMapActivity, it) }; finish() }
        }
        val cancel = Button(this).apply { text = "Abbrechen"; setOnClickListener { finish() } }
        row.addView(skip); row.addView(reset); row.addView(cancel)
        root.addView(title); root.addView(info); root.addView(row)
        setContentView(root)
        title.text = "CONTROLLER EINSTELLEN"
        info.text = "Drücke eine beliebige Taste auf dem Controller, den du einstellen willst."
    }

    private fun next(code: Int?) {
        if (step >= 0 && code != null) {
            map[code] = Remap.steps[step].first
        }
        step++
        if (step >= Remap.steps.size) {
            Remap.save(this, device, map)
            title.text = "FERTIG"
            info.text = "Die Belegung für \"${device?.name}\" ist gespeichert."
            handler.postDelayed({ finish() }, 1500)
            return
        }
        title.text = Remap.steps[step].second
        info.text = "Controller: ${device?.name ?: "?"}\nSchritt ${step + 1} von ${Remap.steps.size}\n(Taste nicht vorhanden? → Überspringen)"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.device?.isVirtual != false) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        if (step < 0) {
            device = event.device
            Remap.reset(this, device)
            next(null)
            return true
        }
        if (event.device?.id != device?.id) return true
        if (step >= Remap.steps.size) return true
        next(event.keyCode)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (step < 0 || step >= Remap.steps.size || event.device?.id != device?.id) return super.dispatchGenericMotionEvent(event)
        // Steuerkreuz als Hat-Achse und Trigger als Achsen erkennen (werden automatisch umgesetzt)
        val target = Remap.steps[step].first
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        val axisKey = when {
            hy < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            hy > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            hx < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            hx > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            lt > 0.5f -> KeyEvent.KEYCODE_BUTTON_L2
            rt > 0.5f -> KeyEvent.KEYCODE_BUTTON_R2
            else -> 0
        }
        if (axisKey != 0 && axisKey != lastAxisKey) {
            lastAxisKey = axisKey
            // Achsen werden bereits richtig gemeldet; Schritt nur weiterschalten, wenn er passt
            if (axisKey == target) next(null) else { map[axisKey] = target; next(null) }
        } else if (axisKey == 0) lastAxisKey = 0
        return true
    }
}
