package com.strajnar.garage

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject

/** What the server's QR code carries: {"v":1,"key":"<hex>","addr":"AA:BB:..","name":"Garage"}. */
class Pairing(val key: ByteArray, val address: String?, val name: String) {
    companion object {
        private const val PREFS = "pairing"

        fun parse(qr: String): Pairing {
            val o = try {
                JSONObject(qr)
            } catch (e: JSONException) {
                throw IllegalArgumentException("Not a garage pairing code")
            }
            require(o.optInt("v") == 1) { "Unsupported pairing code version" }
            val key = hexToBytes(o.optString("key"))
                ?: throw IllegalArgumentException("Pairing code has an invalid key")
            require(key.size == 16 || key.size == 24 || key.size == 32) { "Pairing code has an invalid key length" }
            val addr = o.optString("addr").uppercase().takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
            return Pairing(key, addr, o.optString("name").ifBlank { "Garage" })
        }

        // App-private storage; backup and device transfer are disabled in the manifest.
        fun load(context: Context): Pairing? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = hexToBytes(p.getString("key", null) ?: return null) ?: return null
            return Pairing(key, p.getString("addr", null), p.getString("name", null) ?: "Garage")
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        }

        private fun hexToBytes(s: String): ByteArray? {
            val t = s.trim()
            if (t.isEmpty() || t.length % 2 != 0) return null
            return try {
                ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("key", key.joinToString("") { "%02x".format(it) })
            putString("addr", address)
            putString("name", name)
        }
    }
}
