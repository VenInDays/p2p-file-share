package com.p2pfileshare.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security Manager for P2P File Share v1.7.0
 *
 * Features:
 * - AES-256-GCM encryption for sensitive data at rest
 * - HMAC-SHA256 API token authentication
 * - Rate limiting per IP (60 requests/minute)
 * - App signature verification (anti-tampering)
 * - Secure token generation and validation
 */
object SecurityManager {

    private const val TAG = "SecurityManager"

    // AES-256-GCM constants
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val AES_ALGORITHM = "AES/GCM/NoPadding"

    // HMAC-SHA256 for API tokens
    private const val HMAC_ALGORITHM = "HmacSHA256"

    // Rate limiting
    private const val RATE_LIMIT_WINDOW_MS = 60_000L // 1 minute
    private const val RATE_LIMIT_MAX_REQUESTS = 60 // 60 requests per minute per IP
    private val rateLimitMap = ConcurrentHashMap<String, RateLimitEntry>()

    // API token - generated once per app installation
    private var apiToken: String = ""
    private var hmacKey: ByteArray = ByteArray(0)

    // Data class for rate limit tracking
    data class RateLimitEntry(
        var requestCount: Int = 0,
        var windowStart: Long = System.currentTimeMillis()
    )

    /**
     * Initialize the security manager.
     * Generates or loads the API token and HMAC key.
     */
    fun initialize(context: Context) {
        try {
            val prefs = context.getSharedPreferences("p2p_security", Context.MODE_PRIVATE)

            // Load or generate API token
            apiToken = prefs.getString("api_token", null) ?: run {
                val token = generateSecureToken(32)
                prefs.edit().putString("api_token", token).apply()
                token
            }

            // Load or generate HMAC key
            val existingKey = prefs.getString("hmac_key", null)
            hmacKey = if (existingKey != null) {
                Base64.decode(existingKey, Base64.NO_WRAP)
            } else {
                val key = ByteArray(32)
                SecureRandom().nextBytes(key)
                prefs.edit().putString("hmac_key", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
                key
            }

            Log.d(TAG, "SecurityManager initialized. Token: ${apiToken.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SecurityManager", e)
            // Fallback: generate ephemeral keys
            apiToken = generateSecureToken(32)
            hmacKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
    }

    /**
     * Get the current API token.
     * This token must be sent with every API request.
     */
    fun getApiToken(): String = apiToken

    /**
     * Validate an API token from an incoming request.
     */
    fun validateApiToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return token == apiToken
    }

    /**
     * Generate HMAC-SHA256 signature for a request.
     * Used to sign API requests so they cannot be replayed or forged.
     */
    fun generateHmacSignature(method: String, path: String, timestamp: Long): String {
        val message = "$method:$path:$timestamp"
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(hmacKey, HMAC_ALGORITHM))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    /**
     * Validate HMAC-SHA256 signature from an incoming request.
     * Also checks that the timestamp is within 5 minutes to prevent replay attacks.
     */
    fun validateHmacSignature(method: String, path: String, timestamp: Long, signature: String): Boolean {
        try {
            // Check timestamp is within 5 minutes
            val now = System.currentTimeMillis()
            if (Math.abs(now - timestamp) > 5 * 60 * 1000) {
                Log.w(TAG, "Request timestamp out of range: $timestamp (now=$now)")
                return false
            }

            val expectedSignature = generateHmacSignature(method, path, timestamp)
            return expectedSignature == signature
        } catch (e: Exception) {
            Log.e(TAG, "HMAC validation error", e)
            return false
        }
    }

    /**
     * Check rate limit for an IP address.
     * Returns true if the request is allowed, false if rate limited.
     */
    fun checkRateLimit(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val entry = rateLimitMap.getOrPut(clientIp) { RateLimitEntry() }

        synchronized(entry) {
            // Reset window if expired
            if (now - entry.windowStart > RATE_LIMIT_WINDOW_MS) {
                entry.requestCount = 0
                entry.windowStart = now
            }

            entry.requestCount++

            if (entry.requestCount > RATE_LIMIT_MAX_REQUESTS) {
                Log.w(TAG, "Rate limit exceeded for $clientIp: ${entry.requestCount} requests")
                return false
            }

            return true
        }
    }

    /**
     * Encrypt data using AES-256-GCM.
     * Returns Base64-encoded string: IV + ciphertext + tag
     */
    fun encryptData(plaintext: String): String {
        try {
            val keySpec = SecretKeySpec(hmacKey, "AES") // Use HMAC key as AES key (both 256-bit)
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // Prepend IV to ciphertext
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            return ""
        }
    }

    /**
     * Decrypt data using AES-256-GCM.
     * Input is Base64-encoded string: IV + ciphertext + tag
     */
    fun decryptData(encrypted: String): String {
        try {
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH + 16) return "" // Too short

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val keySpec = SecretKeySpec(hmacKey, "AES")
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val plaintext = cipher.doFinal(ciphertext)
            return String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            return ""
        }
    }

    /**
     * Verify that the app signature matches the expected signature.
     * This prevents tampered/fake apps from accessing the API.
     */
    fun verifyAppSignature(context: Context, expectedSignatures: List<String>): Boolean {
        try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )

            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.map { sign ->
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(sign.toByteArray())
                    Base64.encodeToString(digest, Base64.NO_WRAP)
                } ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.map { sign ->
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(sign.toByteArray())
                    Base64.encodeToString(digest, Base64.NO_WRAP)
                } ?: emptyList()
            }

            // If no expected signatures configured, allow all
            if (expectedSignatures.isEmpty()) return true

            return signatures.any { it in expectedSignatures }
        } catch (e: Exception) {
            Log.e(TAG, "App signature verification failed", e)
            return false
        }
    }

    /**
     * Generate a secure random token of the specified length (bytes).
     */
    private fun generateSecureToken(length: Int): String {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
            .replace("/", "_")
            .replace("+", "-")
            .replace("=", "")
    }

    /**
     * Clean up expired rate limit entries periodically.
     */
    fun cleanupRateLimits() {
        val now = System.currentTimeMillis()
        val expiredKeys = rateLimitMap.entries
            .filter { now - it.value.windowStart > RATE_LIMIT_WINDOW_MS * 2 }
            .map { it.key }

        expiredKeys.forEach { rateLimitMap.remove(it) }
    }

    /**
     * Obfuscate sensitive data for storage.
     * Makes data meaningless when viewed via terminal or file browser.
     */
    fun obfuscateForStorage(data: String): String {
        return encryptData(data)
    }

    /**
     * De-obfuscate data from storage.
     */
    fun deobfuscateFromStorage(obfuscated: String): String {
        return decryptData(obfuscated)
    }
}
