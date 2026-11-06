package de.delorean23.station

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView
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
    private lateinit var pad: TouchPadView
    private lateinit var video: VideoView
    private var library: Library? = null
    private var systems: List<SystemEntry> = emptyList()
    private var loadJob: Job? = null
    private var launching = false
    private var lastConfigStamp = ""
    private var lastLaunched: Game? = null

    private val handler = Handler(Looper.getMainLooper())
    private var repeatAction: (() -> Unit)? = null
    private var stickDir = 0
    private val lastHat = HashMap<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = FrontendView(this)
        view.listener = this
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        video = VideoView(this).apply { visibility = View.GONE }
        pad = TouchPadView(this).also {
            it.layout = PadLayouts.frontend
            it.passThrough = true
            it.listener = padListener
        }
        val root = FrameLayout(this)
        val full = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(view, full())
        root.addView(video, FrameLayout.LayoutParams(1, 1))
        root.addView(pad, full())
        setContentView(root)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        launching = false
        updatePad()
        view.swapAB = cfg.swapAB
        view.systemView = cfg.systemView
        view.gameView = cfg.gameView
        if (!hasStorage()) { askStorage(); return }
        val stamp = configStamp()
        if (stamp != lastConfigStamp || library == null) {
            lastConfigStamp = stamp
            reload()
        } else {
            // nach dem Spielen: Spielzeit/Zuletzt gespielt aktualisieren
            lastLaunched?.let { g ->
                val lib = library
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { lib?.refreshGame(g) }
                    refreshCollections(keep = true)
                }
            }
        }
        lastLaunched = null
        if (pendingJoin) { pendingJoin = false; joinNetplay() }
        autoUpdateCheck()
    }

    private fun configStamp() = listOf(cfg.baseDir, cfg.storage, cfg.smbHost, cfg.smbShare, cfg.smbPath,
        cfg.smbUser, cfg.smbPassword, cfg.theme, cfg.colorset, cfg.showHidden, scrapeStamp).joinToString("|")

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
        Ui.gamepad(AlertDialog.Builder(this, R.style.StationDialog)
            .setTitle("Speicherzugriff")
            .setMessage("Damit ROMs, BIOS, Spielstände und Themes im Ordner \"batocera\" gelesen und gespeichert werden können, braucht die App Zugriff auf alle Dateien.")
            .setCancelable(false)
            .setPositiveButton("Erlauben") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                } else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
            .setNegativeButton("Beenden") { _, _ -> finish() }.create(), cfg).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStorage()) reload() else askStorage()
    }

    // ---------------------------------------------------------------- Laden

    private fun reload() {
        loadJob?.cancel()
        stopVideo()
        val old = library
        view.theme = Theme(File(cfg.themesDir, cfg.theme), cfg.colorset)
        view.statusText = if (cfg.isNetwork) "Verbinde mit ${cfg.smbHost} …" else "Lade Spiele …"
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { old?.close() } catch (_: Exception) {}
                try { cfg.ensureFolders() } catch (_: Exception) {}
                val lib = Library(this@MainActivity, cfg)
                try { Result.success(lib to lib.loadSystems()) }
                catch (e: Exception) { Result.failure<Pair<Library, List<SystemEntry>>>(e) }
            }
            result.onSuccess { (lib, list) ->
                library = lib; systems = list
                view.library = lib
                view.setSystems(list, cfg.lastSystem)
                view.statusText = ""
                view.requestFocus()
            }.onFailure { e ->
                view.statusText = ""
                library = null; systems = emptyList()
                view.setSystems(emptyList(), null)
                Ui.gamepad(AlertDialog.Builder(this@MainActivity, R.style.StationDialog)
                    .setTitle("Netzwerk-Fehler")
                    .setMessage("Die Freigabe konnte nicht gelesen werden:\n${e.message}\n\nIm Menü kannst du die Einstellungen prüfen oder auf \"Intern\" umstellen.")
                    .setPositiveButton("Menü") { _, _ -> onMenu() }
                    .setNegativeButton("Nochmal") { _, _ -> reload() }.create(), cfg).show()
            }
        }
    }

    /** Sammlungen neu aufbauen (nach Favorit, Spielen, Verstecken) */
    private fun refreshCollections(keep: Boolean) {
        val lib = library ?: return
        val cur = view.current()?.system?.id
        val real = systems.filter { !it.collection }.map { e ->
            SystemEntry(e.system, e.games.filter { cfg.showHidden || !it.hidden })
        }.filter { it.games.isNotEmpty() }
        systems = lib.withCollections(real)
        val inSearch = cur == Library.SEARCH.id
        if (!inSearch) view.setSystems(systems, cur, keepGame = keep) else view.invalidate()
    }

    // ---------------------------------------------------------------- Listener

    override fun onSystemChanged(entry: SystemEntry) {
        if (!entry.collection && entry.system.id != Library.SEARCH.id) cfg.lastSystem = entry.system.id
    }

    override fun onMenu() {
        stopVideo()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onLaunch(entry: SystemEntry, game: Game) {
        launchGame(game, null)
    }

    private fun launchGame(game: Game, netplayMode: String?, host: Netplay.HostInfo? = null) {
        val lib = library ?: return
        val sys = Systems.byId(game.system) ?: return
        if (launching) return
        launching = true
        stopVideo()
        val chosen = cfg.effective(sys.id, game.path, "core")
        val core = if (chosen.isNotEmpty() && Systems.coreFile(this, chosen).exists()) chosen else Systems.availableCore(this, sys)
        if (core == null) {
            launching = false
            flash("Kein Emulator für ${sys.name} in dieser APK")
            return
        }
        view.statusText = if (lib.source.isNetwork) "Lade ${game.name} …" else "Starte ${game.name} …"
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val f = lib.prepare(sys, game) { _, done, total ->
                        val txt = if (total > 0) "Lade ${game.name} … ${(done * 100 / total)} %"
                        else "Lade ${game.name} … ${done / (1024 * 1024)} MB"
                        runOnUiThread { view.statusText = txt }
                    }
                    // Batocera: Anzahl Starts und zuletzt gespielt
                    try {
                        lib.updateGame(game) { m ->
                            m["playcount"] = ((m["playcount"]?.toIntOrNull() ?: 0) + 1).toString()
                            m["lastplayed"] = GameList.now()
                        }
                    } catch (_: Exception) {}
                    Result.success(f)
                } catch (e: Exception) { Result.failure<File>(e) }
            }
            view.statusText = ""
            file.onSuccess { f ->
                lastLaunched = game
                val i = Intent(this@MainActivity, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_CORE, core)
                    .putExtra(GameActivity.EXTRA_ROM, f.absolutePath)
                    .putExtra(GameActivity.EXTRA_SYSTEM, sys.id)
                    .putExtra(GameActivity.EXTRA_PATH, game.path)
                    .putExtra(GameActivity.EXTRA_NAME, game.name)
                    .putExtra(GameActivity.EXTRA_SAVE_NAME, game.fileName.substringBeforeLast('.'))
                if (netplayMode != null) i.putExtra(GameActivity.EXTRA_NETPLAY, netplayMode)
                if (host != null) i.putExtra(GameActivity.EXTRA_HOST, host.ip).putExtra(GameActivity.EXTRA_PORT, host.port)
                startActivity(i)
            }.onFailure { e ->
                launching = false
                Ui.message(this@MainActivity, cfg, "Spiel konnte nicht geladen werden", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun flash(s: String) {
        view.statusText = s
        view.postDelayed({ if (view.statusText == s) view.statusText = "" }, 2500)
    }

    // ---------------------------------------------------------------- Spieloptionen (SELECT / lange tippen)

    override fun onGameOptions(entry: SystemEntry, game: Game) {
        stopVideo()
        val lib = library ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (game.favorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") to { toggleFavorite(game) }
        items += "Spiel-Einstellungen" to { gameSettings(game) }
        items += "Spiel bearbeiten" to { editGame(game) }
        items += "Dieses Spiel scrapen" to {
            startActivity(Intent(this, ScrapeActivity::class.java).putExtra("system", game.system).putExtra("path", game.path))
            scrapeStamp = System.currentTimeMillis().toString()
        }
        items += "Netplay: als Host starten" to { launchGame(game, "host") }
        items += (if (game.hidden) "Spiel wieder anzeigen" else "Spiel verstecken") to {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { lib.updateGame(game) { m -> if (game.hidden) m.remove("hidden") else m["hidden"] = "true" } }
                refreshCollections(keep = true)
            }
        }
        items += "Suchen" to { search() }
        Ui.list(this, cfg, game.name, items.map { it.first }) { items[it].second() }
    }

    private fun toggleFavorite(game: Game) {
        val lib = library ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                lib.updateGame(game) { m -> if (game.favorite) m.remove("favorite") else m["favorite"] = "true" }
            }
            flash(if (game.favorite) "★ Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt")
            refreshCollections(keep = true)
        }
    }

    private fun gameSettings(game: Game) {
        val sys = Systems.byId(game.system) ?: return
        fun valueName(key: String, v: String): String = if (v.isEmpty()) "Standard" else when (key) {
            "aspect" -> mapOf("core" to "Original", "4:3" to "4:3", "16:9" to "16:9", "voll" to "Bild füllen")[v] ?: v
            "scale" -> if (v == "integer") "Ganzzahlig" else "Bildschirm füllen"
            "shader" -> mapOf("sharp" to "Scharf", "default" to "Weich", "crt" to "CRT", "lcd" to "LCD", "smooth" to "Hochskaliert")[v] ?: v
            else -> v
        }
        val keys = listOf("core", "aspect", "scale", "shader")
        val labels = listOf("Emulator", "Seitenverhältnis", "Skalierung", "Bildfilter")
        val items = keys.mapIndexed { i, k -> "${labels[i]}: ${valueName(k, cfg.gameSetting(sys.id, game.path, k))}" }
        Ui.list(this, cfg, "Einstellungen: ${game.name}", items) { i ->
            val key = keys[i]
            val opts: List<Pair<String, String>> = when (key) {
                "core" -> listOf("" to "Standard") + sys.cores.filter { Systems.coreFile(this, it).exists() }.map { it to it }
                "aspect" -> listOf("" to "Standard", "core" to "Original", "4:3" to "4:3", "16:9" to "16:9", "voll" to "Bild füllen")
                "scale" -> listOf("" to "Standard", "normal" to "Bildschirm füllen", "integer" to "Ganzzahlig (pixelgenau)")
                else -> listOf("" to "Standard", "sharp" to "Scharf (Pixel)", "default" to "Weich", "crt" to "Röhrenfernseher (CRT)", "lcd" to "LCD-Raster", "smooth" to "Hochskaliert")
            }
            val cur = cfg.gameSetting(sys.id, game.path, key)
            Ui.choice(this, cfg, labels[i], opts.map { it.second }, opts.indexOfFirst { it.first == cur }.coerceAtLeast(0)) { j ->
                cfg.setGameSetting(sys.id, game.path, key, opts[j].first)
                gameSettings(game)
            }
        }
    }

    private fun editGame(game: Game) {
        val lib = library ?: return
        Ui.list(this, cfg, "Bearbeiten: ${game.name}", listOf("Name", "Beschreibung", "Erscheinungsjahr", "Genre", "Spieler")) { i ->
            val field = listOf("name", "desc", "releasedate", "genre", "players")[i]
            val current = when (field) { "releasedate" -> game.year; else -> game.raw[field] ?: if (field == "name") game.name else "" }
            Ui.keyboard(this, cfg, listOf("Name", "Beschreibung", "Erscheinungsjahr", "Genre", "Spieler")[i], current) { value ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        lib.updateGame(game) { m ->
                            val v = if (field == "releasedate" && value.length == 4) value + "0101T000000" else value
                            if (v.isEmpty()) m.remove(field) else m[field] = v
                        }
                    }
                    view.invalidate()
                }
            }
        }
    }

    private fun search() {
        Ui.keyboard(this, cfg, "Suche", "") { q ->
            if (q.isEmpty()) return@keyboard
            val words = q.lowercase().split(' ').filter { it.isNotEmpty() }
            val hits = systems.filter { !it.collection && it.system.id != Library.SEARCH.id }.flatMap { it.games }
                .filter { g -> words.all { g.name.lowercase().contains(it) || g.fileName.lowercase().contains(it) } }
                .sortedBy { it.name.lowercase() }
            if (hits.isEmpty()) { flash("Nichts gefunden für \"$q\""); return@keyboard }
            view.showEntry(SystemEntry(Library.SEARCH.copy(name = "Suche: $q"), hits, collection = false))
        }
    }

    // ---------------------------------------------------------------- Video-Vorschau

    private val videoRunnable = Runnable { startVideo() }
    private var videoFor: Game? = null

    override fun onSelectionChanged(entry: SystemEntry?, game: Game?) {
        stopVideo()
        if (game == null || !cfg.videoPreview || game.video.isEmpty() || cfg.gameView != "detailed") return
        videoFor = game
        handler.postDelayed(videoRunnable, 1200)
    }

    private fun startVideo() {
        val g = videoFor ?: return
        val lib = library ?: return
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { try { lib.mediaFile(g.system, g.video) } catch (e: Exception) { null } }
            if (f == null || videoFor !== g || view.videoRect.isEmpty) return@launch
            val r = view.videoRect
            video.layoutParams = FrameLayout.LayoutParams(r.width().toInt(), r.height().toInt()).apply {
                leftMargin = r.left.toInt(); topMargin = r.top.toInt()
            }
            video.visibility = View.VISIBLE
            video.setOnPreparedListener { mp: MediaPlayer -> mp.isLooping = true; mp.setVolume(0f, 0f); view.videoActive = true; view.invalidate() }
            video.setOnErrorListener { _, _, _ -> stopVideo(); true }
            video.setVideoPath(f.absolutePath)
            video.start()
        }
    }

    private fun stopVideo() {
        handler.removeCallbacks(videoRunnable)
        videoFor = null
        if (video.visibility == View.VISIBLE) { try { video.stopPlayback() } catch (_: Exception) {}; video.visibility = View.GONE }
        if (view.videoActive) { view.videoActive = false; view.invalidate() }
    }

    // ---------------------------------------------------------------- Netplay beitreten

    private fun joinNetplay() {
        flash("Suche Netplay-Spiele im LAN …")
        lifecycleScope.launch {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val lock = try { wifi?.createMulticastLock("netplay")?.apply { acquire() } } catch (e: Exception) { null }
            val hosts = withContext(Dispatchers.IO) { try { Netplay.discover() } catch (e: Exception) { emptyList() } }
            try { lock?.release() } catch (_: Exception) {}
            view.statusText = ""
            val manual = "IP-Adresse eingeben …"
            val labels = hosts.map { "${it.game} [${it.system}] – ${it.host} (${it.ip})" } + manual
            Ui.list(this@MainActivity, cfg, if (hosts.isEmpty()) "Kein Host gefunden" else "Netplay beitreten", labels) { i ->
                if (i < hosts.size) joinHost(hosts[i])
                else Ui.keyboard(this@MainActivity, cfg, "IP-Adresse des Hosts", "192.168.") { ip ->
                    // Ohne Ankündigung: Spiel muss vorher gleich gewählt sein → aktuelles Spiel nehmen
                    val g = view.currentGame()
                    if (g == null) Ui.message(this@MainActivity, cfg, "Netplay", "Wähle zuerst dasselbe Spiel wie der Host in der Liste aus.")
                    else launchGame(g, "client", Netplay.HostInfo(g.system, g.path, g.name, "", ip.trim()))
                }
            }
        }
    }

    private fun joinHost(h: Netplay.HostInfo) {
        val game = systems.filter { !it.collection }.firstOrNull { it.system.id == h.system }?.games?.firstOrNull { it.path == h.path }
            ?: systems.flatMap { it.games }.firstOrNull { it.system == h.system && it.fileName == h.path.substringAfterLast('/') }
        if (game == null) {
            Ui.message(this, cfg, "Netplay", "Das Spiel \"${h.game}\" (${h.system}/${h.path}) wurde in deiner Spieleliste nicht gefunden.")
            return
        }
        launchGame(game, "client", h)
    }

    // ---------------------------------------------------------------- Update-Prüfung

    private fun autoUpdateCheck() {
        if (!cfg.autoUpdateCheck) return
        val now = System.currentTimeMillis()
        if (now - cfg.lastUpdateCheck < 12 * 3600 * 1000L) return
        cfg.lastUpdateCheck = now
        lifecycleScope.launch {
            val u = withContext(Dispatchers.IO) { try { Updater.check() } catch (e: Exception) { null } } ?: return@launch
            Ui.confirm(this@MainActivity, cfg, "Update verfügbar", "Build ${u.build} ist verfügbar (installiert: ${BuildConfig.BUILD_NUMBER}).\nJetzt herunterladen und installieren?", "Installieren") {
                SettingsActivity.downloadUpdate(this@MainActivity, cfg, u)
            }
        }
    }

    // ---------------------------------------------------------------- Touch-Controller (Oberfläche)

    private val repeatRunnable = object : Runnable {
        override fun run() { repeatAction?.let { it(); handler.postDelayed(this, 90) } }
    }

    private fun startRepeat(action: () -> Unit) {
        stopRepeat(); action(); repeatAction = action
        handler.postDelayed(repeatRunnable, 380)
    }

    private fun stopRepeat() { repeatAction = null; handler.removeCallbacks(repeatRunnable) }

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

    /** Gemeinsame Steuerung für Touch und Controller (Standard-Tasten = Xbox-Positionen) */
    private fun handleNav(code: Int, down: Boolean) {
        val confirm = Ui.confirmKey(cfg)
        val cancel = Ui.cancelKey(cfg)
        if (!down) {
            if (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)) stopRepeat()
            return
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> startRepeat { view.moveVertical(-1) }
            KeyEvent.KEYCODE_DPAD_DOWN -> startRepeat { view.moveVertical(1) }
            KeyEvent.KEYCODE_DPAD_LEFT -> startRepeat { view.moveHorizontal(-1) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> startRepeat { view.moveHorizontal(1) }
            KeyEvent.KEYCODE_BUTTON_L2 -> startRepeat { view.moveVertical(-10) }
            KeyEvent.KEYCODE_BUTTON_R2 -> startRepeat { view.moveVertical(10) }
            KeyEvent.KEYCODE_BUTTON_L1 -> view.jumpLetter(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> view.jumpLetter(1)
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE -> onMenu()
            KeyEvent.KEYCODE_BUTTON_Y -> view.currentGame()?.let { toggleFavorite(it) }
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (view.inGameList) view.options() else search()
            KeyEvent.KEYCODE_BUTTON_X -> search()
            confirm -> view.confirm()
            cancel -> if (!view.back()) askExit()
        }
    }

    private val padListener = object : TouchPadView.Listener {
        override fun onKey(key: Int, pressed: Boolean) {
            // Touch-Layout der Oberfläche liefert Xbox-Positionen (A unten, B rechts)
            handleNav(key, pressed)
        }

        override fun onStick(left: Boolean, x: Float, y: Float) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !Remap.isGamepadEvent(event)) {
            if (down) { if (!view.back()) askExit() }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (down) view.confirm(); return true }
            KeyEvent.KEYCODE_ESCAPE -> { if (down && !view.back()) askExit(); return true }
            KeyEvent.KEYCODE_MENU -> { if (down) onMenu(); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { if (down) view.jumpLetter(-1); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) view.jumpLetter(1); return true }
        }
        val code = Remap.translate(this, event).let { if (it == KeyEvent.KEYCODE_BACK) KeyEvent.KEYCODE_BUTTON_SELECT else it }
        if (event.repeatCount > 0) return true
        val handled = code in NAV_KEYS
        if (handled) handleNav(code, down)
        return handled || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        if ((src and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return super.dispatchGenericMotionEvent(event)
        val id = event.deviceId
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = event.getAxisValue(MotionEvent.AXIS_X); val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val dir = when {
            hy < -0.5f || y < -0.6f -> 1
            hy > 0.5f || y > 0.6f -> 2
            hx < -0.5f || x < -0.6f -> 3
            hx > 0.5f || x > 0.6f -> 4
            else -> 0
        }
        val prev = lastHat[id] ?: 0
        if (dir != prev) {
            lastHat[id] = dir
            val keys = intArrayOf(0, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            if (prev != 0) handleNav(keys[prev], false)
            if (dir != 0) handleNav(keys[dir], true)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!view.back()) askExit() }

    private fun askExit() {
        Ui.confirm(this, cfg, "DeLorean Station beenden?", "", "Beenden") { finishAndRemoveTask() }
    }

    override fun onPause() {
        super.onPause()
        stopRepeat(); pad.releaseAll(); stickDir = 0
        stopVideo()
    }

    override fun onDestroy() {
        try { (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this) } catch (_: Exception) {}
        stopRepeat()
        super.onDestroy()
        val lib = library
        Thread { try { lib?.close() } catch (_: Exception) {} }.start()
    }

    companion object {
        /** Wird vom Menü gesetzt: nach dem Schließen Netplay-Suche starten */
        @JvmStatic var pendingJoin = false
        /** Ändert sich nach einem Scraper-Lauf → Liste neu laden */
        @JvmStatic var scrapeStamp = ""

        private val NAV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE
        )
    }
}
