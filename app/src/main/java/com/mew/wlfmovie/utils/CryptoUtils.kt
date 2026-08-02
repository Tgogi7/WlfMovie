package com.mew.wlfmovie.utils

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * WLFMOVIE V4: Encriptación AES-256 para sync data.
 *
 * La clave se deriva del email del usuario + un salt fijo de la app.
 * Así cada usuario tiene su propia clave pero no necesitamos almacenarla.
 */
object CryptoUtils {

    private const val TAG = "WlfMovie-Crypto"
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val SALT = "WlfMovie2026SyncSalt"

    /**
     * Genera una clave AES de 256 bits a partir del email + salt.
     */
    private fun generateKey(email: String): SecretKey {
        val keyMaterial = (email + SALT).toByteArray()
        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(keyMaterial)
        // Usar los primeros 32 bytes (256 bits) del SHA-256
        return SecretKeySpec(sha256, 0, 32, ALGORITHM)
    }

    /**
     * Encripta un string con AES-256-CBC.
     * Returns: Base64 string del IV + datos encriptados.
     */
    fun encrypt(plainText: String, email: String): String {
        try {
            val key = generateKey(email)
            val cipher = Cipher.getInstance(TRANSFORMATION)

            // Generar IV aleatorio
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray())

            // Combinar IV + datos encriptados
            val combined = iv + encrypted
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting: ${e.message}")
            return ""
        }
    }

    /**
     * Desencripta un string Base64 (IV + datos) con AES-256-CBC.
     */
    fun decrypt(encryptedBase64: String, email: String): String? {
        try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)

            // Extraer IV (primeros 16 bytes) y datos encriptados (resto)
            val iv = combined.copyOfRange(0, 16)
            val encrypted = combined.copyOfRange(16, combined.size)

            val key = generateKey(email)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)
            val decrypted = cipher.doFinal(encrypted)

            return String(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting: ${e.message}")
            return null
        }
    }
}
