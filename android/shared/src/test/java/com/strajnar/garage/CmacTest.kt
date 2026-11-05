package com.strajnar.garage

import org.junit.Assert.assertEquals
import org.junit.Test

class CmacTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private val rfcMsg = hex(
        "6bc1bee22e409f96e93d7e117393172a" + "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" + "f69f2445df4f9b17ad2b417be66c3710",
    )

    @Test
    fun rfc4493Vectors() {
        val c = Cmac(hex("2b7e151628aed2a6abf7158809cf4f3c"))
        assertEquals("bb1d6929e95937287fa37d129b756746", c.mac(rfcMsg.copyOf(0)).toHex())
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c", c.mac(rfcMsg.copyOf(16)).toHex())
        assertEquals("dfa66747de9ae63030ca32611497c827", c.mac(rfcMsg.copyOf(40)).toHex())
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe", c.mac(rfcMsg).toHex())
    }

    @Test
    fun aes256Vector() {
        val c = Cmac(hex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"))
        assertEquals("e1992190549f6ed5696a2c056c315410", c.mac(rfcMsg).toHex())
    }

    /** Same vector as TestCommandTagVector in server/auth_test.go (computed with openssl). */
    @Test
    fun commandMatchesServer() {
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val payload = Protocol.command(key, hex("000102030405060708090a0b0c0d0e0f"), Protocol.CMD_SET_RELAY, 1)
        assertEquals("0201" + "5b26e246a530b9e275b3333d48291397", payload.toHex())
    }
}
