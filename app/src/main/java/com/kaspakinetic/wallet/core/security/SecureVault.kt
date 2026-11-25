package com.kaspakinetic.wallet.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Hardware-backed Android Keystore / StrongBox AES-256-GCM vault for Kaspa seed material,
 * off-main-thread 6-digit PIN PBKDF2 hashing/verification, optional wallet password,
 * and BiometricPrompt integration.
 */
class SecureVault(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    data class DecryptedSecret(
        val mnemonic: String,
        val passphrase: String,
    )

    private fun ensureVaultKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            generator.init(buildKeySpec(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P))
            generator.generateKey()
        } catch (_: Exception) {
            if (store.containsAlias(KEY_ALIAS)) {
                runCatching { store.deleteEntry(KEY_ALIAS) }
            }
            generator.init(buildKeySpec(strongBox = false))
            generator.generateKey()
        }
    }

    private fun buildKeySpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= 35) {
            builder.setUnlockedDeviceRequired(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(strongBox)
        }
        return builder.build()
    }

    fun isHardwareBacked(): Boolean = runCatching {
        val key = ensureVaultKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
    }.getOrDefault(false)

    suspend fun storeWalletSecret(
        walletId: String,
        mnemonic: String,
        passphrase: String,
    ) = withContext(Dispatchers.IO) {
        val key = ensureVaultKey()
        val payloadBytes = encodeSecretPayload(mnemonic, passphrase)
        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD("kaspa-kinetic-wallet:$walletId".toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(payloadBytes)
            val iv = cipher.iv
            val committed = prefs.edit()
                .putString("w.$walletId.ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString("w.$walletId.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .commit()
            ciphertext.fill(0)
            iv.fill(0)
            check(committed) { "Failed to persist encrypted wallet material" }
        } finally {
            payloadBytes.fill(0)
        }
    }

    suspend fun <T> withDecryptedSecret(
        walletId: String,
        block: (mnemonic: String, passphrase: String) -> T,
    ): T = withContext(Dispatchers.Default) {
        val ctB64 = prefs.getString("w.$walletId.ct", null)
            ?: error("Encrypted wallet material not found")
        val ivB64 = prefs.getString("w.$walletId.iv", null)
            ?: error("Encrypted wallet IV not found")
        val ciphertext = Base64.decode(ctB64, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val plain = try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, ensureVaultKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD("kaspa-kinetic-wallet:$walletId".toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext)
        } finally {
            ciphertext.fill(0)
            iv.fill(0)
        }
        try {
            val secret = decodeSecretPayload(plain)
            block(secret.mnemonic, secret.passphrase)
        } finally {
            plain.fill(0)
        }
    }

    fun hasWalletSecret(walletId: String): Boolean =
        prefs.contains("w.$walletId.ct") && prefs.contains("w.$walletId.iv")

    fun deleteWalletSecret(walletId: String) {
        prefs.edit()
            .remove("w.$walletId.ct")
            .remove("w.$walletId.iv")
            .commit()
    }

    // -------------------------------------------------------------------------
    // 6-Digit Security PIN (Always Hashed / Verified Off Main Thread)
    // -------------------------------------------------------------------------

    fun hasPin(): Boolean =
        prefs.contains(KEY_PIN_SALT) && prefs.contains(KEY_PIN_HASH)

    suspend fun setPin(pin: String) = withContext(Dispatchers.Default) {
        require(pin.length == 6 && pin.all { it.isDigit() }) { "PIN must be exactly 6 digits" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val digest = pbkdf2Hash(pin.toCharArray(), salt, PIN_ITERATIONS)
        try {
            prefs.edit()
                .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_PIN_HASH, Base64.encodeToString(digest, Base64.NO_WRAP))
                .commit()
        } finally {
            salt.fill(0)
            digest.fill(0)
        }
    }

    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return@withContext false
        val hashB64 = prefs.getString(KEY_PIN_HASH, null) ?: return@withContext false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        val computed = pbkdf2Hash(pin.toCharArray(), salt, PIN_ITERATIONS)
        try {
            MessageDigest.isEqual(expected, computed)
        } finally {
            salt.fill(0)
            expected.fill(0)
            computed.fill(0)
        }
    }

    suspend fun removePin() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .commit()
    }

    // -------------------------------------------------------------------------
    // Optional Wallet Password
    // -------------------------------------------------------------------------

    fun hasWalletPassword(): Boolean =
        prefs.contains(KEY_PW_SALT) && prefs.contains(KEY_PW_HASH)

    suspend fun setWalletPassword(password: String) = withContext(Dispatchers.Default) {
        require(password.length >= 6) { "Password must be at least 6 characters" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val digest = pbkdf2Hash(password.toCharArray(), salt, PIN_ITERATIONS)
        try {
            prefs.edit()
                .putString(KEY_PW_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_PW_HASH, Base64.encodeToString(digest, Base64.NO_WRAP))
                .commit()
        } finally {
            salt.fill(0)
            digest.fill(0)
        }
    }

    suspend fun verifyWalletPassword(password: String): Boolean = withContext(Dispatchers.Default) {
        val saltB64 = prefs.getString(KEY_PW_SALT, null) ?: return@withContext false
        val hashB64 = prefs.getString(KEY_PW_HASH, null) ?: return@withContext false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        val computed = pbkdf2Hash(password.toCharArray(), salt, PIN_ITERATIONS)
        try {
            MessageDigest.isEqual(expected, computed)
        } finally {
            salt.fill(0)
            expected.fill(0)
            computed.fill(0)
        }
    }

    suspend fun removeWalletPassword() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_PW_SALT)
            .remove(KEY_PW_HASH)
            .commit()
    }

    private fun pbkdf2Hash(chars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun encodeSecretPayload(mnemonic: String, passphrase: String): ByteArray {
        val mBytes = mnemonic.toByteArray(Charsets.UTF_8)
        val pBytes = passphrase.toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + mBytes.size + 4 + pBytes.size)
        writeIntBE(out, 0, mBytes.size)
        mBytes.copyInto(out, 4)
        writeIntBE(out, 4 + mBytes.size, pBytes.size)
        pBytes.copyInto(out, 8 + mBytes.size)
        mBytes.fill(0)
        pBytes.fill(0)
        return out
    }

    private fun decodeSecretPayload(data: ByteArray): DecryptedSecret {
        require(data.size >= 8) { "Corrupted encrypted payload" }
        val mLen = readIntBE(data, 0)
        require(mLen >= 0 && 8 + mLen <= data.size) { "Corrupted mnemonic length" }
        val pLen = readIntBE(data, 4 + mLen)
        require(pLen >= 0 && 8 + mLen + pLen <= data.size) { "Corrupted passphrase length" }
        val mnemonic = String(data, 4, mLen, Charsets.UTF_8)
        val passphrase = String(data, 8 + mLen, pLen, Charsets.UTF_8)
        return DecryptedSecret(mnemonic, passphrase)
    }

    private fun writeIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun readIntBE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "kaspa_kinetic_master_wrap_v1"
        private const val PREFS_NAME = "kaspa_kinetic_secure_vault_v1"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val KEY_PIN_SALT = "pin_salt_v1"
        private const val KEY_PIN_HASH = "pin_hash_v1"
        private const val KEY_PW_SALT = "pw_salt_v1"
        private const val KEY_PW_HASH = "pw_hash_v1"
        private const val PIN_ITERATIONS = 100_000

        fun isBiometricAvailable(context: Context): Boolean {
            val mgr = BiometricManager.from(context)
            val result = mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            return result == BiometricManager.BIOMETRIC_SUCCESS
        }

        fun promptBiometric(
            activity: FragmentActivity,
            title: String,
            subtitle: String,
            negativeButtonText: String,
            onSuccess: () -> Unit,
            onFallbackOrError: (String) -> Unit,
        ) {
            runCatching {
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            onFallbackOrError(errString.toString())
                        }
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButtonText(negativeButtonText)
                    .build()
                prompt.authenticate(info)
            }.onFailure { err ->
                onFallbackOrError(err.message ?: "Biometrics unavailable")
            }
        }
    }
}
