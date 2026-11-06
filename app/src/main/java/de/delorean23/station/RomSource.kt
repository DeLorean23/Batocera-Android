package de.delorean23.station

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Eintrag in einem System-Ordner (Datei oder Unterordner). */
data class RomEntry(val path: String, val isDir: Boolean, val size: Long)

/** Quelle der ROMs: interner Speicher oder SMB-Freigabe. Alle Methoden blockieren (IO-Thread). */
interface RomSource {
    val isNetwork: Boolean
    fun systemFolders(): Set<String>
    /** Dateien direkt im System-Ordner (ohne Unterordner images/videos/…) */
    fun list(system: String, sub: String = ""): List<RomEntry>
    fun exists(system: String, path: String): Boolean
    fun open(system: String, path: String): InputStream?
    fun size(system: String, path: String): Long
    fun close() {}
}

class LocalSource(private val roms: File) : RomSource {
    override val isNetwork = false

    override fun systemFolders(): Set<String> =
        roms.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet() ?: emptySet()

    override fun list(system: String, sub: String): List<RomEntry> {
        val dir = if (sub.isEmpty()) File(roms, system) else File(File(roms, system), sub)
        val prefix = if (sub.isEmpty()) "" else "$sub/"
        return dir.listFiles()?.map { RomEntry(prefix + it.name, it.isDirectory, it.length()) } ?: emptyList()
    }

    override fun exists(system: String, path: String) = File(File(roms, system), path).exists()

    override fun open(system: String, path: String): InputStream? {
        val f = File(File(roms, system), path)
        return if (f.isFile) FileInputStream(f) else null
    }

    override fun size(system: String, path: String) = File(File(roms, system), path).length()
}

class SmbSource(private val cfg: Config) : RomSource {
    override val isNetwork = true

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun disk(): DiskShare {
        share?.let { if (it.isConnected) return it }
        close()
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build()
        val c = SMBClient(config)
        val host = cfg.smbHost.removePrefix("smb://").removePrefix("\\\\").trimEnd('/')
        val conn = if (host.contains(':')) {
            c.connect(host.substringBefore(':'), host.substringAfter(':').toIntOrNull() ?: 445)
        } else c.connect(host)
        val auth = if (cfg.smbUser.isEmpty()) AuthenticationContext.guest()
        else AuthenticationContext(cfg.smbUser, cfg.smbPassword.toCharArray(), cfg.smbDomain.ifEmpty { null })
        val s = conn.authenticate(auth)
        val d = s.connectShare(cfg.smbShare) as DiskShare
        client = c; connection = conn; session = s; share = d
        return d
    }

    private fun remote(system: String, path: String = ""): String {
        val parts = listOf(cfg.smbPath, system, path).filter { it.isNotEmpty() }
        return parts.joinToString("\\").replace('/', '\\')
    }

    override fun systemFolders(): Set<String> {
        val d = disk()
        val base = cfg.smbPath.replace('/', '\\')
        return d.list(base).filter {
            val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            dir && it.fileName != "." && it.fileName != ".."
        }.map { it.fileName }.toSet()
    }

    override fun list(system: String, sub: String): List<RomEntry> {
        val d = disk()
        val dir = remote(system, sub)
        if (!d.folderExists(dir)) return emptyList()
        val prefix = if (sub.isEmpty()) "" else "$sub/"
        return d.list(dir).filter { it.fileName != "." && it.fileName != ".." }.map {
            val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            RomEntry(prefix + it.fileName, isDir, it.endOfFile)
        }
    }

    override fun exists(system: String, path: String): Boolean {
        val d = disk()
        val r = remote(system, path)
        return try { d.fileExists(r) || d.folderExists(r) } catch (e: Exception) { false }
    }

    override fun open(system: String, path: String): InputStream? {
        val d = disk()
        val r = remote(system, path)
        if (!d.fileExists(r)) return null
        val f = d.openFile(
            r, EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
        )
        val inner = f.inputStream
        return object : InputStream() {
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun close() {
                try { inner.close() } finally { f.close() }
            }
        }
    }

    override fun size(system: String, path: String): Long {
        val d = disk()
        return try { d.getFileInformation(remote(system, path)).standardInformation.endOfFile } catch (e: Exception) { -1 }
    }

    @Synchronized
    override fun close() {
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null; session = null; connection = null; client = null
    }

    /** Verbindung testen – gibt null bei Erfolg, sonst die Fehlermeldung. */
    fun test(): String? = try {
        disk()
        val n = systemFolders().size
        if (n == 0) "Verbunden, aber im Ordner \"${cfg.smbPath}\" wurden keine System-Ordner gefunden." else null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    } finally { close() }
}
