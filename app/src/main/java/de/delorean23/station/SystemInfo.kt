package de.delorean23.station

/**
 * Zusatzdaten je System: ScreenScraper-ID, Libretro-Thumbnail-Ordner und
 * typische Bildauflösung (für ganzzahlige Skalierung).
 */
object SystemInfo {

    val screenScraperId: Map<String, Int> = mapOf(
        "nes" to 3, "fds" to 106, "snes" to 4, "satellaview" to 107, "sufami" to 108, "n64" to 14,
        "gb" to 9, "gb2players" to 9, "gbc" to 10, "gba" to 12, "nds" to 15, "3ds" to 17,
        "virtualboy" to 11, "pokemini" to 211, "gameandwatch" to 52, "gamecube" to 13, "wii" to 16,
        "sg1000" to 109, "mastersystem" to 2, "megadrive" to 1, "gamegear" to 21, "megacd" to 20,
        "sega32x" to 19, "saturn" to 22, "dreamcast" to 23, "naomi" to 56, "atomiswave" to 53,
        "psx" to 57, "psp" to 61, "pcengine" to 31, "pcenginecd" to 114, "supergrafx" to 105,
        "pcfx" to 72, "neogeo" to 142, "neogeocd" to 70, "ngp" to 25, "ngpc" to 82, "fbneo" to 75,
        "mame" to 75, "atari2600" to 26, "atari5200" to 40, "atari800" to 43, "atari7800" to 41,
        "lynx" to 28, "jaguar" to 27, "wswan" to 45, "wswanc" to 46, "colecovision" to 48,
        "intellivision" to 115, "odyssey2" to 104, "vectrex" to 102, "3do" to 29, "msx1" to 113,
        "msx2" to 116, "amstradcpc" to 65, "c64" to 66, "amiga500" to 64, "x68000" to 79,
        "zxspectrum" to 76, "dos" to 135, "scummvm" to 123
    )

    val libretroName: Map<String, String> = mapOf(
        "nes" to "Nintendo - Nintendo Entertainment System",
        "fds" to "Nintendo - Family Computer Disk System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "satellaview" to "Nintendo - Satellaview",
        "sufami" to "Nintendo - Sufami Turbo",
        "n64" to "Nintendo - Nintendo 64",
        "gb" to "Nintendo - Game Boy", "gb2players" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "gba" to "Nintendo - Game Boy Advance",
        "nds" to "Nintendo - Nintendo DS",
        "3ds" to "Nintendo - Nintendo 3DS",
        "virtualboy" to "Nintendo - Virtual Boy",
        "pokemini" to "Nintendo - Pokemon Mini",
        "gameandwatch" to "Handheld Electronic Game",
        "gamecube" to "Nintendo - GameCube",
        "wii" to "Nintendo - Wii",
        "sg1000" to "Sega - SG-1000",
        "mastersystem" to "Sega - Master System - Mark III",
        "megadrive" to "Sega - Mega Drive - Genesis",
        "gamegear" to "Sega - Game Gear",
        "megacd" to "Sega - Mega-CD - Sega CD",
        "sega32x" to "Sega - 32X",
        "saturn" to "Sega - Saturn",
        "dreamcast" to "Sega - Dreamcast",
        "naomi" to "Sega - Naomi",
        "atomiswave" to "Atomiswave",
        "psx" to "Sony - PlayStation",
        "psp" to "Sony - PlayStation Portable",
        "pcengine" to "NEC - PC Engine - TurboGrafx 16",
        "pcenginecd" to "NEC - PC Engine CD - TurboGrafx-CD",
        "supergrafx" to "NEC - PC Engine SuperGrafx",
        "pcfx" to "NEC - PC-FX",
        "neogeo" to "SNK - Neo Geo",
        "neogeocd" to "SNK - Neo Geo CD",
        "ngp" to "SNK - Neo Geo Pocket",
        "ngpc" to "SNK - Neo Geo Pocket Color",
        "fbneo" to "FBNeo - Arcade Games",
        "mame" to "MAME",
        "atari2600" to "Atari - 2600",
        "atari5200" to "Atari - 5200",
        "atari800" to "Atari - 8-bit",
        "atari7800" to "Atari - 7800",
        "lynx" to "Atari - Lynx",
        "jaguar" to "Atari - Jaguar",
        "wswan" to "Bandai - WonderSwan",
        "wswanc" to "Bandai - WonderSwan Color",
        "colecovision" to "Coleco - ColecoVision",
        "intellivision" to "Mattel - Intellivision",
        "odyssey2" to "Magnavox - Odyssey2",
        "vectrex" to "GCE - Vectrex",
        "3do" to "The 3DO Company - 3DO",
        "msx1" to "Microsoft - MSX",
        "msx2" to "Microsoft - MSX2",
        "amstradcpc" to "Amstrad - CPC",
        "c64" to "Commodore - 64",
        "amiga500" to "Commodore - Amiga",
        "x68000" to "Sharp - X68000",
        "zxspectrum" to "Sinclair - ZX Spectrum",
        "dos" to "DOS",
        "scummvm" to "ScummVM"
    )

    /** Typische Auflösung (Breite x Höhe) für ganzzahlige Skalierung */
    fun resolution(system: String): Pair<Int, Int> = when (system) {
        "nes", "fds" -> 256 to 240
        "snes", "satellaview", "sufami" -> 256 to 224
        "gb", "gbc", "gamegear" -> 160 to 144
        "gb2players" -> 320 to 144
        "gba" -> 240 to 160
        "nds" -> 256 to 384
        "virtualboy" -> 384 to 224
        "pokemini" -> 96 to 64
        "megadrive", "megacd", "sega32x" -> 320 to 224
        "mastersystem", "sg1000", "colecovision" -> 256 to 192
        "pcengine", "pcenginecd", "supergrafx" -> 256 to 240
        "ngp", "ngpc" -> 160 to 152
        "wswan", "wswanc" -> 224 to 144
        "lynx" -> 160 to 102
        "atari2600" -> 160 to 210
        "psp" -> 480 to 272
        else -> 320 to 240
    }
}
