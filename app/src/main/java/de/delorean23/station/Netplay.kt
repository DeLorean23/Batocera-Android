package de.delorean23.station

import android.view.KeyEvent
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Netplay im LAN zwischen zwei DeLorean-Station-Geräten (Lockstep):
 * Der Host schickt beim Start seinen Spielstand, danach tauschen beide nur
 * noch Eingaben aus. Host = Spieler 1 (Port 0), Gast = Spieler 2 (Port 1).
 */
class Netplay private constructor(val isHost: Boolean, private val socket: Socket) {

    interface Listener {
        fun onWaiting(waiting: Boolean)
        fun onDisconnected(reason: String)
    }

    var listener: Listener? = null

    private val out = DataOutputStream(socket.getOutputStream().buffered())
    private val inp = DataInputStream(socket.getInputStream().buffered())
    private val remote = ConcurrentHashMap<Int, LongArray>()
    private val lock = Object()
    @Volatile private var remoteState: ByteArray? = null
    @Volatile var active = true
        private set
    @Volatile var paused = false

    // Lokale Eingabe (vom UI-Thread geschrieben, im GL-Thread gelesen)
    @Volatile private var localMask = 0
    @Volatile private var lx = 0f; @Volatile private var ly = 0f
    @Volatile private var rx = 0f; @Volatile private var ry = 0f

    private var frame = 0
    private val applied = arrayOf(IntArray(1), IntArray(1))
    private val appliedAxes = arrayOf(FloatArray(4), FloatArray(4))

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = 0
        thread(name = "netplay-read", isDaemon = true) { readLoop() }
    }

    fun setKey(retroKey: Int, down: Boolean) {
        val bit = KEYS.indexOf(retroKey)
        if (bit < 0) return
        localMask = if (down) localMask or (1 shl bit) else localMask and (1 shl bit).inv()
    }

    fun setStick(left: Boolean, x: Float, y: Float) {
        if (left) { lx = x; ly = y } else { rx = x; ry = y }
    }

    private fun readLoop() {
        try {
            while (active) {
                when (inp.readByte().toInt()) {
                    T_INPUT -> {
                        val f = inp.readInt(); val m = inp.readInt()
                        val a = LongArray(5)
                        a[0] = m.toLong()
                        for (i in 1..4) a[i] = inp.readByte().toLong()
                        remote[f] = a
                    }
                    T_STATE -> {
                        val len = inp.readInt()
                        val b = ByteArray(len); inp.readFully(b)
                        remoteState = b
                    }
                    T_BYE -> { stop("Der Mitspieler hat das Spiel beendet."); return }
                }
                synchronized(lock) { lock.notifyAll() }
            }
        } catch (e: Exception) {
            if (active) stop("Verbindung zum Mitspieler verloren.")
        }
    }

    private fun send(block: DataOutputStream.() -> Unit) {
        try { synchronized(out) { out.block(); out.flush() } } catch (e: Exception) {
            if (active) stop("Verbindung zum Mitspieler verloren.")
        }
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        var notified = false
        while (active && !cond()) {
            val waited = System.currentTimeMillis() - start
            if (waited > 150 && !notified) { notified = true; listener?.onWaiting(true) }
            if (waited > timeoutMs && !paused) { stop("Mitspieler antwortet nicht mehr."); return false }
            synchronized(lock) { lock.wait(16) }
        }
        if (notified) listener?.onWaiting(false)
        return active
    }

    /** Wird im GL-Thread vor jedem Frame aufgerufen. */
    fun onFrame() {
        if (!active) return
        if (frame == 0) {
            if (isHost) {
                val st = LibretroDroid.serializeState()
                send { writeByte(T_STATE); writeInt(st.size); write(st) }
            } else {
                if (!waitFor(30_000) { remoteState != null }) return
                LibretroDroid.unserializeState(remoteState)
                remoteState = null
            }
        }
        // eigene Eingabe für Frame + DELAY senden
        val target = frame + DELAY
        val m = localMask
        val axes = floatArrayOf(lx, ly, rx, ry)
        send {
            writeByte(T_INPUT); writeInt(target); writeInt(m)
            axes.forEach { writeByte((it * 127f).roundToInt().coerceIn(-127, 127)) }
        }
        localHistory[target] = longArrayOf(m.toLong()) + axes.map { (it * 127f).roundToInt().toLong() }

        // Eingaben beider Spieler für diesen Frame
        val mine: LongArray = localHistory.remove(frame) ?: LongArray(5)
        val theirs: LongArray = if (frame < DELAY) LongArray(5) else {
            if (!waitFor(20_000) { remote.containsKey(frame) }) return
            remote.remove(frame) ?: LongArray(5)
        }
        val hostIn = if (isHost) mine else theirs
        val guestIn = if (isHost) theirs else mine
        apply(0, hostIn); apply(1, guestIn)
        frame++
    }

    private val localHistory = HashMap<Int, LongArray>()

    private fun apply(port: Int, input: LongArray) {
        val mask = input[0].toInt()
        val prev = applied[port][0]
        if (mask != prev) {
            for (bit in KEYS.indices) {
                val now = (mask shr bit) and 1; val before = (prev shr bit) and 1
                if (now != before) LibretroDroid.onKeyEvent(port, if (now == 1) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KEYS[bit])
            }
            applied[port][0] = mask
        }
        val ax = FloatArray(4) { input[it + 1] / 127f }
        val old = appliedAxes[port]
        if (ax[0] != old[0] || ax[1] != old[1]) LibretroDroid.onMotionEvent(port, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, ax[0], ax[1])
        if (ax[2] != old[2] || ax[3] != old[3]) LibretroDroid.onMotionEvent(port, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, ax[2], ax[3])
        appliedAxes[port] = ax
    }

    fun stop(reason: String? = null) {
        if (!active) return
        active = false
        synchronized(lock) { lock.notifyAll() }
        if (reason == null) try { synchronized(out) { out.writeByte(T_BYE); out.flush() } } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
        if (reason != null) listener?.onDisconnected(reason)
    }

    companion object {
        const val TCP_PORT = 55436
        const val UDP_PORT = 55435
        private const val DELAY = 3
        private const val T_INPUT = 1
        private const val T_STATE = 2
        private const val T_BYE = 3

        val KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START
        )

        /** Host: wartet auf einen Mitspieler und kündigt das Spiel im LAN an. */
        fun host(info: HostInfo, cancelled: () -> Boolean): Netplay? {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(TCP_PORT))
            server.soTimeout = 1000
            val udp = DatagramSocket().apply { broadcast = true }
            try {
                while (!cancelled()) {
                    try {
                        val msg = "DLST1|$TCP_PORT|${info.system}|${info.path}|${info.game}|${info.host}".toByteArray()
                        udp.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), UDP_PORT))
                    } catch (_: Exception) {}
                    val s = try { server.accept() } catch (e: java.net.SocketTimeoutException) { null } ?: continue
                    val o = DataOutputStream(s.getOutputStream())
                    o.writeUTF("DLST1|${info.system}|${info.path}"); o.flush()
                    return Netplay(true, s)
                }
                return null
            } finally {
                try { server.close() } catch (_: Exception) {}
                udp.close()
            }
        }

        /** Gast: verbindet sich mit dem Host. */
        fun join(ip: String, port: Int): Netplay {
            val s = Socket()
            s.connect(InetSocketAddress(ip, port), 8000)
            val hello = DataInputStream(s.getInputStream()).readUTF()
            if (!hello.startsWith("DLST1|")) { s.close(); throw IllegalStateException("Kein DeLorean-Station-Host") }
            return Netplay(false, s)
        }

        /** Sucht Hosts im LAN (ca. 3 Sekunden). */
        fun discover(timeoutMs: Long = 3000): List<HostInfo> {
            val found = LinkedHashMap<String, HostInfo>()
            DatagramSocket(null).use { udp ->
                udp.reuseAddress = true
                udp.bind(InetSocketAddress(UDP_PORT))
                udp.soTimeout = 500
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { udp.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }
                    val parts = String(p.data, 0, p.length).split('|')
                    if (parts.size >= 6 && parts[0] == "DLST1") {
                        val ip = p.address.hostAddress ?: continue
                        found[ip] = HostInfo(parts[2], parts[3], parts[4], parts[5], ip, parts[1].toIntOrNull() ?: TCP_PORT)
                    }
                }
            }
            return found.values.toList()
        }
    }

    data class HostInfo(val system: String, val path: String, val game: String, val host: String,
                        val ip: String = "", val port: Int = TCP_PORT)
}
