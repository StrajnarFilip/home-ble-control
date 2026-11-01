package com.strajnar.garage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingTest {
    private val aes128 = "000102030405060708090a0b0c0d0e0f"

    @Test
    fun bareKeyHasNoAddress() {
        val p = Pairing.parse(aes128)
        assertEquals(16, p.key.size)
        assertEquals(0x0f.toByte(), p.key.last())
        assertNull(p.address)
        assertEquals("Garage", p.name)
    }

    @Test
    fun acceptsAllAesKeyLengths() {
        for (bytes in listOf(16, 24, 32)) {
            assertEquals(bytes, Pairing.parse("ab".repeat(bytes)).key.size)
        }
    }

    @Test
    fun ignoresSurroundingWhitespaceAndCase() {
        assertArrayEquals(Pairing.parse(aes128).key, Pairing.parse("  ${aes128.uppercase()}\n").key)
    }

    @Test
    fun rejectsHexOfTheWrongLength() {
        val e = assertThrows(PairingException::class.java) { Pairing.parse("abcd") }
        assertEquals(R.string.pairing_bad_key_length, e.reason)
    }

    @Test
    fun parsesTheQrCodeJson() {
        val p = Pairing.parse("""{"v":1,"key":"$aes128","addr":"B8:27:EB:00:11:22","name":"Shed"}""")
        assertArrayEquals(Pairing.parse(aes128).key, p.key)
        assertEquals("Shed", p.name)
    }

    @Test
    fun rejectsJsonWithABadVersionOrKey() {
        fun reason(code: String) = assertThrows(PairingException::class.java) { Pairing.parse(code) }.reason
        assertEquals(R.string.pairing_bad_version, reason("""{"v":2,"key":"$aes128"}"""))
        assertEquals(R.string.pairing_bad_key, reason("""{"v":1,"key":"not hex"}"""))
        assertEquals(R.string.pairing_bad_key_length, reason("""{"v":1,"key":"abcd"}"""))
    }

    @Test
    fun rejectsAnythingElse() {
        val e = assertThrows(PairingException::class.java) { Pairing.parse("https://example.com") }
        assertEquals(R.string.pairing_not_garage, e.reason)
    }

    @Test
    fun rejectsSignedHexPairs() {
        // Integer parsing would read these as numbers; a key must be plain hex.
        val e = assertThrows(PairingException::class.java) { Pairing.parse("+a".repeat(16)) }
        assertEquals(R.string.pairing_not_garage, e.reason)
    }
}
