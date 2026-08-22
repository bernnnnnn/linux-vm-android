package com.berns.linuxports.core

import android.content.Context
import com.berns.linuxports.model.Distro
import java.security.SecureRandom

/** Per-distro settings, plus the generated VNC password. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("berns", Context.MODE_PRIVATE)

    fun vncPassword(distro: Distro): String {
        val key = "vncpass_${distro.key}"
        sp.getString(key, null)?.let { return it }
        // TigerVNC truncates at 8 characters, so there is no point generating more.
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val generated = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        sp.edit().putString(key, generated).apply()
        return generated
    }

    fun geometry(distro: Distro): String = sp.getString("geometry_${distro.key}", "1280x720")!!

    fun setGeometry(distro: Distro, value: String) =
        sp.edit().putString("geometry_${distro.key}", value).apply()

    fun dpi(distro: Distro): Int = sp.getInt("dpi_${distro.key}", 120)

    fun setDpi(distro: Distro, value: Int) = sp.edit().putInt("dpi_${distro.key}", value).apply()

    /** How touches drive the remote pointer: "trackpad", "direct" or "joystick". */
    fun pointerMode(): String = sp.getString("pointer_mode", "trackpad")!!

    fun setPointerMode(value: String) = sp.edit().putString("pointer_mode", value).apply()

    fun pointerSpeed(): Float = sp.getFloat("pointer_speed", 1.4f)

    fun setPointerSpeed(value: Float) = sp.edit().putFloat("pointer_speed", value).apply()

    fun display(distro: Distro): Int = when (distro.key) {
        "mint" -> 1
        "ubuntu" -> 2
        else -> 3
    }

    companion object {
        val GEOMETRIES = listOf(
            "1024x600", "1280x720", "1366x768", "1600x900", "1920x1080"
        )

        val POINTER_SPEEDS = listOf(0.8f, 1.4f, 2.2f)
    }
}
