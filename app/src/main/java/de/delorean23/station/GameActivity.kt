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
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.floor

/**
 * Startet ein Spiel mit dem eingebauten Libretro-Core.
 * Spielstände: saves/<system>/<rom>.srm, Savestates: saves/<system>/<rom>.state[N]
 * Hotkeys wie bei Batocera (Hotkey = SELECT/BACK).
 */
class GameActivity : AppCompatActivity(), TouchPadView.Listener, InputManager.InputDeviceListener {

    private lateinit var cfg: Config
    private lateinit var root: FrameLayout
    private var retroView: GLRetroView? = null
    private var touchPad: TouchPadView? = null
    private lateinit var saveDir: File
    private lateinit var saveName: String
    private lateinit var system: String
    private lateinit var gamePath: String
    private var slot = 0
    private var fastForward = false
    private var menuOpen = false
    private var emulationPaused = false
    private var quitting = false

    // Hotkey (SELECT gedrückt halten)
    private var hotkeyHeld = false
    private var hotkeyUsed = false

    // Netplay
    private var netplay: Netplay? = null
    private var waitingView: TextView? = null

    // Spielzeit
    private var playStart = 0L
    private var playSeconds = 0L

    // Analog-Zustand für N64-C-Tasten (Touch)
    private var cX = 0; private var cY = 0
    // Zuletzt gemeldete Achsen pro Port (echter Controller)
    private val lastHat = HashMap<Int, Int>()
    private val lastTrig = HashMap<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        system = intent.getStringExtra(EXTRA_SYSTEM) ?: return finish()
        gamePath = intent.getStringExtra(EXTRA_PATH) ?: ""
        saveName = intent.getStringExtra(EXTRA_SAVE_NAME) ?: File(intent.getStringExtra(EXTRA_ROM) ?: "x").nameWithoutExtension
        saveDir = File(cfg.savesDir, system).apply { mkdirs() }
        cfg.biosDir.mkdirs()

        root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        setContentView(root)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)

        when (intent.getStringExtra(EXTRA_NETPLAY)) {
            "host" -> startHost()
            "client" -> startClient()
            else -> startEmulator()
        }
    }

    // ---------------------------------------------------------------- Start

    private fun startEmulator() {
        val core = intent.getStringExtra(EXTRA_CORE) ?: return finish()
        val rom = intent.getStringExtra(EXTRA_ROM) ?: return finish()
        val sram = File(saveDir, "$saveName.srm")
        val vars = ArrayList<Variable>()
        when (core) {
            "tgbdual" -> {
                vars += Variable("tgbdual_gblink_enable", "enabled")
                vars += Variable("tgbdual_screen_placement", "left-right")
                vars += Variable("tgbdual_single_screen_mp", "both players")
            }
            "picodrive" -> if (system == "megadrive" || system == "megacd" || system == "sega32x") {
                vars += Variable("picodrive_input1", "6 button pad")
                vars += Variable("picodrive_input2", "6 button pad")
            }
        }
        val data = GLRetroViewData(this).apply {
            coreFilePath = Systems.coreFile(this@GameActivity, core).absolutePath
            gameFilePath = rom
            systemDirectory = cfg.biosDir.absolutePath
            savesDirectory = saveDir.absolutePath
            variables = vars.toTypedArray()
            saveRAMState = if (sram.isFile) try { sram.readBytes() } catch (e: Exception) { null } else null
            shader = when (cfg.effective(system, gamePath, "shader")) {
                "default" -> ShaderConfig.Default
                "crt" -> ShaderConfig.CRT
                "lcd" -> ShaderConfig.LCD
                "smooth" -> ShaderConfig.CUT()
                else -> ShaderConfig.Sharp
            }
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
        }

        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)
        root.addView(view, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        val layout = PadLayouts.forSystem(system).let { l ->
            l.copy(buttons = l.buttons + PadLayouts.Btn("menu", "☰", PadLayouts.COL_GRAY, PadLayouts.K_MENU,
                PadLayouts.Side.CENTER, 0f, 0.06f, 0.04f))
        }
        touchPad = TouchPadView(this).also {
            it.layout = layout
            it.listener = this
            it.passThrough = true      // z. B. DS-Touchscreen
            it.opacity = cfg.touchOpacity / 100f
            it.vibrate = cfg.touchVibrate
            root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        updateTouchVisibility()
        root.post { applyScaling() }

        netplay?.let { np -> view.frameHook = { np.onFrame() } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                view.getGLRetroErrors().collect { code -> showError(code) }
            }
        }
        // Mega Drive & Co.: 6-Tasten-Controller einstellen
        lifecycleScope.launch {
            delay(1500)
            if (core == "genesis_plus_gx") selectController { it.contains("6") }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(20_000)
                    if (!menuOpen) saveSram(true)
                }
            }
        }
        val np = netplay
        val msg = if (np != null) (if (np.isHost) "Netplay: du bist Spieler 1" else "Netplay: du bist Spieler 2")
        else "${intent.getStringExtra(EXTRA_NAME) ?: ""}\nMenü: ☰ oben oder SELECT + A"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun selectController(match: (String) -> Boolean) {
        val v = retroView ?: return
        try {
            val ctrls = v.getControllers()
            for (port in ctrls.indices) {
                ctrls[port].firstOrNull { match(it.description ?: "") }?.let { v.setControllerType(port, it.id) }
            }
        } catch (_: Exception) {}
    }

    /** Seitenverhältnis und ganzzahlige Skalierung (pro Spiel → System → global) */
    private fun applyScaling() {
        val v = retroView ?: return
        val w = root.width.toFloat(); val h = root.height.toFloat()
        if (w <= 0 || h <= 0) return
        val aspect = cfg.effective(system, gamePath, "aspect")
        val ratio = when (aspect) {
            "4:3" -> 4f / 3f
            "16:9" -> 16f / 9f
            "voll" -> w / h
            else -> -1f
        }
        v.setAspectRatioOverride(ratio)
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (cfg.effective(system, gamePath, "scale") == "integer" && aspect != "voll") {
            val (bw, bh) = SystemInfo.resolution(system)
            val sys = Systems.byId(system)
            val boxAspect = if (ratio > 0) ratio else if (sys?.hardware == "portable") bw.toFloat() / bh else 4f / 3f
            var k = floor(h / bh).toInt().coerceAtLeast(1)
            while (k > 1 && bh * k * boxAspect > w) k--
            lp.width = (bh * k * boxAspect).toInt(); lp.height = bh * k
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        lp.gravity = Gravity.CENTER
        v.layoutParams = lp
    }

    // ---------------------------------------------------------------- Netplay-Start

    private fun showWaiting(text: String?) {
        runOnUiThread {
            if (text == null) { waitingView?.let { root.removeView(it) }; waitingView = null; return@runOnUiThread }
            val tv = waitingView ?: TextView(this).also {
                it.setTextColor(0xFFFFFFFF.toInt()); it.textSize = 20f; it.gravity = Gravity.CENTER
                it.setBackgroundColor(0xC0000000.toInt()); it.setPadding(40, 30, 40, 30)
                root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                waitingView = it
            }
            tv.text = text
        }
    }

    @Volatile private var hostCancelled = false

    private fun startHost() {
        showWaiting("Netplay: warte auf Spieler 2 …\n${intent.getStringExtra(EXTRA_NAME)}\n\nZurück = Abbrechen")
        lifecycleScope.launch {
            val np = withContext(Dispatchers.IO) {
                try {
                    Netplay.host(Netplay.HostInfo(system, gamePath, intent.getStringExtra(EXTRA_NAME) ?: "", cfg.netplayName)) { hostCancelled }
                } catch (e: Exception) { null }
            }
            if (np == null) { if (!hostCancelled) toast("Netplay konnte nicht gestartet werden"); finish(); return@launch }
            attachNetplay(np)
            showWaiting(null)
            startEmulator()
        }
    }

    private fun startClient() {
        showWaiting("Netplay: verbinde mit ${intent.getStringExtra(EXTRA_HOST)} …")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Result.success(Netplay.join(intent.getStringExtra(EXTRA_HOST) ?: "", intent.getIntExtra(EXTRA_PORT, Netplay.TCP_PORT))) }
                catch (e: Exception) { Result.failure<Netplay>(e) }
            }
            r.onSuccess { np -> attachNetplay(np); showWaiting(null); startEmulator() }
                .onFailure { e -> toast("Verbindung fehlgeschlagen: ${e.message}"); finish() }
        }
    }

    private fun attachNetplay(np: Netplay) {
        netplay = np
        np.listener = object : Netplay.Listener {
            override fun onWaiting(waiting: Boolean) {
                showWaiting(if (waiting) "Warte auf Mitspieler …" else null)
            }
            override fun onDisconnected(reason: String) {
                retroView?.frameHook = null
                netplay = null
                showWaiting(null)
                toast("$reason Du spielst allein weiter.")
            }
        }
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
            GLRetroView.ERROR_LOAD_GAME -> "Das Spiel konnte nicht geladen werden.\nFehlt eventuell ein BIOS im Ordner\n${cfg.biosDir.path}?\n(Menü → Fehlende BIOS prüfen)"
            GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "Dieses Gerät unterstützt die nötige Grafik (OpenGL ES) für diesen Emulator nicht."
            else -> "Fehler beim Starten des Emulators ($code)."
        }
        Ui.gamepad(AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle("Fehler").setMessage(msg).setCancelable(false)
            .setPositiveButton("Zurück") { _, _ -> quit(save = false) }.create(), cfg).show()
    }

    // ---------------------------------------------------------------- Touch-Sichtbarkeit

    private fun touchVisible() = touchPad?.visibility == View.VISIBLE

    private fun updateTouchVisibility() {
        val show = TouchPadView.shouldShow(cfg.touchMode)
        touchPad?.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) touchPad?.releaseAll()
    }

    override fun onInputDeviceAdded(deviceId: Int) = updateTouchVisibility()
    override fun onInputDeviceRemoved(deviceId: Int) = updateTouchVisibility()
    override fun onInputDeviceChanged(deviceId: Int) = updateTouchVisibility()

    // ---------------------------------------------------------------- Eingabe an den Emulator

    /** retroKey = KeyEvent-Code in Libretro-Bedeutung (BUTTON_A = rechts, BUTTON_B = unten) */
    private fun pressRetro(port: Int, retroKey: Int, down: Boolean) {
        val np = netplay
        if (np != null) { np.setKey(retroKey, down); return }
        retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, retroKey, port)
    }

    private fun stickRetro(port: Int, left: Boolean, x: Float, y: Float) {
        val np = netplay
        if (np != null) { np.setStick(left, x, y); return }
        retroView?.sendMotionEvent(if (left) GLRetroView.MOTION_SOURCE_ANALOG_LEFT else GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y, port)
    }

    /** Xbox-Position (Android-Standard) → Libretro-Bedeutung */
    private fun positionalToRetro(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
        else -> code
    }

    private fun portFor(device: InputDevice?): Int {
        val n = (device?.controllerNumber ?: 1).coerceAtLeast(1) - 1
        // Wenn der Touch-Controller sichtbar ist, ist er Spieler 1 und echte Controller rücken nach
        return (if (touchVisible()) n + 1 else n).coerceIn(0, 3)
    }

    override fun onKey(key: Int, pressed: Boolean) {
        if (menuOpen) return
        when (key) {
            PadLayouts.K_MENU -> { if (pressed) openMenu(); return }
            PadLayouts.K_C_UP, PadLayouts.K_C_DOWN, PadLayouts.K_C_LEFT, PadLayouts.K_C_RIGHT -> {
                val d = if (pressed) 1 else 0
                when (key) {
                    PadLayouts.K_C_UP -> cY = if (pressed) -1 else if (cY == -1) 0 else cY
                    PadLayouts.K_C_DOWN -> cY = if (pressed) 1 else if (cY == 1) 0 else cY
                    PadLayouts.K_C_LEFT -> cX = if (pressed) -1 else if (cX == -1) 0 else cX
                    else -> cX = if (pressed) d else if (cX == 1) 0 else cX
                }
                stickRetro(0, false, cX.toFloat(), cY.toFloat())
                return
            }
        }
        if (key == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (pressed) { hotkeyHeld = true; hotkeyUsed = false } else hotkeyHeld = false
        } else if (hotkeyHeld && pressed && hotkeyAction(key)) {
            hotkeyUsed = true; return
        }
        pressRetro(0, key, pressed)
    }

    override fun onStick(left: Boolean, x: Float, y: Float) {
        if (!menuOpen) stickRetro(0, left, x, y)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuOpen) return super.dispatchKeyEvent(event)
        val raw = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (raw == KeyEvent.KEYCODE_VOLUME_UP || raw == KeyEvent.KEYCODE_VOLUME_DOWN || raw == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.dispatchKeyEvent(event)
        if (raw == KeyEvent.KEYCODE_BACK && !Remap.isGamepadEvent(event)) {
            if (down && event.repeatCount == 0) {
                if (waitingView != null && netplay == null) { hostCancelled = true; finish() } else openMenu()
            }
            return true
        }
        if (raw == KeyEvent.KEYCODE_BUTTON_MODE) { if (down) openMenu(); return true }
        if (event.repeatCount > 0) return true

        val code = Remap.translate(this, event).let { if (it == KeyEvent.KEYCODE_BACK) KeyEvent.KEYCODE_BUTTON_SELECT else it }
        val retro = positionalToRetro(code)
        val port = portFor(event.device)

        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (down) { hotkeyHeld = true; hotkeyUsed = false } else hotkeyHeld = false
        } else if (hotkeyHeld && down) {
            if (hotkeyAction(retro)) { hotkeyUsed = true; return true }
        }
        pressRetro(port, retro, down)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (menuOpen) return super.dispatchGenericMotionEvent(event)
        val src = event.source
        if ((src and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK && (src and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD)
            return super.dispatchGenericMotionEvent(event)
        val port = portFor(event.device)
        val dev = event.device
        fun axis(a: Int): Float {
            val v = event.getAxisValue(a)
            val flat = dev?.getMotionRange(a, src)?.flat ?: 0.1f
            return if (abs(v) < maxOf(flat, 0.12f)) 0f else v
        }
        stickRetro(port, true, axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y))
        stickRetro(port, false, axis(MotionEvent.AXIS_Z), axis(MotionEvent.AXIS_RZ))

        // Steuerkreuz als Hat-Achse → Tasten
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hat = (if (hy < -0.5f) 1 else 0) or (if (hy > 0.5f) 2 else 0) or (if (hx < -0.5f) 4 else 0) or (if (hx > 0.5f) 8 else 0)
        val prevHat = lastHat[port] ?: 0
        if (hat != prevHat) {
            val keys = intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            for (i in 0..3) {
                val now = (hat shr i) and 1; val before = (prevHat shr i) and 1
                if (now != before) {
                    val mapped = Remap.load(this, dev)[keys[i]] ?: keys[i]
                    val retro = positionalToRetro(mapped)
                    if (now == 1 && hotkeyHeld && hotkeyAction(retro)) { hotkeyUsed = true; continue }
                    pressRetro(port, retro, now == 1)
                }
            }
            lastHat[port] = hat
        }
        // Trigger als Achsen → L2/R2
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        val trig = (if (lt > 0.5f) 1 else 0) or (if (rt > 0.5f) 2 else 0)
        val prevTrig = lastTrig[port] ?: 0
        if (trig != prevTrig) {
            if ((trig and 1) != (prevTrig and 1)) pressRetro(port, Remap.load(this, dev)[KeyEvent.KEYCODE_BUTTON_L2] ?: KeyEvent.KEYCODE_BUTTON_L2, (trig and 1) != 0)
            if ((trig and 2) != (prevTrig and 2)) pressRetro(port, Remap.load(this, dev)[KeyEvent.KEYCODE_BUTTON_R2] ?: KeyEvent.KEYCODE_BUTTON_R2, (trig and 2) != 0)
            lastTrig[port] = trig
        }
        return true
    }

    /**
     * Batocera-Hotkeys (SELECT halten), Belegung nach RetroPad:
     * START = Beenden, B (unten) = Menü, A (rechts) = Neustart, Y (links) = Speichern,
     * X (oben) = Laden, Hoch/Runter = Slot, Rechts = Schnellvorlauf.
     */
    private fun hotkeyAction(retroKey: Int): Boolean {
        val net = netplay != null
        when (retroKey) {
            KeyEvent.KEYCODE_BUTTON_START -> quit(save = true)
            KeyEvent.KEYCODE_BUTTON_B -> openMenu()
            KeyEvent.KEYCODE_BUTTON_A -> if (net) toast("Im Netplay nicht möglich") else { retroView?.reset(); toast("Neustart") }
            KeyEvent.KEYCODE_BUTTON_Y -> if (net) toast("Im Netplay nicht möglich") else saveState()
            KeyEvent.KEYCODE_BUTTON_X -> if (net) toast("Im Netplay nicht möglich") else loadState()
            KeyEvent.KEYCODE_DPAD_UP -> { slot = (slot + 1) % 10; toast("Slot $slot") }
            KeyEvent.KEYCODE_DPAD_DOWN -> { slot = (slot + 9) % 10; toast("Slot $slot") }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (net) toast("Im Netplay nicht möglich") else toggleFastForward()
            else -> return false
        }
        pressRetro(0, KeyEvent.KEYCODE_BUTTON_SELECT, false)
        return true
    }

    private fun toggleFastForward() {
        fastForward = !fastForward
        retroView?.frameSpeed = if (fastForward) 3 else 1
        toast(if (fastForward) "Schnellvorlauf an" else "Schnellvorlauf aus")
    }

    // ---------------------------------------------------------------- Speichern

    private fun stateFile(): File = File(saveDir, if (slot == 0) "$saveName.state" else "$saveName.state$slot")

    private fun saveState() {
        val v = retroView ?: return
        try {
            val bytes = v.serializeState()
            if (bytes.isEmpty()) { toast("Dieser Emulator unterstützt keine Savestates"); return }
            stateFile().writeBytes(bytes)
            toast("Gespeichert (Slot $slot)")
        } catch (e: Exception) { toast("Speichern fehlgeschlagen") }
    }

    private fun loadState() {
        val v = retroView ?: return
        val f = stateFile()
        if (!f.isFile) { toast("Kein Savestate in Slot $slot"); return }
        try { toast(if (v.unserializeState(f.readBytes())) "Geladen (Slot $slot)" else "Laden fehlgeschlagen") }
        catch (e: Exception) { toast("Laden fehlgeschlagen") }
    }

    private fun saveSram(onEmuThread: Boolean) {
        val v = retroView ?: return
        try {
            val bytes = v.serializeSRAM(onEmuThread)
            if (bytes.isNotEmpty()) {
                val f = File(saveDir, "$saveName.srm")
                val tmp = File(saveDir, "$saveName.srm.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        } catch (_: Exception) {}
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // ---------------------------------------------------------------- Spielmenü

    private fun pauseEmulation(p: Boolean) {
        if (netplay != null) { netplay?.paused = p; return }   // im Netplay läuft das Spiel weiter
        val v = retroView ?: return
        if (p == emulationPaused) return
        emulationPaused = p
        if (p) v.onPause() else v.onResume()
        v.audioEnabled = !p
    }

    private fun openMenu() {
        if (menuOpen || retroView == null) return
        menuOpen = true
        touchPad?.releaseAll()
        val net = netplay != null
        val disks = try { retroView?.getAvailableDisks() ?: 0 } catch (e: Exception) { 0 }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Weiterspielen" to {}
        if (!net) {
            items += "Speichern (Slot $slot)" to { saveState() }
            items += "Laden (Slot $slot)" to { loadState() }
            items += "Slot wählen" to { pickSlot() }
            items += (if (fastForward) "Schnellvorlauf aus" else "Schnellvorlauf an") to { toggleFastForward() }
        }
        if (disks > 1) items += "Disc wechseln" to { pickDisk(disks) }
        items += "Bild: Seitenverhältnis" to { pickAspect() }
        items += "Bild: Skalierung" to { pickScale() }
        if (!net) items += "Neustart" to { retroView?.reset() }
        if (net) items += "Netplay beenden" to { netplay?.stop(); netplay = null; retroView?.frameHook = null; toast("Netplay beendet") }
        items += "Beenden" to { quit(save = true) }

        val dlg = AlertDialog.Builder(this, R.style.StationDialog)
            .setCustomTitle(TextView(this).apply {
                text = intent.getStringExtra(EXTRA_NAME) ?: "Spielmenü"
                textSize = 20f; setPadding(48, 36, 48, 12); setTextColor(0xFFFFFFFF.toInt())
            })
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                menuOpen = false
                pauseEmulation(false)
                retroView?.post { items[which].second() }
            }
            .setOnCancelListener { menuOpen = false; pauseEmulation(false) }
            .create()
        Ui.gamepad(dlg, cfg)
        pauseEmulation(true)
        dlg.show()
    }

    private fun subMenu(title: String, names: List<String>, selected: Int, onPick: (Int) -> Unit) {
        menuOpen = true
        pauseEmulation(true)
        val d = AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), selected) { d, which -> d.dismiss(); onPick(which) }
            .setOnDismissListener { menuOpen = false; pauseEmulation(false) }
            .create()
        Ui.gamepad(d, cfg).show()
    }

    private fun pickAspect() {
        val keys = listOf("", "core", "4:3", "16:9", "voll")
        val names = listOf("Standard", "Original (Emulator)", "4:3", "16:9", "Bild füllen")
        subMenu("Seitenverhältnis (dieses Spiel)", names, keys.indexOf(cfg.gameSetting(system, gamePath, "aspect")).coerceAtLeast(0)) {
            cfg.setGameSetting(system, gamePath, "aspect", keys[it]); applyScaling()
        }
    }

    private fun pickScale() {
        val keys = listOf("", "normal", "integer")
        val names = listOf("Standard", "Bildschirm füllen", "Ganzzahlig (pixelgenau)")
        subMenu("Skalierung (dieses Spiel)", names, keys.indexOf(cfg.gameSetting(system, gamePath, "scale")).coerceAtLeast(0)) {
            cfg.setGameSetting(system, gamePath, "scale", keys[it]); applyScaling()
        }
    }

    private fun pickSlot() {
        val names = (0..9).map { s ->
            val f = File(saveDir, if (s == 0) "$saveName.state" else "$saveName.state$s")
            "Slot $s" + if (f.isFile) "  ●" else ""
        }
        subMenu("Slot wählen", names, slot) { slot = it }
    }

    private fun pickDisk(count: Int) {
        val current = try { retroView?.getCurrentDisk() ?: 0 } catch (e: Exception) { 0 }
        subMenu("Disc wechseln", (1..count).map { "Disc $it" }, current) { which ->
            retroView?.post { try { retroView?.changeDisk(which) } catch (_: Exception) {} }
        }
    }

    private fun quit(save: Boolean) {
        if (quitting) return
        quitting = true
        netplay?.stop(); netplay = null
        retroView?.frameHook = null
        if (save && retroView != null) {
            if (emulationPaused) pauseEmulation(false)
            saveSram(true)
        }
        recordPlayTime()
        finish()
    }

    /** Spielzeit wie bei Batocera in der lokalen gamelist.xml (gametime in Sekunden) */
    private fun recordPlayTime() {
        if (playStart > 0) { playSeconds += (System.currentTimeMillis() - playStart) / 1000; playStart = 0 }
        if (playSeconds < 5 || gamePath.isEmpty()) return
        val secs = playSeconds
        playSeconds = 0
        try {
            GameList.updateEntry(File(File(cfg.romsDir, system), "gamelist.xml"), gamePath,
                intent.getStringExtra(EXTRA_NAME) ?: saveName) { m ->
                m["gametime"] = ((m["gametime"]?.toLongOrNull() ?: 0) + secs).toString()
            }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onResume() {
        super.onResume()
        playStart = System.currentTimeMillis()
        updateTouchVisibility()
    }

    override fun onPause() {
        if (playStart > 0) { playSeconds += (System.currentTimeMillis() - playStart) / 1000; playStart = 0 }
        if (!quitting) saveSram(false)
        super.onPause()
    }

    override fun onDestroy() {
        hostCancelled = true
        netplay?.stop()
        recordPlayTime()
        try { (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this) } catch (_: Exception) {}
        super.onDestroy()
        if (isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        const val EXTRA_CORE = "core"
        const val EXTRA_ROM = "rom"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_SAVE_NAME = "saveName"
        const val EXTRA_NETPLAY = "netplay"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }
}
