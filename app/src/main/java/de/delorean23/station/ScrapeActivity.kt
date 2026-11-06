package de.delorean23.station

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Scraper-Lauf mit Fortschritt. Extras: "systems" (Komma-Liste, leer = alle), "system"+"path" = einzelnes Spiel */
class ScrapeActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private var scraper: Scraper? = null
    private var done = false
    private val lines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
        }
        root.addView(TextView(this).apply { text = "SCRAPER"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        status = TextView(this).apply { textSize = 18f; setTextColor(0xFFBD4747.toInt()); setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        root.addView(status)
        logView = TextView(this).apply { textSize = 14f; setTextColor(0xFFC0C0C0.toInt()) }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val stop = Button(this).apply {
            text = "Abbrechen / Schließen"
            setOnClickListener { scraper?.cancelled = true; finish() }
        }
        root.addView(stop)
        setContentView(root)
        stop.requestFocus()
        run()
    }

    private fun log(s: String) {
        runOnUiThread {
            lines.addLast(s); while (lines.size > 300) lines.removeFirst()
            logView.text = lines.joinToString("\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun run() {
        val cfg = Config(this)
        status.text = "Lade Spieleliste …"
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val lib = Library(this@ScrapeActivity, cfg)
                try {
                    val sc = Scraper(cfg, lib).also { scraper = it }
                    val one = intent.getStringExtra("path")
                    val wanted = intent.getStringExtra("systems").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
                    val systems = lib.loadSystems().filter { !it.collection }
                    val games = if (one != null) {
                        systems.firstOrNull { it.system.id == intent.getStringExtra("system") }?.games?.filter { it.path == one } ?: emptyList()
                    } else systems.filter { wanted.isEmpty() || it.system.id in wanted }.flatMap { s -> s.games.filter { sc.needsScrape(it) } }
                    var ok = 0; var fail = 0
                    games.forEachIndexed { i, g ->
                        if (sc.cancelled) return@withContext "Abgebrochen. $ok gefunden, $fail nicht gefunden."
                        runOnUiThread { status.text = "${i + 1} / ${games.size}: ${g.name} [${g.system}]" }
                        val r = sc.scrape(g) { log("   $it") }
                        if (r.ok) { ok++; log("✓ ${g.name}") } else { fail++; log("✗ ${g.name}: ${r.message}") }
                        if (r.message.contains("Tageslimit") || r.message.contains("Login falsch") || r.message.contains("Entwickler-Zugang"))
                            return@withContext "Gestoppt: ${r.message}"
                        if (cfg.scrapeSource != "libretro") Thread.sleep(1200)
                    }
                    if (games.isEmpty()) "Nichts zu scrapen." else "Fertig: $ok gefunden, $fail nicht gefunden."
                } catch (e: Exception) {
                    "Fehler: ${e.message}"
                } finally { lib.close() }
            }
            done = true
            status.text = summary
            log(summary)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
            scraper?.cancelled = true; finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        scraper?.cancelled = true
        super.onDestroy()
    }
}
