
package com.openrouter.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecurityManager {
    private const val ALIAS = "OpenRouterKeySecureAlias"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PROVIDER = "AndroidKeyStore"

    init {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return keyStore.getKey(ALIAS, null) as SecretKey
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        val combined = Base64.decode(cipherText, Base64.DEFAULT)
        val iv = ByteArray(12)
        val encryptedBytes = ByteArray(combined.size - iv.size)
        System.arraycopy(combined, 0, iv, 0, iv.size)
        System.arraycopy(combined, iv.size, encryptedBytes, 0, encryptedBytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }
}
