package de.delorean23.station

import android.Manifest
import android.content.Intent
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

class MainActivity : AppCompatActivity(), FrontendView.Listener {

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
        setContentView(view)
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        launching = false
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

    override fun onDestroy() {
        super.onDestroy()
        val lib = library
        Thread { try { lib?.close() } catch (_: Exception) {} }.start()
    }
}
