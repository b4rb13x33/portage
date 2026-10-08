package com.portage.app.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.vaultStore: DataStore<Preferences> by preferencesDataStore(name = "vault")

/**
 * Encrypted key-value store backed by the Android Keystore.
 *
 * The master key lives in AndroidKeyStore and is non-exportable; only
 * ciphertext is persisted to DataStore. Payload layout is
 * `GCM_IV (12 bytes) || ciphertext`, base64-encoded with no wrapping.
 */
class Vault(private val context: Context) {

    private val store get() = context.vaultStore

    suspend fun put(id: String, value: String) {
        val blob = encode(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(blob, Base64.NO_WRAP)
        store.edit { it[stringPreferencesKey(prefix + id)] = encoded }
    }

    suspend fun get(id: String): String? {
        val encoded = store.data.first()[stringPreferencesKey(prefix + id)] ?: return null
        return decode(Base64.decode(encoded, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    suspend fun delete(id: String) {
        store.edit { it.remove(stringPreferencesKey(prefix + id)) }
    }

    suspend fun has(id: String): Boolean = get(id) != null

    private fun encode(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        synchronized(lock) {
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext)
            return ByteArray(iv.size + body.size).also {
                iv.copyInto(it, 0)
                body.copyInto(it, iv.size)
            }
        }
    }

    private fun decode(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "malformed vault payload" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        synchronized(lock) {
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            return cipher.doFinal(body)
        }
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val ALIAS = "com.portage.master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val prefix = "secret:"
        val lock = Any()
    }
}
