package com.kaze.encryption

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException :
    IOException("Wrong password, or the file is damaged.")

/**
 * Same format as the original Python tool:
 *   [salt 32][iv 16][AES-256-CBC + PKCS7 ciphertext]
 * Key + IV come from PBKDF2-HMAC-SHA256 (100,000 iterations, 48 bytes).
 * Files made by the desktop version can be opened here, and vice versa.
 */
object CryptoEngine {
    private const val SALT_SIZE = 32
    private const val IV_SIZE = 16
    private const val KEY_SIZE = 32
    private const val HEADER = SALT_SIZE + IV_SIZE
    private const val ITERATIONS = 100_000
    private const val BUFFER = 64 * 1024
    private const val TRANSFORM = "AES/CBC/PKCS5Padding"

    private fun derive(password: CharArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val spec = PBEKeySpec(password, salt, ITERATIONS, (KEY_SIZE + IV_SIZE) * 8)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            return bytes.copyOfRange(0, KEY_SIZE) to bytes.copyOfRange(KEY_SIZE, KEY_SIZE + IV_SIZE)
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(cr: ContentResolver, input: Uri, output: Uri, password: CharArray) {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val (key, iv) = derive(password, salt)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        val src = requireNotNull(cr.openInputStream(input)) { "Can't open the selected file." }
        val dst = requireNotNull(cr.openOutputStream(output, "wt")) { "Can't write the output file." }
        src.use { ins ->
            dst.use { outs ->
                outs.write(salt)
                outs.write(iv)
                CipherOutputStream(outs, cipher).use { co -> ins.copyTo(co, BUFFER) }
            }
        }
    }

    fun decrypt(cr: ContentResolver, input: Uri, output: Uri, password: CharArray) {
        val src = requireNotNull(cr.openInputStream(input)) { "Can't open the selected file." }
        src.use { ins ->
            val header = ByteArray(HEADER)
            var read = 0
            while (read < HEADER) {
                val n = ins.read(header, read, HEADER - read)
                if (n < 0) break
                read += n
            }
            if (read < HEADER) throw IOException("This isn't a Kaze encrypted file.")

            val salt = header.copyOfRange(0, SALT_SIZE)
            val iv = header.copyOfRange(SALT_SIZE, HEADER)
            val (key, _) = derive(password, salt)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

            val dst = requireNotNull(cr.openOutputStream(output, "wt")) { "Can't write the output file." }
            dst.use { outs ->
                try {
                    CipherInputStream(ins, cipher).use { ci -> ci.copyTo(outs, BUFFER) }
                } catch (e: IOException) {
                    val crypto = generateSequence<Throwable>(e) { it.cause }
                        .any { it is GeneralSecurityException }
                    throw if (crypto) WrongPasswordException() else e
                }
            }
        }
    }
}
