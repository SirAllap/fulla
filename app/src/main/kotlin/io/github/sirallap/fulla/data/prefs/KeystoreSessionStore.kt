// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.sirallap.fulla.client.remote.Session
import io.github.sirallap.fulla.client.remote.SessionStore
import io.github.sirallap.fulla.client.wire.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The session, encrypted at rest with an AES-GCM key that lives in the
 * Android Keystore and never leaves it. The file holds only ciphertext.
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val file = File(context.noBackupFilesDir, "session.bin")
    private val lock = Mutex()
    private var cached: Session? = null
    private var loaded = false

    override suspend fun load(): Session? = lock.withLock {
        if (!loaded) {
            cached = withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            loaded = true
        }
        cached
    }

    override suspend fun save(session: Session?) {
        lock.withLock {
            cached = session
            loaded = true
            withContext(Dispatchers.IO) {
                if (session == null) file.delete() else write(session)
            }
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun write(session: Session) {
        val plain = JsonObject(mapOf(
            "access_token" to JsonPrimitive(session.accessToken),
            "refresh_token" to JsonPrimitive(session.refreshToken),
            "expires_at" to JsonPrimitive(session.expiresAt),
            "user_id" to JsonPrimitive(session.userId),
            "email" to (session.email?.let(::JsonPrimitive) ?: JsonNull),
        )).toString().toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain)
        val tmp = File(file.parentFile, "session.tmp")
        tmp.writeText(Base64.encodeToString(sealed, Base64.NO_WRAP))
        tmp.renameTo(file)
    }

    private fun read(): Session? {
        if (!file.exists()) return null
        val sealed = Base64.decode(file.readText(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        val o = Wire.json.parseToJsonElement(String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        return Session(
            accessToken = s("access_token") ?: return null,
            refreshToken = s("refresh_token") ?: return null,
            expiresAt = (o["expires_at"] as? JsonPrimitive)?.longOrNull ?: 0,
            userId = s("user_id") ?: return null,
            email = s("email"),
        )
    }

    private companion object {
        const val ALIAS = "fulla.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
