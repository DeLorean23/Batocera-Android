package de.delorean23.station

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Dialoge, die sich mit Controller und Touch bedienen lassen. */
object Ui {

    /** A (unten) bestätigt, B (rechts) zurück – getauscht = Batocera-Belegung */
    fun confirmKey(cfg: Config) = if (cfg.swapAB) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
    fun cancelKey(cfg: Config) = if (cfg.swapAB) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B

    /** Bestätigen/Zurück mit Controller-Tasten (Batocera-Belegung) */
    fun gamepad(dialog: AlertDialog, cfg: Config): AlertDialog {
        dialog.setOnKeyListener { d, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val confirm = confirmKey(cfg)
            val cancel = cancelKey(cfg)
            when (code) {
                confirm -> {
                    val focus = dialog.currentFocus
                    val list = dialog.listView
                    if (list != null && list.hasFocus() && list.selectedItemPosition >= 0) {
                        list.performItemClick(list.selectedView, list.selectedItemPosition, list.selectedItemId)
                    } else if (focus is ListView && focus.selectedItemPosition >= 0) {
                        focus.performItemClick(focus.selectedView, focus.selectedItemPosition, focus.selectedItemId)
                    } else focus?.performClick()
                    true
                }
                cancel -> { d.cancel(); true }
                else -> false
            }
        }
        return dialog
    }

    fun list(ctx: Context, cfg: Config, title: String, items: List<String>, onPick: (Int) -> Unit): AlertDialog =
        gamepad(AlertDialog.Builder(ctx, R.style.StationDialog)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, i -> onPick(i) }
            .setNegativeButton("Zurück", null)
            .create(), cfg).also { it.show() }

    fun choice(ctx: Context, cfg: Config, title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit): AlertDialog =
        gamepad(AlertDialog.Builder(ctx, R.style.StationDialog)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected) { d, i -> d.dismiss(); onPick(i) }
            .setNegativeButton("Zurück", null)
            .create(), cfg).also { it.show() }

    fun message(ctx: Context, cfg: Config, title: String, text: String, ok: (() -> Unit)? = null): AlertDialog =
        gamepad(AlertDialog.Builder(ctx, R.style.StationDialog)
            .setTitle(title).setMessage(text)
            .setPositiveButton("OK") { _, _ -> ok?.invoke() }
            .create(), cfg).also { it.show() }

    fun confirm(ctx: Context, cfg: Config, title: String, text: String, yes: String, onYes: () -> Unit): AlertDialog =
        gamepad(AlertDialog.Builder(ctx, R.style.StationDialog)
            .setTitle(title).setMessage(text)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Abbrechen", null)
            .create(), cfg).also { it.show() }

    /**
     * Bildschirmtastatur wie bei Batocera: Buchstabenraster, mit Steuerkreuz
     * und Touch bedienbar.
     */
    fun keyboard(activity: Activity, cfg: Config, title: String, initial: String, onDone: (String) -> Unit) {
        val dp = activity.resources.displayMetrics.density
        val sb = StringBuilder(initial)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), 0)
        }
        val display = TextView(activity).apply {
            textSize = 22f; setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2A2A2A.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            text = "$sb▏"
        }
        root.addView(display)
        val rows = listOf("1234567890", "QWERTZUIOP", "ASDFGHJKLÜ", "YXCVBNMÖÄ-", "., :'!?&()")
        val grid = GridLayout(activity).apply { columnCount = 10 }
        lateinit var dialog: AlertDialog
        fun keyBg(): StateListDrawable {
            val sel = GradientDrawable().apply { setColor(0xFF8B0000.toInt()); cornerRadius = 6 * dp }
            val norm = GradientDrawable().apply { setColor(0xFF333333.toInt()); cornerRadius = 6 * dp }
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), sel)
                addState(intArrayOf(android.R.attr.state_pressed), sel)
                addState(intArrayOf(), norm)
            }
        }
        fun key(label: String, span: Int = 1, action: () -> Unit) {
            val tv = TextView(activity).apply {
                text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                isFocusable = true; isClickable = true; background = keyBg()
                setOnClickListener { action(); display.text = "$sb▏" }
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, span, 1f)).apply {
                width = 0; height = (40 * dp).toInt(); setMargins(3, 3, 3, 3)
            }
            grid.addView(tv, lp)
        }
        rows.forEach { r -> r.forEach { ch -> key(ch.toString()) { sb.append(ch) } } }
        key("LEER", 3) { sb.append(' ') }
        key("⌫", 2) { if (sb.isNotEmpty()) sb.setLength(sb.length - 1) }
        key("LÖSCHEN", 2) { sb.setLength(0) }
        key("OK", 3) { dialog.dismiss(); onDone(sb.toString().trim()) }
        root.addView(grid)
        dialog = AlertDialog.Builder(activity, R.style.StationDialog).setTitle(title).setView(root).create()
        gamepad(dialog, cfg)
        dialog.show()
        grid.getChildAt(10)?.requestFocus()
    }
}
