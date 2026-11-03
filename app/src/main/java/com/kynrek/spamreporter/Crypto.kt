package com.kynrek.spamreporter

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for everything the app stores (profile, messages, filings).
 * AES-256-GCM with a non-exportable key held in the Android Keystore (hardware-backed where available).
 * File format: [12-byte IV][ciphertext+tag]. The key never leaves the device and is destroyed on uninstall.
 */
object Crypto {
    private const val ALIAS = "spamreporter-data-v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun write(f: File, text: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
        val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeBytes(out); tmp.renameTo(f)   // atomic replace
    }

    fun read(f: File): String? {
        if (!f.exists()) return null
        val b = f.readBytes(); if (b.size < 13) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }

    /** Wipes all stored data and the key. */
    fun wipe(dir: File) {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}
