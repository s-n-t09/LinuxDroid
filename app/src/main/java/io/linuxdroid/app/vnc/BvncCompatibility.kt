package io.linuxdroid.app.vnc

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * bVNC-compatible classic VNC authentication helper.
 *
 * The key-bit ordering follows the VNC DES behavior used by bVNC's
 * `com.iiordanov.bVNC.DesCipher`. The upstream bVNC project is GPL-3.0 and its
 * copyright notices are preserved in THIRD_PARTY_NOTICES.md.
 */
internal object BvncCompatibility {
    fun encryptClassicVncChallenge(challenge: ByteArray, password: String): ByteArray {
        require(challenge.size == 16) { "A VNC challenge must contain 16 bytes." }
        val reversedKey = ByteArray(8)
        password.take(8).toByteArray(StandardCharsets.ISO_8859_1).forEachIndexed { index, value ->
            reversedKey[index] = reverseBits(value)
        }
        return Cipher.getInstance("DES/ECB/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(reversedKey, "DES")) }
            .doFinal(challenge)
    }

    private fun reverseBits(value: Byte): Byte {
        var source = value.toInt() and 0xFF
        var result = 0
        repeat(8) {
            result = (result shl 1) or (source and 1)
            source = source ushr 1
        }
        return result.toByte()
    }
}
