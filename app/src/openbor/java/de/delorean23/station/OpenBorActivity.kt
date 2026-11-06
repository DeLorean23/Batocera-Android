package de.delorean23.station

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * Startet ein OpenBOR-Spiel (.pak) direkt mit der eingebauten OpenBOR-Engine.
 * Extra "pak" = voller Pfad zur .pak-Datei. Läuft in eigenem Prozess (:openbor).
 */
class OpenBorActivity : SDLActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun getLibraries(): Array<String> = arrayOf("SDL2", "openbor")

    override fun getMainSharedObject(): String {
        val name = "libopenbor.so"
        val extracted = File(applicationInfo.nativeLibraryDir, name)
        return if (extracted.exists()) extracted.absolutePath else name
    }

    /** OpenBOR startet ein Pak direkt, wenn es als einziges Argument übergeben wird. */
    override fun getArguments(): Array<String> {
        val pak = intent?.getStringExtra(EXTRA_PAK) ?: return arrayOf()
        return arrayOf(pak)
    }

    // Android 14+: Empfänger müssen exportiert/nicht exportiert angegeben werden
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
        return if (Build.VERSION.SDK_INT >= 34) super.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else super.registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        // eigener Prozess: komplett beenden, damit die Engine beim nächsten Start sauber lädt
        if (isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        const val EXTRA_PAK = "pak"

        /** Wird aus dem C-Code von OpenBOR aufgerufen (Vibration bei Touch) */
        @JvmStatic
        fun jni_vibrate() {
            try {
                val ctx = getContext() ?: return
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (!v.hasVibrator()) return
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, 120))
                else @Suppress("DEPRECATION") v.vibrate(40)
            } catch (_: Exception) {}
        }
    }
}
