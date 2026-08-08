package com.jerreader.android.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TranslationCredentialStore {
    fun read(account: String): String?
    fun save(account: String, secret: String?)
}

class AndroidKeystoreCredentialStore(context: Context) : TranslationCredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(account: String): String? {
        val encoded = preferences.getString(account, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_LENGTH)
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val encrypted = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.doFinal(encrypted).decodeToString()
        }.getOrElse {
            preferences.edit().remove(account).apply()
            null
        }
    }

    override fun save(account: String, secret: String?) {
        val normalized = secret?.trim().orEmpty()
        if (normalized.isEmpty()) {
            preferences.edit().remove(account).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(normalized.encodeToByteArray())
        val payload = cipher.iv + encrypted
        preferences.edit()
            .putString(account, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "jerreader_translation_credentials"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "Jerreader.translation.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
