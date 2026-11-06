package de.delorean23.station

import android.content.Context
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Startet ein Spiel mit dem eingebauten Libretro-Core.
 * Spielstände: saves/<system>/<rom>.srm, Savestates: saves/<system>/<rom>.state[N]
 * Hotkeys wie bei Batocera mit der Ansicht-Taste (Select) als Hotkey.
 */
class GameActivity : AppCompatActivity(), TouchPadView.Listener, InputManager.InputDeviceListener {

    private lateinit var cfg: Config
    private lateinit var retroView: GLRetroView
    private var touchPad: TouchPadView? = null
    private lateinit var saveDir: File
    private lateinit var saveName: String
    private var slot = 0
    private var fastForward = false
    private var menuOpen = false
    private var emulationPaused = false

    // Hotkey
    private var hotkeyHeld = false
    private var hotkeyUsed = false
    private var touchHotkey = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val core = intent.getStringExtra(EXTRA_CORE) ?: return finish()
        val rom = intent.getStringExtra(EXTRA_ROM) ?: return finish()
        val system = intent.getStringExtra(EXTRA_SYSTEM) ?: return finish()
        saveName = intent.getStringExtra(EXTRA_SAVE_NAME) ?: File(rom).nameWithoutExtension
        saveDir = File(cfg.savesDir, system).apply { mkdirs() }
        cfg.biosDir.mkdirs()

        val sram = File(saveDir, "$saveName.srm")
        val data = GLRetroViewData(this).apply {
            coreFilePath = Systems.coreFile(this@GameActivity, core).absolutePath
            gameFilePath = rom
            systemDirectory = cfg.biosDir.absolutePath
            savesDirectory = saveDir.absolutePath
            saveRAMState = if (sram.isFile) try { sram.readBytes() } catch (e: Exception) { null } else null
            shader = when (cfg.shader) {
                "default" -> ShaderConfig.Default
                "crt" -> ShaderConfig.CRT
                "lcd" -> ShaderConfig.LCD
                "smooth" -> ShaderConfig.CUT()
                else -> ShaderConfig.Sharp
            }
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
        }

        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        root.addView(retroView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        touchPad = TouchPadView(this).also {
            it.listener = this
            it.opacity = cfg.touchOpacity / 100f
            it.vibrate = cfg.touchVibrate
            root.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        updateTouchVisibility()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                retroView.getGLRetroErrors().collect { code -> showError(code) }
            }
        }
        // Spielstand (SRAM) regelmäßig sichern
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(20_000)
                    if (!menuOpen) saveSram(true)
                }
            }
        }
        Toast.makeText(this, "${intent.getStringExtra(EXTRA_NAME) ?: ""}\nHotkey: Ansicht-Taste ⧉ + Menü ≡ = Beenden", Toast.LENGTH_LONG).show()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun showError(code: Int) {
        val msg = when (code) {
            GLRetroView.ERROR_LOAD_LIBRARY -> "Der Emulator-Core konnte nicht geladen werden."
            GLRetroView.ERROR_LOAD_GAME -> "Das Spiel konnte nicht geladen werden.\nFehlt eventuell ein BIOS im Ordner\n${cfg.biosDir.path}?"
            GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "Dieses Gerät unterstützt die nötige Grafik (OpenGL ES) für diesen Emulator nicht."
            else -> "Fehler beim Starten des Emulators ($code)."
        }
        AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle("Fehler")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Zurück") { _, _ -> quit(save = false) }
            .show()
    }

    // ---------------------------------------------------------------- Controller erkannt?

    private fun hasGamepad(): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) continue
            val s = d.sources
            if ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true
        }
        return false
    }

    private fun updateTouchVisibility() {
        val show = when (cfg.touchMode) {
            "an" -> true
            "aus" -> false
            else -> !hasGamepad()
        }
        touchPad?.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) touchPad?.releaseAll()
    }

    override fun onInputDeviceAdded(deviceId: Int) = updateTouchVisibility()
    override fun onInputDeviceRemoved(deviceId: Int) = updateTouchVisibility()
    override fun onInputDeviceChanged(deviceId: Int) = updateTouchVisibility()

    // ---------------------------------------------------------------- Touch-Controller

    override fun onButton(btn: TouchPadView.Btn, pressed: Boolean) {
        if (menuOpen) return
        if (btn == TouchPadView.Btn.GUIDE) { if (pressed) openMenu(); return }
        if (btn == TouchPadView.Btn.VIEW) touchHotkey = pressed
        if (touchHotkey && pressed && btn != TouchPadView.Btn.VIEW && hotkeyAction(touchToKey(btn))) {
            hotkeyUsed = true
            return
        }
        val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        retroView.sendKeyEvent(action, touchToKey(btn), 0)
    }

    override fun onStick(left: Boolean, x: Float, y: Float) {
        if (menuOpen) return
        retroView.sendMotionEvent(
            if (left) GLRetroView.MOTION_SOURCE_ANALOG_LEFT else GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y, 0)
    }

    /** Xbox-Position → RetroPad (Libretro): A unten = B, B rechts = A, X links = Y, Y oben = X */
    private fun touchToKey(btn: TouchPadView.Btn): Int = when (btn) {
        TouchPadView.Btn.A -> KeyEvent.KEYCODE_BUTTON_B
        TouchPadView.Btn.B -> KeyEvent.KEYCODE_BUTTON_A
        TouchPadView.Btn.X -> KeyEvent.KEYCODE_BUTTON_Y
        TouchPadView.Btn.Y -> KeyEvent.KEYCODE_BUTTON_X
        TouchPadView.Btn.LB -> KeyEvent.KEYCODE_BUTTON_L1
        TouchPadView.Btn.RB -> KeyEvent.KEYCODE_BUTTON_R1
        TouchPadView.Btn.LT -> KeyEvent.KEYCODE_BUTTON_L2
        TouchPadView.Btn.RT -> KeyEvent.KEYCODE_BUTTON_R2
        TouchPadView.Btn.L3 -> KeyEvent.KEYCODE_BUTTON_THUMBL
        TouchPadView.Btn.R3 -> KeyEvent.KEYCODE_BUTTON_THUMBR
        TouchPadView.Btn.VIEW -> KeyEvent.KEYCODE_BUTTON_SELECT
        TouchPadView.Btn.MENU -> KeyEvent.KEYCODE_BUTTON_START
        TouchPadView.Btn.UP -> KeyEvent.KEYCODE_DPAD_UP
        TouchPadView.Btn.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        TouchPadView.Btn.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        TouchPadView.Btn.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        TouchPadView.Btn.GUIDE -> KeyEvent.KEYCODE_BUTTON_MODE
    }

    // ---------------------------------------------------------------- Echter Controller

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuOpen) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) openMenu()
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (down) openMenu()
            return true
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.dispatchKeyEvent(event)

        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (down) { hotkeyHeld = true; hotkeyUsed = false }
            else hotkeyHeld = false
        } else if (hotkeyHeld && down && event.repeatCount == 0) {
            // Physische Tasten: Xbox-Belegung (Android meldet A unten, B rechts usw.)
            val retro = when (code) {
                KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
                KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
                else -> code
            }
            if (hotkeyAction(retro)) { hotkeyUsed = true; return true }
        } else if (hotkeyHeld && !down && hotkeyUsed && code != KeyEvent.KEYCODE_BUTTON_SELECT) {
            return true
        }

        val handled = if (down) retroView.onKeyDown(code, event) else retroView.onKeyUp(code, event)
        return handled || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (menuOpen) return super.dispatchGenericMotionEvent(event)
        return retroView.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
    }

    /**
     * Batocera-Hotkeys (Hotkey = Ansicht/Select). Belegung nach RetroPad-Namen:
     * Start = Beenden, B (unten) = Menü, A (rechts) = Neustart, Y (links) = Speichern,
     * X (oben) = Laden, Hoch/Runter = Slot, Rechts = Schnellvorlauf.
     */
    private fun hotkeyAction(retroKey: Int): Boolean {
        when (retroKey) {
            KeyEvent.KEYCODE_BUTTON_START -> quit(save = true)
            KeyEvent.KEYCODE_BUTTON_B -> openMenu()
            KeyEvent.KEYCODE_BUTTON_A -> { retroView.reset(); toast("Neustart") }
            KeyEvent.KEYCODE_BUTTON_Y -> saveState()
            KeyEvent.KEYCODE_BUTTON_X -> loadState()
            KeyEvent.KEYCODE_DPAD_UP -> { slot = (slot + 1) % 10; toast("Slot $slot") }
            KeyEvent.KEYCODE_DPAD_DOWN -> { slot = (slot + 9) % 10; toast("Slot $slot") }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                fastForward = !fastForward
                retroView.frameSpeed = if (fastForward) 3 else 1
                toast(if (fastForward) "Schnellvorlauf an" else "Schnellvorlauf aus")
            }
            else -> return false
        }
        // Select im Spiel loslassen, damit der Core keine hängende Taste hat
        retroView.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT, 0)
        return true
    }

    // ---------------------------------------------------------------- Speichern

    private fun stateFile(): File = File(saveDir, if (slot == 0) "$saveName.state" else "$saveName.state$slot")

    private fun saveState() {
        try {
            val bytes = retroView.serializeState()
            if (bytes.isEmpty()) { toast("Dieser Emulator unterstützt keine Savestates"); return }
            stateFile().writeBytes(bytes)
            toast("Gespeichert (Slot $slot)")
        } catch (e: Exception) { toast("Speichern fehlgeschlagen") }
    }

    private fun loadState() {
        val f = stateFile()
        if (!f.isFile) { toast("Kein Savestate in Slot $slot"); return }
        try {
            val ok = retroView.unserializeState(f.readBytes())
            toast(if (ok) "Geladen (Slot $slot)" else "Laden fehlgeschlagen")
        } catch (e: Exception) { toast("Laden fehlgeschlagen") }
    }

    private fun saveSram(onEmuThread: Boolean) {
        try {
            val bytes = retroView.serializeSRAM(onEmuThread)
            if (bytes.isNotEmpty()) {
                val f = File(saveDir, "$saveName.srm")
                val tmp = File(saveDir, "$saveName.srm.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        } catch (_: Exception) {}
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- Spielmenü

    private fun pauseEmulation(p: Boolean) {
        if (p == emulationPaused) return
        emulationPaused = p
        if (p) retroView.onPause() else retroView.onResume()
        retroView.audioEnabled = !p
    }

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        touchPad?.releaseAll()
        val disks = try { retroView.getAvailableDisks() } catch (e: Exception) { 0 }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Weiterspielen" to {}
        items += "Speichern (Slot $slot)" to { saveState() }
        items += "Laden (Slot $slot)" to { loadState() }
        items += "Slot wählen" to { pickSlot() }
        items += (if (fastForward) "Schnellvorlauf aus" else "Schnellvorlauf an") to {
            fastForward = !fastForward; retroView.frameSpeed = if (fastForward) 3 else 1
        }
        if (disks > 1) items += "Disc wechseln" to { pickDisk(disks) }
        items += "Neustart" to { retroView.reset() }
        items += "Beenden" to { quit(save = true) }

        // Für Speichern/Laden muss die Emulation laufen – erst nach der Auswahl pausieren wir nicht mehr
        val dlg = AlertDialog.Builder(this, R.style.StationDialog)
            .setCustomTitle(TextView(this).apply {
                text = intent.getStringExtra(EXTRA_NAME) ?: "Spielmenü"
                textSize = 20f; setPadding(48, 36, 48, 12); setTextColor(0xFFFFFFFF.toInt())
            })
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                menuOpen = false
                pauseEmulation(false)
                retroView.post { items[which].second() }
            }
            .setOnCancelListener { menuOpen = false; pauseEmulation(false) }
            .create()
        pauseEmulation(true)
        dlg.show()
    }

    private fun pickSlot() {
        menuOpen = true
        pauseEmulation(true)
        val names = (0..9).map { s ->
            val f = File(saveDir, if (s == 0) "$saveName.state" else "$saveName.state$s")
            "Slot $s" + if (f.isFile) "  ●" else ""
        }
        AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle("Slot wählen")
            .setSingleChoiceItems(names.toTypedArray(), slot) { d, which ->
                slot = which; d.dismiss()
            }
            .setOnDismissListener { menuOpen = false; pauseEmulation(false) }
            .show()
    }

    private fun pickDisk(count: Int) {
        menuOpen = true
        pauseEmulation(true)
        val current = try { retroView.getCurrentDisk() } catch (e: Exception) { 0 }
        AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle("Disc wechseln")
            .setSingleChoiceItems((1..count).map { "Disc $it" }.toTypedArray(), current) { d, which ->
                d.dismiss()
                pauseEmulation(false)
                retroView.post { try { retroView.changeDisk(which) } catch (_: Exception) {} }
            }
            .setOnDismissListener { menuOpen = false; pauseEmulation(false) }
            .show()
    }

    private var quitting = false

    private fun quit(save: Boolean) {
        if (quitting) return
        quitting = true
        if (save) {
            if (emulationPaused) pauseEmulation(false)
            saveSram(true)
        }
        finish()
    }

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onPause() {
        // GL-Thread ist hier evtl. schon pausiert → direkt lesen
        if (!quitting) saveSram(false)
        super.onPause()
    }

    override fun onDestroy() {
        try { (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this) } catch (_: Exception) {}
        super.onDestroy()
        // Eigener Prozess: komplett beenden, damit der nächste Core sauber startet
        if (isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        const val EXTRA_CORE = "core"
        const val EXTRA_ROM = "rom"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_NAME = "name"
        const val EXTRA_SAVE_NAME = "saveName"
    }
}
