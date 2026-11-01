package com.strajnar.garage

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject

/** A pairing code that can't be used; [reason] is the message to show. */
class PairingException(@StringRes val reason: Int) : IllegalArgumentException()

/** What the server's QR code carries: {"v":1,"key":"<hex>","addr":"AA:BB:..","name":"Garage"}. */
class Pairing(val key: ByteArray, val address: String?, val name: String) {
    companion object {
        private const val PREFS = "pairing"

        /**
         * Accepts the QR code's JSON or just the hex key (e.g. pasted from key.txt). Without an
         * address, [GarageBle] finds the garage by scanning for its service.
         */
        fun parse(code: String): Pairing {
            hexToBytes(code)?.let { return Pairing(checkKeyLength(it), null, "Garage") }
            val o = try {
                JSONObject(code.trim())
            } catch (e: JSONException) {
                throw PairingException(R.string.pairing_not_garage)
            }
            if (o.optInt("v") != 1) throw PairingException(R.string.pairing_bad_version)
            val key = hexToBytes(o.optString("key")) ?: throw PairingException(R.string.pairing_bad_key)
            val addr = o.optString("addr").uppercase().takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
            return Pairing(checkKeyLength(key), addr, o.optString("name").ifBlank { "Garage" })
        }

        private fun checkKeyLength(key: ByteArray): ByteArray {
            if (key.size != 16 && key.size != 24 && key.size != 32) throw PairingException(R.string.pairing_bad_key_length)
            return key
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
            if (t.isEmpty()) return null
            return try {
                t.hexToByteArray()
            } catch (e: IllegalArgumentException) {
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
