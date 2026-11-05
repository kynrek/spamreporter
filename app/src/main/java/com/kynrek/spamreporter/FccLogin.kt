package com.kynrek.spamreporter

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional saved FCC sign-in (email + password), so the user doesn't retype it every ~8 hours.
 *
 * - Encrypted with AES-256-GCM using its own Android Keystore key that can only be used for 30 seconds after the
 *   user unlocks with fingerprint / face / device PIN. Saving and filling each ask for that unlock.
 * - Only ever typed into the FCC's own sign-in page (consumercomplaints.fcc.gov); never sent anywhere else, never logged.
 * - Saved only after a sign-in actually succeeded, and only if the user agrees.
 * - "Forget saved sign-in" and "Delete all my data" remove both the file and the key.
 */
object FccLogin {
    private const val ALIAS = "spamreporter-fcc-login-v1"
    private const val FILE = "fcc_login.bin"
    private fun f(c: Context) = File(c.filesDir, FILE)

    fun saved(c: Context) = f(c).exists()

    private fun settings(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Settings → "Save FCC password" (default on). Off = never offer to save; turning it off removes a saved one. */
    fun saveEnabled(c: Context) = settings(c).getBoolean("fcc_save_pw", true)
    fun setSaveEnabled(c: Context, on: Boolean) { settings(c).edit().putBoolean("fcc_save_pw", on).apply(); if (!on) forget(c) }
    /** Settings → "Sign in with saved password" (default on): the sign-in button unlocks, fills and submits in one tap. */
    fun autoEnabled(c: Context) = settings(c).getBoolean("fcc_auto_signin", true)
    fun setAutoEnabled(c: Context, on: Boolean) = settings(c).edit().putBoolean("fcc_auto_signin", on).apply()
    /** The email (not secret) is kept in plain prefs so the UI can say which account is saved. */
    fun savedEmail(c: Context) = c.getSharedPreferences("fcc_account_login", Context.MODE_PRIVATE).getString("email", null)

    fun forget(c: Context) {
        f(c).delete(); c.getSharedPreferences("fcc_account_login", Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(30,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(30)
            }
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** What the phone can unlock the saved sign-in with. */
    enum class Lock { FINGERPRINT, PIN_ONLY, NONE }

    fun lock(c: Context): Lock {
        val secure = (c.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
        if (!secure) return Lock.NONE
        val bio = if (Build.VERSION.SDK_INT >= 30)
            c.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        else @Suppress("DEPRECATION") c.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        return if (bio) Lock.FINGERPRINT else Lock.PIN_ONLY
    }

    /** Open the phone's own screen for adding a fingerprint (it asks for a PIN first if there isn't one). The user does the setup. */
    fun openLockSetup(a: Activity) {
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= 30) add(android.content.Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL))
            @Suppress("DEPRECATION") add(android.content.Intent(android.provider.Settings.ACTION_FINGERPRINT_ENROLL))
            add(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
            add(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        }
        for (i in tries) if (runCatching { a.startActivity(i) }.isSuccess) return
    }

    /** Explain the lock and offer the shortcut. [onReady] runs if a lock is already set (fingerprint or PIN). */
    fun explainLock(a: Activity, onReady: (() -> Unit)? = null) {
        val l = lock(a)
        val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
        when (l) {
            Lock.NONE -> d.setTitle("Fingerprint or PIN needed")
                .setMessage("To save and reuse your FCC sign-in, this phone needs a fingerprint or a screen-lock PIN — either one works. " +
                    "It locks the saved password so only you can use it.\n\nThis is optional: without it, just type your FCC password each time you sign in.")
                .setPositiveButton("Set up fingerprint / PIN") { _, _ -> openLockSetup(a) }.setNegativeButton("Not now", null)
            Lock.PIN_ONLY -> d.setTitle("Fingerprint (optional)")
                .setMessage("Your screen-lock PIN already protects a saved FCC sign-in, so you're set. " +
                    "Adding a fingerprint lets you unlock it with a touch instead of typing the PIN.\n\nOnly used to lock your saved FCC sign-in.")
                .setPositiveButton("Add fingerprint") { _, _ -> openLockSetup(a) }
                .setNegativeButton(if (onReady != null) "Use PIN" else "Close") { _, _ -> onReady?.invoke() }
            Lock.FINGERPRINT -> d.setTitle("Fingerprint is on")
                .setMessage("Your saved FCC sign-in is unlocked with your fingerprint, or your PIN as a backup. It's only used for that.")
                .setPositiveButton("OK") { _, _ -> onReady?.invoke() }
        }
        d.show()
    }

    /** Ask for fingerprint / face / PIN, then run [then] (the key is usable for 30 s afterwards). */
    fun unlock(a: Activity, title: String, then: () -> Unit, failed: (String) -> Unit) {
        val b = BiometricPrompt.Builder(a).setTitle(title).setSubtitle("Your FCC sign-in is locked to this phone")
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        try {
            b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = then()
                override fun onAuthenticationError(code: Int, msg: CharSequence) = failed(msg.toString())
            })
        } catch (t: Throwable) { failed(t.message ?: "Couldn't unlock") }   // never crash the screen over the unlock prompt
    }

    /** Must be called within 30 s of [unlock]. */
    fun save(c: Context, email: String, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plain = JSONObject().put("email", email).put("password", password).toString().toByteArray(Charsets.UTF_8)
        f(c).writeBytes(cipher.iv + cipher.doFinal(plain))
        plain.fill(0)
        c.getSharedPreferences("fcc_account_login", Context.MODE_PRIVATE).edit().putString("email", email).apply()
    }

    /** Must be called within 30 s of [unlock]. Returns (email, password) or null. */
    fun load(c: Context): Pair<String, String>? {
        val b = f(c).takeIf { it.exists() }?.readBytes() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        val o = JSONObject(String(cipher.doFinal(b, 12, b.size - 12), Charsets.UTF_8))
        return o.getString("email") to o.getString("password")
    }

    /** JS that reads the sign-in form (evaluated by the app; the page gets no way to call into the app). */
    const val READ_JS = "(()=>{const e=document.querySelector('[data-testid=email-input],input[type=email]'),p=document.querySelector('[data-testid=password-input],input[type=password]');" +
        "return JSON.stringify({e:e?e.value:'',p:p?p.value:''})})()"

    /** JS that presses the FCC form's own Sign in button. */
    const val SUBMIT_JS = "(()=>{const b=document.querySelector('#login-form button[type=submit],form[data-testid=login-form] button[type=submit]');" +
        "if(!b)return false;b.click();return true})()"

    /** JS that fills the sign-in form the way typing would (React-controlled inputs need the native setter + events). */
    fun fillJs(email: String, password: String) =
        "(()=>{const set=(el,v)=>{if(!el)return false;Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,v);" +
        "['input','change'].forEach(t=>el.dispatchEvent(new Event(t,{bubbles:true})));return true};" +
        "const e=document.querySelector('[data-testid=email-input],input[type=email]'),p=document.querySelector('[data-testid=password-input],input[type=password]');" +
        "return set(e,${JSONObject.quote(email)})&&set(p,${JSONObject.quote(password)})})()"
}
