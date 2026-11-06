package de.delorean23.station

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Controller-Belegung wie "Controller einstellen" bei Batocera.
 * Pro Gerät (descriptor) wird gespeichert, welche echte Taste welcher
 * Standard-Taste entspricht. Standard = Xbox-Positionen:
 * BUTTON_A unten, BUTTON_B rechts, BUTTON_X links, BUTTON_Y oben.
 */
object Remap {

    /** Reihenfolge beim Einstellen (Code, Anzeigename) */
    val steps: List<Pair<Int, String>> = listOf(
        KeyEvent.KEYCODE_DPAD_UP to "Steuerkreuz HOCH",
        KeyEvent.KEYCODE_DPAD_DOWN to "Steuerkreuz RUNTER",
        KeyEvent.KEYCODE_DPAD_LEFT to "Steuerkreuz LINKS",
        KeyEvent.KEYCODE_DPAD_RIGHT to "Steuerkreuz RECHTS",
        KeyEvent.KEYCODE_BUTTON_A to "Taste UNTEN (Xbox A)",
        KeyEvent.KEYCODE_BUTTON_B to "Taste RECHTS (Xbox B)",
        KeyEvent.KEYCODE_BUTTON_X to "Taste LINKS (Xbox X)",
        KeyEvent.KEYCODE_BUTTON_Y to "Taste OBEN (Xbox Y)",
        KeyEvent.KEYCODE_BUTTON_L1 to "LB / L1",
        KeyEvent.KEYCODE_BUTTON_R1 to "RB / R1",
        KeyEvent.KEYCODE_BUTTON_L2 to "LT / L2",
        KeyEvent.KEYCODE_BUTTON_R2 to "RT / R2",
        KeyEvent.KEYCODE_BUTTON_SELECT to "SELECT / Ansicht (Hotkey)",
        KeyEvent.KEYCODE_BUTTON_START to "START / Menü",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3 (linker Stick drücken)",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "R3 (rechter Stick drücken)"
    )

    private val cache = HashMap<String, Map<Int, Int>>()

    private fun prefs(c: Context) = c.getSharedPreferences("remap", Context.MODE_PRIVATE)

    fun key(device: InputDevice?): String = device?.descriptor ?: device?.name ?: "unknown"

    fun load(c: Context, device: InputDevice?): Map<Int, Int> {
        val k = key(device)
        cache[k]?.let { return it }
        val json = prefs(c).getString(k, null)
        val map = HashMap<Int, Int>()
        if (json != null) try {
            val o = JSONObject(json)
            o.keys().forEach { map[it.toInt()] = o.getInt(it) }
        } catch (_: Exception) {}
        cache[k] = map
        return map
    }

    fun save(c: Context, device: InputDevice?, map: Map<Int, Int>) {
        val o = JSONObject()
        map.forEach { (from, to) -> o.put(from.toString(), to) }
        prefs(c).edit().putString(key(device), o.toString())
            .putString("name_" + key(device), device?.name ?: "Controller").apply()
        cache[key(device)] = map
    }

    fun reset(c: Context, device: InputDevice?) {
        prefs(c).edit().remove(key(device)).apply()
        cache.remove(key(device))
    }

    /** Wandelt einen echten Tastencode in die Standard-Taste um (ohne Belegung unverändert). */
    fun translate(c: Context, event: KeyEvent): Int {
        val map = load(c, event.device)
        return map[event.keyCode] ?: event.keyCode
    }

    fun isGamepadEvent(event: KeyEvent): Boolean {
        val s = event.source
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                KeyEvent.isGamepadButton(event.keyCode)
    }
}
