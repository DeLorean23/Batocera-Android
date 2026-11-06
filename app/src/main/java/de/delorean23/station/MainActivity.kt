package de.delorean23.station

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity(), FrontendView.Listener, InputManager.InputDeviceListener {

    private lateinit var cfg: Config
    private lateinit var view: FrontendView
    private var library: Library? = null
    private var loadJob: Job? = null
    private var launching = false
    private var lastConfigStamp = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = FrontendView(this)
        view.listener = this
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // Voller Xbox-One-Touch-Controller auch in der Oberfläche
        pad = TouchPadView(this).also {
            it.passThrough = true
            it.listener = padListener
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
        hideSystemUi()
    }

    // ---------------------------------------------------------------- Touch-Controller

    private lateinit var pad: TouchPadView
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatAction: (() -> Unit)? = null
    private var stickDir = 0   // 0 = Mitte, 1 hoch, 2 runter, 3 links, 4 rechts

    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatAction?.let { it(); repeatHandler.postDelayed(this, 90) }
        }
    }

    private fun startRepeat(action: () -> Unit) {
        stopRepeat()
        action()
        repeatAction = action
        repeatHandler.postDelayed(repeatRunnable, 380)
    }

    private fun stopRepeat() {
        repeatAction = null
        repeatHandler.removeCallbacks(repeatRunnable)
    }

    private fun updatePad() {
        pad.opacity = cfg.touchOpacity / 100f
        pad.vibrate = cfg.touchVibrate
        val show = TouchPadView.shouldShow(cfg.touchMode)
        pad.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) { pad.releaseAll(); stopRepeat() }
    }

    override fun onInputDeviceAdded(deviceId: Int) = updatePad()
    override fun onInputDeviceRemoved(deviceId: Int) = updatePad()
    override fun onInputDeviceChanged(deviceId: Int) = updatePad()

    private val padListener = object : TouchPadView.Listener {
        override fun onButton(btn: TouchPadView.Btn, pressed: Boolean) {
            val confirmBtn = if (cfg.swapAB) TouchPadView.Btn.A else TouchPadView.Btn.B
            val backBtn = if (cfg.swapAB) TouchPadView.Btn.B else TouchPadView.Btn.A
            if (!pressed) {
                if (btn in TouchPadView.dpadBtns || btn == TouchPadView.Btn.LT || btn == TouchPadView.Btn.RT) stopRepeat()
                return
            }
            when (btn) {
                TouchPadView.Btn.UP -> startRepeat { view.moveVertical(-1) }
                TouchPadView.Btn.DOWN -> startRepeat { view.moveVertical(1) }
                TouchPadView.Btn.LEFT -> startRepeat { view.moveHorizontal(-1) }
                TouchPadView.Btn.RIGHT -> startRepeat { view.moveHorizontal(1) }
                TouchPadView.Btn.LT -> startRepeat { view.moveVertical(-10) }
                TouchPadView.Btn.RT -> startRepeat { view.moveVertical(10) }
                TouchPadView.Btn.LB -> view.jumpLetter(-1)
                TouchPadView.Btn.RB -> view.jumpLetter(1)
                TouchPadView.Btn.MENU, TouchPadView.Btn.GUIDE -> onMenu()
                confirmBtn -> view.confirm()
                backBtn -> if (!view.back()) askExit()
                else -> {}
            }
        }

        override fun onStick(left: Boolean, x: Float, y: Float) {
            if (!left) return
            val dir = when {
                kotlin.math.abs(x) < 0.5f && kotlin.math.abs(y) < 0.5f -> 0
                kotlin.math.abs(y) >= kotlin.math.abs(x) -> if (y < 0) 1 else 2
                else -> if (x < 0) 3 else 4
            }
            if (dir == stickDir) return
            stickDir = dir
            when (dir) {
                0 -> stopRepeat()
                1 -> startRepeat { view.moveVertical(-1) }
                2 -> startRepeat { view.moveVertical(1) }
                3 -> startRepeat { view.moveHorizontal(-1) }
                4 -> startRepeat { view.moveHorizontal(1) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        launching = false
        updatePad()
        if (!hasStorage()) {
            askStorage()
            return
        }
        val stamp = configStamp()
        if (stamp != lastConfigStamp || library == null) {
            lastConfigStamp = stamp
            reload()
        }
    }

    private fun configStamp() = listOf(cfg.baseDir, cfg.storage, cfg.smbHost, cfg.smbShare, cfg.smbPath,
        cfg.smbUser, cfg.smbPassword, cfg.theme, cfg.showHidden).joinToString("|")

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

    // ---------------------------------------------------------------- Speicherzugriff

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle("Speicherzugriff")
            .setMessage("Damit ROMs, BIOS, Spielstände und Themes im Ordner \"batocera\" gelesen und gespeichert werden können, braucht die App Zugriff auf alle Dateien.")
            .setCancelable(false)
            .setPositiveButton("Erlauben") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                }
            }
            .setNegativeButton("Beenden") { _, _ -> finish() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStorage()) reload() else askStorage()
    }

    // ---------------------------------------------------------------- Laden

    private fun reload() {
        loadJob?.cancel()
        val old = library
        view.theme = Theme(File(cfg.themesDir, cfg.theme))
        view.statusText = if (cfg.isNetwork) "Verbinde mit ${cfg.smbHost} …" else "Lade Spiele …"
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { old?.close() } catch (_: Exception) {}
                try { cfg.ensureFolders() } catch (_: Exception) {}
                val lib = Library(this@MainActivity, cfg)
                try {
                    Result.success(lib to lib.loadSystems())
                } catch (e: Exception) {
                    Result.failure<Pair<Library, List<SystemEntry>>>(e)
                }
            }
            result.onSuccess { (lib, systems) ->
                library = lib
                view.library = lib
                view.setSystems(systems, cfg.lastSystem)
                view.statusText = ""
                view.requestFocus()
            }.onFailure { e ->
                view.statusText = ""
                library = null
                view.setSystems(emptyList(), null)
                AlertDialog.Builder(this@MainActivity, R.style.StationDialog)
                    .setTitle("Netzwerk-Fehler")
                    .setMessage("Die Freigabe konnte nicht gelesen werden:\n${e.message}\n\nIm Menü kannst du die Einstellungen prüfen oder auf \"Intern\" umstellen.")
                    .setPositiveButton("Menü") { _, _ -> onMenu() }
                    .setNegativeButton("Nochmal") { _, _ -> reload() }
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- Listener

    override fun onSystemChanged(entry: SystemEntry) {
        cfg.lastSystem = entry.system.id
    }

    override fun onMenu() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onExitRequest() {
        finishAndRemoveTask()
    }

    override fun onLaunch(entry: SystemEntry, game: Game) {
        val lib = library ?: return
        if (launching) return
        launching = true
        val chosen = cfg.coreFor(entry.system.id)
        val core = if (chosen.isNotEmpty() && Systems.coreFile(this, chosen).exists()) chosen
        else Systems.availableCore(this, entry.system)
        if (core == null) {
            launching = false
            view.statusText = "Kein Emulator für ${entry.system.name} in dieser APK"
            view.postDelayed({ view.statusText = "" }, 2500)
            return
        }
        view.statusText = if (lib.source.isNetwork) "Lade ${game.name} …" else "Starte ${game.name} …"
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    Result.success(lib.prepare(entry.system, game) { name, done, total ->
                        val txt = if (total > 0) "Lade ${game.name} … ${(done * 100 / total)} %"
                        else "Lade ${game.name} … ${done / (1024 * 1024)} MB"
                        runOnUiThread { view.statusText = txt }
                    })
                } catch (e: Exception) { Result.failure<File>(e) }
            }
            view.statusText = ""
            file.onSuccess { f ->
                val i = Intent(this@MainActivity, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_CORE, core)
                    .putExtra(GameActivity.EXTRA_ROM, f.absolutePath)
                    .putExtra(GameActivity.EXTRA_SYSTEM, entry.system.id)
                    .putExtra(GameActivity.EXTRA_NAME, game.name)
                    .putExtra(GameActivity.EXTRA_SAVE_NAME, game.fileName.substringBeforeLast('.'))
                startActivity(i)
            }.onFailure { e ->
                launching = false
                AlertDialog.Builder(this@MainActivity, R.style.StationDialog)
                    .setTitle("Spiel konnte nicht geladen werden")
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- Tasten

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val confirm = if (cfg.swapAB) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        val cancel = if (cfg.swapAB) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { view.moveHorizontal(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { view.moveHorizontal(1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { view.moveVertical(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { view.moveVertical(1); return true }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> { view.jumpLetter(-1); return true }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> { view.jumpLetter(1); return true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { view.moveVertical(-10); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { view.moveVertical(10); return true }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { onMenu(); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { view.confirm(); return true }
            confirm -> { view.confirm(); return true }
            cancel, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (!view.back()) askExit()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.back()) askExit()
    }

    private fun askExit() {
        AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle("DeLorean Station beenden?")
            .setPositiveButton("Beenden") { _, _ -> onExitRequest() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        stopRepeat()
        pad.releaseAll()
        stickDir = 0
    }

    override fun onDestroy() {
        try { (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this) } catch (_: Exception) {}
        stopRepeat()
        super.onDestroy()
        val lib = library
        Thread { try { lib?.close() } catch (_: Exception) {} }.start()
    }
}
