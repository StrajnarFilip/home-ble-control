package com.strajnar.garage

import android.annotation.SuppressLint
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** AES-CMAC (RFC 4493). Must match server/auth.go. */
class Cmac(key: ByteArray) {
    // ECB here is the raw AES block function that CMAC is built from, not a mode for encrypting data.
    @SuppressLint("GetInstance")
    private val aes: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private val k1: ByteArray
    private val k2: ByteArray

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 16, 24 or 32 bytes" }
        val l = aes.doFinal(ByteArray(16))
        k1 = double(l)
        k2 = double(k1)
    }

    fun mac(msg: ByteArray): ByteArray {
        var n = (msg.size + 15) / 16
        val complete = n > 0 && msg.size % 16 == 0
        if (n == 0) n = 1
        val last = ByteArray(16)
        val tailStart = (n - 1) * 16
        msg.copyInto(last, 0, tailStart, msg.size)
        val sub = if (complete) k1 else {
            last[msg.size - tailStart] = 0x80.toByte()
            k2
        }
        for (i in 0 until 16) last[i] = (last[i].toInt() xor sub[i].toInt()).toByte()

        var x = ByteArray(16)
        for (b in 0 until n - 1) {
            for (i in 0 until 16) x[i] = (x[i].toInt() xor msg[b * 16 + i].toInt()).toByte()
            x = aes.doFinal(x)
        }
        for (i in 0 until 16) x[i] = (x[i].toInt() xor last[i].toInt()).toByte()
        return aes.doFinal(x)
    }

    private fun double(input: ByteArray): ByteArray {
        val out = ByteArray(16)
        for (i in 0 until 15) {
            out[i] = ((input[i].toInt() shl 1) or ((input[i + 1].toInt() and 0xff) ushr 7)).toByte()
        }
        out[15] = (input[15].toInt() shl 1).toByte()
        if (input[0].toInt() and 0x80 != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
        return out
    }
}

/** Wire protocol shared with server/auth.go. */
object Protocol {
    const val CMD_PULSE_GARAGE: Byte = 0x01
    const val CMD_SET_RELAY: Byte = 0x02
    private val DOMAIN = "HBC1".toByteArray(Charsets.US_ASCII)

    /** Command write payload: cmd | arg | AES-CMAC(key, "HBC1" | challenge | cmd | arg). */
    fun command(key: ByteArray, challenge: ByteArray, cmd: Byte, arg: Byte): ByteArray {
        require(challenge.size == 16) { "challenge must be 16 bytes" }
        val tag = Cmac(key).mac(DOMAIN + challenge + byteArrayOf(cmd, arg))
        return byteArrayOf(cmd, arg) + tag
    }
}
