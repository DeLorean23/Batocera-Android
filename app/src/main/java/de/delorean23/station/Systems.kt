package de.delorean23.station

import android.content.Context
import java.io.File

/**
 * Ein System mit Batocera-Ordnernamen (roms/<id>), Anzeigenamen und den
 * Cores in Reihenfolge der Priorität. Der erste Core, der in der APK
 * vorhanden ist, wird genommen (Batocera-Standard zuerst).
 */
data class GameSystem(
    val id: String,
    val name: String,
    val manufacturer: String,
    val year: Int,
    val extensions: List<String>,
    val cores: List<String>,
    /** Core kann ZIP-Dateien selbst öffnen (Arcade usw.) */
    val zipNative: Boolean = false,
    val hardware: String = "console"
)

object Systems {

    private val cartExt = listOf("zip", "7z")

    val all: List<GameSystem> = listOf(
        GameSystem("nes", "Nintendo Entertainment System", "Nintendo", 1983,
            listOf("nes", "unif", "unf") + cartExt, listOf("fceumm", "nestopia"), zipNative = true),
        GameSystem("fds", "Family Computer Disk System", "Nintendo", 1986,
            listOf("fds") + cartExt, listOf("fceumm", "nestopia"), zipNative = true),
        GameSystem("snes", "Super Nintendo Entertainment System", "Nintendo", 1990,
            listOf("smc", "sfc", "swc", "fig", "bs", "st") + cartExt, listOf("snes9x"), zipNative = true),
        GameSystem("satellaview", "Satellaview", "Nintendo", 1995,
            listOf("bs", "smc", "sfc") + cartExt, listOf("snes9x"), zipNative = true),
        GameSystem("sufami", "SuFami Turbo", "Bandai", 1996,
            listOf("st", "smc", "sfc") + cartExt, listOf("snes9x"), zipNative = true),
        GameSystem("n64", "Nintendo 64", "Nintendo", 1996,
            listOf("z64", "n64", "v64") + cartExt, listOf("mupen64plus_next_gles3", "mupen64plus_next", "parallel_n64"), zipNative = true),
        GameSystem("gb", "Game Boy", "Nintendo", 1989,
            listOf("gb") + cartExt, listOf("gambatte", "mgba"), zipNative = true, hardware = "portable"),
        GameSystem("gb2players", "Game Boy (2 players)", "Nintendo", 1989,
            listOf("gb", "gbc") + cartExt, listOf("tgbdual"), zipNative = true, hardware = "portable"),
        GameSystem("gbc", "Game Boy Color", "Nintendo", 1998,
            listOf("gbc") + cartExt, listOf("gambatte", "mgba"), zipNative = true, hardware = "portable"),
        GameSystem("gba", "Game Boy Advance", "Nintendo", 2001,
            listOf("gba") + cartExt, listOf("mgba"), zipNative = true, hardware = "portable"),
        GameSystem("nds", "Nintendo DS", "Nintendo", 2004,
            listOf("nds") + cartExt, listOf("melonds", "melondsds", "desmume"), hardware = "portable"),
        GameSystem("3ds", "3DS", "Nintendo", 2011,
            listOf("3ds", "3dsx", "cci", "cxi", "app", "elf", "axf"), listOf("citra"), hardware = "portable"),
        GameSystem("virtualboy", "Virtual Boy", "Nintendo", 1995,
            listOf("vb") + cartExt, listOf("mednafen_vb"), zipNative = true, hardware = "portable"),
        GameSystem("pokemini", "Pokemon Mini", "Nintendo", 2001,
            listOf("min") + cartExt, listOf("pokemini"), zipNative = true, hardware = "portable"),
        GameSystem("gameandwatch", "Game and Watch", "Nintendo", 1980,
            listOf("mgw") + cartExt, listOf("gw"), zipNative = true, hardware = "portable"),
        GameSystem("gamecube", "GameCube", "Nintendo", 2001,
            listOf("gcm", "iso", "gcz", "ciso", "wbfs", "rvz", "dol", "elf"), listOf("dolphin")),
        GameSystem("wii", "Wii", "Nintendo", 2006,
            listOf("gcm", "iso", "gcz", "ciso", "wbfs", "wad", "rvz", "dol", "elf"), listOf("dolphin")),
        GameSystem("sg1000", "SG-1000", "Sega", 1983,
            listOf("sg") + cartExt, listOf("genesis_plus_gx"), zipNative = true),
        GameSystem("mastersystem", "Master System", "Sega", 1985,
            listOf("sms", "bin") + cartExt, listOf("genesis_plus_gx", "picodrive"), zipNative = true),
        GameSystem("megadrive", "Mega Drive", "Sega", 1988,
            listOf("md", "gen", "smd", "bin", "sg") + cartExt, listOf("genesis_plus_gx", "picodrive"), zipNative = true),
        GameSystem("gamegear", "Game Gear", "Sega", 1990,
            listOf("gg") + cartExt, listOf("genesis_plus_gx"), zipNative = true, hardware = "portable"),
        GameSystem("megacd", "Mega CD", "Sega", 1991,
            listOf("cue", "iso", "chd", "m3u"), listOf("genesis_plus_gx", "picodrive")),
        GameSystem("sega32x", "32x", "Sega", 1994,
            listOf("32x", "bin", "smd") + cartExt, listOf("picodrive"), zipNative = true),
        GameSystem("saturn", "Saturn", "Sega", 1994,
            listOf("cue", "iso", "ccd", "mds", "chd", "m3u"), listOf("yabasanshiro")),
        GameSystem("dreamcast", "Dreamcast", "Sega", 1998,
            listOf("cdi", "cue", "gdi", "chd", "m3u"), listOf("flycast")),
        GameSystem("naomi", "Naomi", "Sega", 1998,
            listOf("lst", "bin", "dat", "zip", "7z"), listOf("flycast"), zipNative = true, hardware = "arcade"),
        GameSystem("atomiswave", "Atomiswave", "Sammy", 2003,
            listOf("lst", "bin", "dat", "zip", "7z"), listOf("flycast"), zipNative = true, hardware = "arcade"),
        GameSystem("psx", "PlayStation", "Sony", 1994,
            listOf("cue", "img", "mdf", "pbp", "toc", "cbn", "m3u", "ccd", "chd", "iso", "exe"),
            listOf("swanstation", "pcsx_rearmed")),
        GameSystem("psp", "PlayStation Portable", "Sony", 2004,
            listOf("iso", "cso", "pbp", "chd"), listOf("ppsspp"), hardware = "portable"),
        GameSystem("pcengine", "PC Engine", "NEC", 1987,
            listOf("pce", "bin") + cartExt, listOf("mednafen_pce_fast", "mednafen_supergrafx"), zipNative = true),
        GameSystem("pcenginecd", "PC Engine CD", "NEC", 1988,
            listOf("pce", "cue", "ccd", "iso", "img", "chd", "m3u"), listOf("mednafen_pce_fast", "mednafen_supergrafx")),
        GameSystem("supergrafx", "Supergrafx", "NEC", 1989,
            listOf("pce", "sgx", "cue", "ccd", "chd") + cartExt, listOf("mednafen_supergrafx"), zipNative = true),
        GameSystem("pcfx", "PC-FX", "NEC", 1994,
            listOf("cue", "ccd", "toc", "chd"), listOf("mednafen_pcfx")),
        GameSystem("neogeo", "Neo-Geo", "SNK", 1990,
            listOf("zip", "7z"), listOf("fbneo"), zipNative = true, hardware = "arcade"),
        GameSystem("neogeocd", "Neo-Geo CD", "SNK", 1994,
            listOf("cue", "iso", "chd"), listOf("neocd")),
        GameSystem("ngp", "Neo-Geo Pocket", "SNK", 1998,
            listOf("ngp") + cartExt, listOf("mednafen_ngp"), zipNative = true, hardware = "portable"),
        GameSystem("ngpc", "Neo-Geo Pocket Color", "SNK", 1999,
            listOf("ngc") + cartExt, listOf("mednafen_ngp"), zipNative = true, hardware = "portable"),
        GameSystem("fbneo", "Final Burn Neo", "Arcade", 2019,
            listOf("zip", "7z"), listOf("fbneo"), zipNative = true, hardware = "arcade"),
        GameSystem("mame", "Mame", "Arcade", 1997,
            listOf("zip", "7z"), listOf("mame2003_plus", "fbneo"), zipNative = true, hardware = "arcade"),
        GameSystem("atari2600", "Atari 2600", "Atari", 1977,
            listOf("a26", "bin") + cartExt, listOf("stella"), zipNative = true),
        GameSystem("atari5200", "Atari 5200", "Atari", 1982,
            listOf("rom", "xfd", "atr", "atx", "cdm", "cas", "car", "bin", "a52", "xex") + cartExt,
            listOf("a5200", "atari800"), zipNative = true),
        GameSystem("atari800", "Atari 800", "Atari", 1979,
            listOf("rom", "xfd", "atr", "atx", "cdm", "cas", "car", "bin", "xex") + cartExt, listOf("atari800"), zipNative = true),
        GameSystem("atari7800", "Atari 7800", "Atari", 1986,
            listOf("a78", "bin") + cartExt, listOf("prosystem"), zipNative = true),
        GameSystem("lynx", "Atari Lynx", "Atari", 1989,
            listOf("lnx") + cartExt, listOf("handy"), zipNative = true, hardware = "portable"),
        GameSystem("jaguar", "Jaguar", "Atari", 1993,
            listOf("cue", "j64", "jag", "cof", "abs", "cdi", "rom") + cartExt, listOf("virtualjaguar"), zipNative = true),
        GameSystem("wswan", "WonderSwan", "Bandai", 1999,
            listOf("ws") + cartExt, listOf("mednafen_wswan"), zipNative = true, hardware = "portable"),
        GameSystem("wswanc", "WonderSwan Color", "Bandai", 2000,
            listOf("wsc") + cartExt, listOf("mednafen_wswan"), zipNative = true, hardware = "portable"),
        GameSystem("colecovision", "ColecoVision", "Coleco", 1982,
            listOf("bin", "col", "rom") + cartExt, listOf("gearcoleco", "bluemsx"), zipNative = true),
        GameSystem("intellivision", "Mattel Intellivision", "Mattel", 1979,
            listOf("int", "bin", "rom") + cartExt, listOf("freeintv"), zipNative = true),
        GameSystem("odyssey2", "Odyssey2", "Magnavox", 1978,
            listOf("bin") + cartExt, listOf("o2em"), zipNative = true),
        GameSystem("vectrex", "Vectrex", "MB", 1982,
            listOf("bin", "gam", "vec") + cartExt, listOf("vecx"), zipNative = true),
        GameSystem("3do", "3DO Interactive Multiplayer", "Panasonic", 1993,
            listOf("iso", "chd", "cue"), listOf("opera")),
        GameSystem("msx1", "MSX1", "Microsoft", 1983,
            listOf("dsk", "mx1", "rom", "cas", "m3u") + cartExt, listOf("bluemsx"), zipNative = true, hardware = "computer"),
        GameSystem("msx2", "MSX2", "Microsoft", 1985,
            listOf("dsk", "mx2", "rom", "cas", "m3u") + cartExt, listOf("bluemsx"), zipNative = true, hardware = "computer"),
        GameSystem("amstradcpc", "Amstrad CPC", "Amstrad", 1984,
            listOf("dsk", "sna", "tap", "cdt", "voc", "m3u", "cpr") + cartExt, listOf("cap32"), zipNative = true, hardware = "computer"),
        GameSystem("c64", "Commodore 64", "Commodore", 1982,
            listOf("d64", "d81", "crt", "prg", "tap", "t64", "m3u") + cartExt, listOf("vice_x64"), zipNative = true, hardware = "computer"),
        GameSystem("amiga500", "Amiga OCS/ECS", "Commodore", 1987,
            listOf("adf", "uae", "ipf", "dms", "adz", "lha", "hdf", "m3u") + cartExt, listOf("puae"), zipNative = true, hardware = "computer"),
        GameSystem("x68000", "Sharp X68000", "Sharp", 1987,
            listOf("dim", "img", "d88", "88d", "hdm", "dup", "2hd", "xdf", "hdf", "cmd", "m3u") + cartExt, listOf("px68k"), zipNative = true, hardware = "computer"),
        GameSystem("zxspectrum", "ZX Spectrum", "Sinclair", 1982,
            listOf("tzx", "tap", "z80", "rzx", "scl", "trd", "dsk") + cartExt, listOf("fuse"), zipNative = true, hardware = "computer"),
        GameSystem("dos", "Dos (x86)", "Microsoft", 1981,
            listOf("pc", "dos", "zip", "dosz", "exe", "com", "bat", "iso", "cue", "conf", "m3u"), listOf("dosbox_pure"), zipNative = true, hardware = "computer"),
        GameSystem("openbor", "OpenBOR", "OpenBOR Team", 2004,
            listOf("pak"), listOf("openbor"), hardware = "engine"),
        GameSystem("scummvm", "ScummVM", "LucasArts", 1987,
            listOf("scummvm"), listOf("scummvm"), hardware = "engine")
    )

    fun byId(id: String): GameSystem? = all.firstOrNull { it.id == id }

    /** Archive (zip/7z) direkt an den Emulator geben (Arcade, DOS) statt zu entpacken */
    fun keepsArchive(system: GameSystem): Boolean = system.hardware == "arcade" || system.id == "dos"

    fun coreFile(context: Context, core: String): File =
        if (core == "openbor") File(context.applicationInfo.nativeLibraryDir, "libopenbor.so")
        else File(context.applicationInfo.nativeLibraryDir, "lib${core}_libretro_android.so")

    /** Erster eingebauter Core des Systems, oder null wenn keiner in dieser APK steckt. */
    fun availableCore(context: Context, system: GameSystem): String? =
        system.cores.firstOrNull { coreFile(context, it).exists() }

    fun installedSystems(context: Context): List<GameSystem> =
        all.filter { availableCore(context, it) != null }
}
