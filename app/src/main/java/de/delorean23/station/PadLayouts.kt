package de.delorean23.station

import android.view.KeyEvent

/**
 * Touch-Layouts im Stil von Neon Racer. Positionen sind in Bildschirmhöhen (H)
 * angegeben: x vom linken bzw. rechten Rand, y vom oberen Rand, r = Radius.
 *
 * Jede Taste sendet einen RetroPad-Tastencode (KeyEvent.KEYCODE_BUTTON_* in
 * Libretro-Bedeutung: BUTTON_A = rechts, BUTTON_B = unten, BUTTON_X = oben,
 * BUTTON_Y = links) oder eine Sonderfunktion.
 */
object PadLayouts {

    const val COL_GREEN = 0xFF3CD070.toInt()
    const val COL_RED = 0xFFE04848.toInt()
    const val COL_BLUE = 0xFF4A8CF0.toInt()
    const val COL_YELLOW = 0xFFE8C840.toInt()
    const val COL_PINK = 0xFFE060B0.toInt()
    const val COL_GRAY = 0xFFC8C8C8.toInt()
    const val COL_PURPLE = 0xFF9C6AD8.toInt()
    const val COL_CYAN = 0xFF40C8E0.toInt()

    /** Sonder-Codes (keine RetroPad-Taste) */
    const val K_MENU = -1          // Spielmenü
    const val K_C_UP = -10         // N64 C-Tasten (rechter Analogstick)
    const val K_C_DOWN = -11
    const val K_C_LEFT = -12
    const val K_C_RIGHT = -13

    enum class Side { LEFT, RIGHT, CENTER }

    data class Btn(
        val id: String,
        val label: String,
        val color: Int,
        val key: Int,
        val side: Side,
        val x: Float, val y: Float, val r: Float
    )

    data class Stick(val left: Boolean, val side: Side, val x: Float, val y: Float, val r: Float)

    data class Dpad(val side: Side, val x: Float, val y: Float, val offset: Float, val r: Float)

    data class Layout(val buttons: List<Btn>, val dpad: Dpad?, val sticks: List<Stick>)

    // ---- Positionen aus Neon Racer (1280 x 576 Screenshot, Werte / 576)
    private val L = Side.LEFT
    private val R = Side.RIGHT

    private fun back(label: String = "BACK", key: Int = KeyEvent.KEYCODE_BUTTON_SELECT) =
        Btn("back", label, COL_GRAY, key, L, 0.22f, 0.07f, 0.045f)
    private fun start(label: String = "START", key: Int = KeyEvent.KEYCODE_BUTTON_START) =
        Btn("start", label, COL_GRAY, key, R, 0.134f, 0.07f, 0.045f)
    private fun lt(label: String = "LT", key: Int = KeyEvent.KEYCODE_BUTTON_L2) =
        Btn("lt", label, COL_GRAY, key, L, 0.22f, 0.208f, 0.07f)
    private fun lb(label: String = "LB", key: Int = KeyEvent.KEYCODE_BUTTON_L1) =
        Btn("lb", label, COL_GRAY, key, L, 0.22f, 0.396f, 0.058f)
    private fun rt(label: String = "RT", key: Int = KeyEvent.KEYCODE_BUTTON_R2) =
        Btn("rt", label, COL_GRAY, key, R, 0.134f, 0.208f, 0.07f)
    private fun rb(label: String = "RB", key: Int = KeyEvent.KEYCODE_BUTTON_R1) =
        Btn("rb", label, COL_GRAY, key, R, 0.13f, 0.396f, 0.058f)
    private fun l3(label: String = "L3") = Btn("l3", label, COL_PINK, KeyEvent.KEYCODE_BUTTON_THUMBL, L, 0.45f, 0.483f, 0.048f)
    private fun r3(label: String = "R3") = Btn("r3", label, COL_PINK, KeyEvent.KEYCODE_BUTTON_THUMBR, R, 0.113f, 0.56f, 0.045f)

    // Raute rechts: oben, links, rechts, unten
    private fun faceTop(label: String, color: Int, key: Int = KeyEvent.KEYCODE_BUTTON_X) = Btn("ft", label, color, key, R, 0.278f, 0.595f, 0.07f)
    private fun faceLeft(label: String, color: Int, key: Int = KeyEvent.KEYCODE_BUTTON_Y) = Btn("fl", label, color, key, R, 0.42f, 0.729f, 0.07f)
    private fun faceRight(label: String, color: Int, key: Int = KeyEvent.KEYCODE_BUTTON_A) = Btn("fr", label, color, key, R, 0.13f, 0.729f, 0.07f)
    private fun faceBottom(label: String, color: Int, key: Int = KeyEvent.KEYCODE_BUTTON_B) = Btn("fb", label, color, key, R, 0.278f, 0.863f, 0.07f)

    private val dpadNormal = Dpad(L, 0.333f, 0.764f, 0.135f, 0.08f)
    private val dpadSmall = Dpad(L, 0.42f, 0.40f, 0.08f, 0.045f)
    private val stickLeft = Stick(true, L, 0.333f, 0.764f, 0.19f)
    private val stickRightSmall = Stick(false, R, 0.12f, 0.56f, 0.065f)

    /** Xbox-Belegung (Standard für alle übrigen Systeme) */
    private fun xboxFace() = listOf(
        faceTop("Y", COL_YELLOW), faceLeft("X", COL_BLUE), faceRight("B", COL_RED), faceBottom("A", COL_GREEN)
    )

    private fun full(withSticks: Boolean = false): Layout = Layout(
        listOf(back(), start(), lt(), lb(), rt(), rb(), l3(), r3()) + xboxFace(),
        if (withSticks) dpadSmall else dpadNormal,
        if (withSticks) listOf(stickLeft, stickRightSmall) else emptyList()
    )

    /** Oberfläche: Steuerkreuz, A/B/X/Y, LB/RB, SELECT, START (Codes = Xbox-Positionen) */
    val frontend: Layout = Layout(
        listOf(
            back("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT),
            start("START", KeyEvent.KEYCODE_BUTTON_START),
            lb("LB", KeyEvent.KEYCODE_BUTTON_L1),
            rb("RB", KeyEvent.KEYCODE_BUTTON_R1),
            faceTop("Y", COL_YELLOW, KeyEvent.KEYCODE_BUTTON_Y),
            faceLeft("X", COL_BLUE, KeyEvent.KEYCODE_BUTTON_X),
            faceRight("B", COL_RED, KeyEvent.KEYCODE_BUTTON_B),
            faceBottom("A", COL_GREEN, KeyEvent.KEYCODE_BUTTON_A)
        ),
        dpadNormal, emptyList()
    )

    fun forSystem(system: String): Layout = when (system) {
        // ---- Nintendo
        "nes", "fds" -> Layout(listOf(back("SELECT"), start(),
            faceRight("A", COL_RED), faceBottom("B", COL_RED)), dpadNormal, emptyList())
        "gb", "gbc", "gb2players", "pokemini" -> Layout(listOf(back("SELECT"), start(),
            faceRight("A", COL_PURPLE), faceBottom("B", COL_PURPLE)), dpadNormal, emptyList())
        "gba" -> Layout(listOf(back("SELECT"), start(), lb("L"), rb("R"),
            faceRight("A", COL_PURPLE), faceBottom("B", COL_PURPLE)), dpadNormal, emptyList())
        "virtualboy" -> Layout(listOf(back("SELECT"), start(), lb("L"), rb("R"),
            faceRight("A", COL_RED), faceBottom("B", COL_RED)), dpadNormal, emptyList())
        "snes", "satellaview", "sufami" -> Layout(listOf(back("SELECT"), start(), lb("L"), rb("R"),
            faceTop("X", COL_BLUE), faceLeft("Y", COL_GREEN), faceRight("A", COL_RED), faceBottom("B", COL_YELLOW)),
            dpadNormal, emptyList())
        "nds", "3ds" -> Layout(listOf(back("SELECT"), start(), lb("L"), rb("R"),
            faceTop("X", COL_GRAY), faceLeft("Y", COL_GRAY), faceRight("A", COL_GRAY), faceBottom("B", COL_GRAY)) +
            (if (system == "3ds") listOf(lt("ZL"), rt("ZR")) else emptyList()),
            if (system == "3ds") dpadSmall else dpadNormal,
            if (system == "3ds") listOf(stickLeft) else emptyList())
        "gameandwatch" -> Layout(listOf(back("GAME A", KeyEvent.KEYCODE_BUTTON_L1), start("GAME B", KeyEvent.KEYCODE_BUTTON_R1),
            Btn("time", "TIME", COL_GRAY, KeyEvent.KEYCODE_BUTTON_SELECT, R, 0.13f, 0.396f, 0.058f),
            faceRight("A", COL_RED), faceBottom("B", COL_RED)), dpadNormal, emptyList())
        "n64" -> Layout(listOf(start(), lt("Z", KeyEvent.KEYCODE_BUTTON_L2), lb("L"), rb("R"),
            faceBottom("A", COL_BLUE, KeyEvent.KEYCODE_BUTTON_B),
            faceLeft("B", COL_GREEN, KeyEvent.KEYCODE_BUTTON_Y),
            // C-Tasten als kleine Raute (rechter Analogstick)
            Btn("cu", "▲", COL_YELLOW, K_C_UP, R, 0.21f, 0.27f, 0.045f),
            Btn("cl", "◀", COL_YELLOW, K_C_LEFT, R, 0.30f, 0.36f, 0.045f),
            Btn("cr", "▶", COL_YELLOW, K_C_RIGHT, R, 0.12f, 0.36f, 0.045f),
            Btn("cd", "▼", COL_YELLOW, K_C_DOWN, R, 0.21f, 0.45f, 0.045f)),
            dpadSmall, listOf(stickLeft))
        "gamecube" -> Layout(listOf(start(), lt("L"), rt("R"), rb("Z"),
            faceBottom("A", COL_GREEN), faceLeft("B", COL_RED), faceRight("X", COL_GRAY), faceTop("Y", COL_GRAY)),
            dpadSmall, listOf(stickLeft, stickRightSmall))
        "wii" -> full(withSticks = true)

        // ---- Sega
        "megadrive", "megacd", "sega32x" -> Layout(listOf(
            back("MODE"), start(),
            // obere Reihe X Y Z, untere Reihe A B C (wie der 6-Tasten-Controller)
            Btn("x", "X", COL_GRAY, KeyEvent.KEYCODE_BUTTON_L1, R, 0.40f, 0.60f, 0.062f),
            Btn("y", "Y", COL_GRAY, KeyEvent.KEYCODE_BUTTON_X, R, 0.26f, 0.56f, 0.062f),
            Btn("z", "Z", COL_GRAY, KeyEvent.KEYCODE_BUTTON_R1, R, 0.12f, 0.52f, 0.062f),
            Btn("a", "A", COL_GRAY, KeyEvent.KEYCODE_BUTTON_Y, R, 0.40f, 0.80f, 0.07f),
            Btn("b", "B", COL_GRAY, KeyEvent.KEYCODE_BUTTON_B, R, 0.26f, 0.76f, 0.07f),
            Btn("c", "C", COL_GRAY, KeyEvent.KEYCODE_BUTTON_A, R, 0.12f, 0.72f, 0.07f)),
            dpadNormal, emptyList())
        "saturn" -> Layout(listOf(
            start(), lt("L", KeyEvent.KEYCODE_BUTTON_L2), rt("R", KeyEvent.KEYCODE_BUTTON_R2),
            Btn("x", "X", COL_GRAY, KeyEvent.KEYCODE_BUTTON_L1, R, 0.40f, 0.60f, 0.062f),
            Btn("y", "Y", COL_GRAY, KeyEvent.KEYCODE_BUTTON_X, R, 0.26f, 0.56f, 0.062f),
            Btn("z", "Z", COL_GRAY, KeyEvent.KEYCODE_BUTTON_R1, R, 0.12f, 0.52f, 0.062f),
            Btn("a", "A", COL_RED, KeyEvent.KEYCODE_BUTTON_Y, R, 0.40f, 0.80f, 0.07f),
            Btn("b", "B", COL_YELLOW, KeyEvent.KEYCODE_BUTTON_B, R, 0.26f, 0.76f, 0.07f),
            Btn("c", "C", COL_GREEN, KeyEvent.KEYCODE_BUTTON_A, R, 0.12f, 0.72f, 0.07f)),
            dpadNormal, emptyList())
        "mastersystem", "sg1000" -> Layout(listOf(start("PAUSE"),
            faceBottom("1", COL_GRAY, KeyEvent.KEYCODE_BUTTON_B), faceRight("2", COL_GRAY, KeyEvent.KEYCODE_BUTTON_A)),
            dpadNormal, emptyList())
        "gamegear" -> Layout(listOf(start(),
            faceBottom("1", COL_GRAY, KeyEvent.KEYCODE_BUTTON_B), faceRight("2", COL_GRAY, KeyEvent.KEYCODE_BUTTON_A)),
            dpadNormal, emptyList())
        "dreamcast" -> Layout(listOf(start(), lt("L"), rt("R"),
            faceBottom("A", COL_RED), faceRight("B", COL_BLUE), faceLeft("X", COL_YELLOW), faceTop("Y", COL_GREEN)),
            dpadSmall, listOf(stickLeft))
        "naomi", "atomiswave" -> Layout(listOf(back("COIN"), start(), lb("5", KeyEvent.KEYCODE_BUTTON_L1), rb("6", KeyEvent.KEYCODE_BUTTON_R1),
            faceBottom("1", COL_GRAY), faceRight("2", COL_GRAY), faceLeft("3", COL_GRAY), faceTop("4", COL_GRAY)),
            dpadNormal, emptyList())

        // ---- Sony
        "psx" -> Layout(listOf(back("SELECT"), start(), lt("L2"), lb("L1"), rt("R2"), rb("R1"),
            faceTop("△", COL_GREEN), faceLeft("□", COL_PINK), faceRight("○", COL_RED), faceBottom("✕", COL_BLUE)),
            dpadNormal, emptyList())
        "psp" -> Layout(listOf(back("SELECT"), start(), lb("L"), rb("R"),
            faceTop("△", COL_GREEN), faceLeft("□", COL_PINK), faceRight("○", COL_RED), faceBottom("✕", COL_BLUE)),
            dpadSmall, listOf(stickLeft))

        // ---- NEC
        "pcengine", "pcenginecd", "supergrafx" -> Layout(listOf(back("SELECT"), start("RUN"),
            faceRight("I", COL_GRAY, KeyEvent.KEYCODE_BUTTON_A), faceBottom("II", COL_GRAY, KeyEvent.KEYCODE_BUTTON_B)),
            dpadNormal, emptyList())

        // ---- SNK / Arcade
        "neogeo", "neogeocd" -> Layout(listOf(back("COIN"), start(),
            faceBottom("A", COL_RED), faceRight("B", COL_YELLOW), faceLeft("C", COL_GREEN), faceTop("D", COL_BLUE)),
            dpadNormal, emptyList())
        "ngp", "ngpc" -> Layout(listOf(start("OPTION"),
            faceBottom("A", COL_GRAY, KeyEvent.KEYCODE_BUTTON_B), faceRight("B", COL_GRAY, KeyEvent.KEYCODE_BUTTON_A)),
            dpadNormal, emptyList())
        "mame", "fbneo" -> Layout(listOf(back("COIN"), start(), lb("5"), rb("6"),
            faceBottom("1", COL_GRAY), faceRight("2", COL_GRAY), faceLeft("3", COL_GRAY), faceTop("4", COL_GRAY)),
            dpadNormal, emptyList())

        // ---- Atari
        "atari2600" -> Layout(listOf(back("SELECT"), start("RESET"),
            faceBottom("FIRE", COL_RED)), dpadNormal, emptyList())
        "atari7800" -> Layout(listOf(back("SELECT"), start("PAUSE"),
            faceBottom("1", COL_GRAY), faceRight("2", COL_GRAY)), dpadNormal, emptyList())
        "lynx" -> Layout(listOf(start("PAUSE"), lb("OPT 1"), rb("OPT 2"),
            faceRight("A", COL_GRAY), faceBottom("B", COL_GRAY)), dpadNormal, emptyList())
        "wswan", "wswanc" -> Layout(listOf(start(),
            faceRight("A", COL_GRAY), faceBottom("B", COL_GRAY)), dpadNormal, emptyList())
        "colecovision", "intellivision", "odyssey2", "vectrex" -> Layout(listOf(back(), start(),
            faceTop("4", COL_GRAY), faceLeft("3", COL_GRAY), faceRight("2", COL_GRAY), faceBottom("1", COL_GRAY)),
            dpadNormal, emptyList())

        else -> full()
    }
}
