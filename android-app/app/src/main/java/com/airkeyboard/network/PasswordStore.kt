package com.airkeyboard.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The derived keys K of password-protected PCs, by the PC's salt (base64), never the password.
 *
 * Kept in memory for the process lifetime (reconnects need no prompt). When asked to persist,
 * K is also encrypted with an AndroidKeyStore AES-256-GCM key and written to
 * noBackupFilesDir/saved_keys.json, which backups skip. Any Keystore or crypto failure drops
 * that entry and falls back to memory only. A new PC password gives a new salt, which simply
 * misses. Logs only exception classes.
 */
object PasswordStore {
    private const val TAG = "PasswordStore"
    private const val KEY_ALIAS = "rm_saved_keys"
    private const val FILE_NAME = "saved_keys.json"
    private const val MAX_SAVED = 20
    private const val GCM_TAG_BITS = 128

    private val memory = HashMap<String, ByteArray>()
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
    }

    /** A copy of the saved K for [salt], or null */
    @Synchronized
    fun get(salt: String): ByteArray? {
        memory[salt]?.let { return it.copyOf() }
        val saved = readFile()
        val entry = saved.optString(salt, "")
        if (entry.isEmpty()) return null
        return try {
            val blob = AuthCrypto.fromBase64(entry) ?: throw IllegalStateException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, 12))
            val key = cipher.doFinal(blob, 12, blob.size - 12)
            memory[salt] = key
            key.copyOf()
        } catch (e: Exception) {
            Log.w(TAG, "Saved key unreadable: ${e.javaClass.simpleName}")
            saved.remove(salt)
            writeFile(saved)
            null
        }
    }

    /** Remember a copy of [key] for [salt]; [persist] also saves it encrypted on the tablet */
    @Synchronized
    fun put(salt: String, key: ByteArray, persist: Boolean) {
        memory.remove(salt)?.fill(0)
        memory[salt] = key.copyOf()
        if (!persist) return
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val blob = cipher.iv + cipher.doFinal(key)
            val saved = readFile()
            saved.remove(salt)
            // Keys keep insertion order: drop the oldest
            while (saved.length() >= MAX_SAVED) saved.remove(saved.keys().next())
            saved.put(salt, AuthCrypto.toBase64(blob))
            writeFile(saved)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save key: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun remove(salt: String) {
        memory.remove(salt)?.fill(0)
        val saved = readFile()
        if (saved.has(salt)) {
            saved.remove(salt)
            writeFile(saved)
        }
    }

    /** Forget every PC's key, in memory and on the tablet */
    @Synchronized
    fun clear() {
        memory.values.forEach { it.fill(0) }
        memory.clear()
        file?.delete()
    }

    private fun readFile(): JSONObject {
        val f = file ?: return JSONObject()
        return try {
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "Saved keys unreadable: ${e.javaClass.simpleName}")
            JSONObject()
        }
    }

    private fun writeFile(saved: JSONObject) {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(saved.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write saved keys: ${e.javaClass.simpleName}")
        }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
