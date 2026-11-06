package de.delorean23.station

import android.util.Xml
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Ein Spiel wie in der gamelist.xml von Batocera/EmulationStation. */
data class Game(
    /** Pfad relativ zum System-Ordner, ohne "./" (z. B. "Super Mario World.sfc") */
    val path: String,
    var name: String,
    var desc: String = "",
    var image: String = "",
    var thumbnail: String = "",
    var marquee: String = "",
    var video: String = "",
    var rating: Float = 0f,
    var releaseDate: String = "",
    var developer: String = "",
    var publisher: String = "",
    var genre: String = "",
    var players: String = "",
    var hidden: Boolean = false,
    var favorite: Boolean = false,
    var playcount: Int = 0,
    var lastplayed: String = "",
    var gametime: Long = 0,
    /** System-Ordner (für Sammlungen und Suche) */
    var system: String = "",
    /** Alle Felder als Rohdaten, um sie unverändert in die lokale gamelist.xml zu übernehmen */
    val raw: MutableMap<String, String> = linkedMapOf()
) {
    val fileName: String get() = path.substringAfterLast('/')
    val year: String get() = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else ""
    /** Bild für die Anzeige: image, sonst thumbnail */
    val cover: String get() = image.ifEmpty { thumbnail }
}

object GameList {

    /** Entfernt "./" am Anfang und vereinheitlicht Trenner. */
    fun clean(p: String): String {
        var s = p.trim().replace('\\', '/')
        while (s.startsWith("./")) s = s.substring(2)
        return s
    }

    fun parse(input: InputStream): List<Game> {
        val games = ArrayList<Game>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var current: MutableMap<String, String>? = null
        var depth = 0
        var tag: String? = null
        val text = StringBuilder()
        try {
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (parser.name == "game" && current == null) {
                            current = linkedMapOf()
                        } else if (current != null) {
                            tag = parser.name
                            text.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> if (tag != null) text.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "game" && current != null) {
                            toGame(current)?.let { games.add(it) }
                            current = null
                        } else if (current != null && tag != null && parser.name == tag) {
                            current[tag!!] = text.toString().trim()
                            tag = null
                        }
                        depth--
                    }
                }
                ev = parser.next()
            }
        } catch (e: Exception) {
            // Kaputte gamelist.xml: alles nehmen, was bis dahin gelesen wurde
        }
        return games
    }

    private fun toGame(m: Map<String, String>): Game? {
        val path = m["path"]?.let { clean(it) } ?: return null
        if (path.isEmpty()) return null
        return Game(
            path = path,
            name = m["name"].orEmpty().ifEmpty { path.substringAfterLast('/').substringBeforeLast('.') },
            desc = m["desc"].orEmpty(),
            image = clean(m["image"].orEmpty()),
            thumbnail = clean(m["thumbnail"].orEmpty()),
            marquee = clean(m["marquee"].orEmpty()),
            video = clean(m["video"].orEmpty()),
            rating = m["rating"]?.toFloatOrNull() ?: 0f,
            releaseDate = m["releasedate"].orEmpty(),
            developer = m["developer"].orEmpty(),
            publisher = m["publisher"].orEmpty(),
            genre = m["genre"].orEmpty(),
            players = m["players"].orEmpty(),
            hidden = m["hidden"] == "true",
            favorite = m["favorite"] == "true",
            playcount = m["playcount"]?.toIntOrNull() ?: 0,
            lastplayed = m["lastplayed"].orEmpty(),
            gametime = m["gametime"]?.toLongOrNull() ?: 0,
            raw = LinkedHashMap(m)
        )
    }

    /** Übernimmt alle Felder eines anderen Eintrags (lokale Daten über Netzwerk-Daten). */
    fun overlay(target: Game, local: Game): Game {
        val raw = LinkedHashMap(target.raw)
        raw.putAll(local.raw)
        return toGame(raw)!!.also { it.system = target.system }
    }

    /**
     * Ändert Felder eines Eintrags in der lokalen gamelist.xml (legt ihn bei Bedarf an).
     * edit bekommt die aktuellen Felder und ändert sie direkt; leere Werte werden entfernt.
     */
    @Synchronized
    fun updateEntry(file: File, path: String, defaultName: String, base: Map<String, String> = emptyMap(),
                    edit: (MutableMap<String, String>) -> Unit): Map<String, String> {
        val existing = if (file.isFile) try { file.inputStream().use { parse(it) } } catch (e: Exception) { emptyList() } else emptyList()
        val fields = LinkedHashMap<String, String>()
        fields.putAll(base)
        existing.firstOrNull { it.path == path }?.raw?.let { fields.putAll(it) }
        if (fields["name"].isNullOrEmpty()) fields["name"] = defaultName
        edit(fields)
        val g = toGame(LinkedHashMap(fields).also { it["path"] = path }) ?: return fields
        mergeInto(file, g)
        return fields
    }

    /** Zeitstempel im Format von EmulationStation (lastplayed) */
    fun now(): String = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US).format(java.util.Date())

    /**
     * Fügt ein Spiel in die lokale gamelist.xml ein (oder ersetzt den Eintrag mit
     * demselben Pfad). Andere Einträge bleiben unverändert.
     */
    @Synchronized
    fun mergeInto(file: File, game: Game) {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = if (file.exists() && file.length() > 0) {
            try { builder.parse(file) } catch (e: Exception) { null }
        } else null
        val document = doc ?: builder.newDocument().also { it.appendChild(it.createElement("gameList")) }
        val root = document.documentElement

        val nodes = root.getElementsByTagName("game")
        for (i in nodes.length - 1 downTo 0) {
            val el = nodes.item(i) as Element
            val p = el.getElementsByTagName("path").item(0)?.textContent ?: continue
            if (clean(p) == game.path) root.removeChild(el)
        }

        val el = document.createElement("game")
        val fields = LinkedHashMap(game.raw)
        fields["path"] = "./" + game.path
        if (!fields.containsKey("name")) fields["name"] = game.name
        for ((k, v) in fields) {
            if (v.isEmpty() || !k.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))) continue
            val child = document.createElement(k)
            child.textContent = v
            el.appendChild(child)
        }
        root.appendChild(el)

        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.INDENT, "yes")
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { t.transform(DOMSource(document), StreamResult(it)) }
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }
}
