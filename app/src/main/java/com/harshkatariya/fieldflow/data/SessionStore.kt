package com.harshkatariya.fieldflow.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.harshkatariya.fieldflow.core.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferences by preferencesDataStore("preferences")
@Singleton class SessionStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val sessionMutex = Mutex()
    val session = MutableStateFlow<Session?>(null)
    val ready = MutableStateFlow(false)
    val theme = context.preferences.data.map { it[stringPreferencesKey("theme")] ?: "system" }
    suspend fun setTheme(value: String) { context.preferences.edit { it[stringPreferencesKey("theme")] = value } }
    private val file get() = File(context.noBackupFilesDir, "session.enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("fieldflow-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("fieldflow-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun restore() = withContext(Dispatchers.IO) { sessionMutex.withLock {
        session.value = runCatching {
            val data = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            Wire.decodeFromString<Session>(cipher.doFinal(data.copyOfRange(12, data.size)).decodeToString()).takeIf { Rules.validSession(it, now()) }
        }.getOrNull()
        if (session.value == null) file.delete()
        ready.value = true
    } }
    suspend fun save(value: Session) = withContext(Dispatchers.IO) { sessionMutex.withLock {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val temp = File(context.noBackupFilesDir, "session.tmp")
        temp.writeBytes(cipher.iv + cipher.doFinal(Wire.encodeToString(value).encodeToByteArray()))
        check(temp.renameTo(file)) { "Unable to store encrypted session" }
        session.value = value
    } }
    suspend fun clear(expected: Session? = null) = withContext(Dispatchers.IO) { sessionMutex.withLock {
        // A late response from an older worker must not delete a newly authenticated session.
        if (expected == null || session.value == expected) { file.delete(); session.value = null }
    } }
}
