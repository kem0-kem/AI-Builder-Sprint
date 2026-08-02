package com.apptive.slowtalk.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class StoredTokens(
    val accessToken: String,
    val refreshToken: String
)

/**
 * Keeps credentials out of plain-text preferences. Call [initialize] before creating API clients.
 */
object AuthSession {
    private const val PREFS_NAME = "slowtalk_auth"
    private const val ACCESS_TOKEN_KEY = "access_token"
    private const val REFRESH_TOKEN_KEY = "refresh_token"

    @Volatile
    private var tokenStore: TokenStore? = null

    @Volatile
    private var tokens: StoredTokens? = null

    val accessToken: String?
        get() = tokens?.accessToken

    val refreshToken: String?
        get() = tokens?.refreshToken

    val isSignedIn: Boolean
        get() = accessToken != null && refreshToken != null

    fun initialize(context: Context) {
        if (tokenStore != null) return
        synchronized(this) {
            if (tokenStore == null) {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val preferences = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                tokenStore = SharedPreferencesTokenStore(preferences).also { tokens = it.read() }
            }
        }
    }

    fun save(accessToken: String, refreshToken: String) {
        val newTokens = StoredTokens(accessToken, refreshToken)
        requireStore().write(newTokens)
        tokens = newTokens
    }

    fun clear() {
        requireStore().clear()
        tokens = null
    }

    private fun requireStore(): TokenStore = checkNotNull(tokenStore) {
        "AuthSession must be initialized before it is used"
    }
}

private interface TokenStore {
    fun read(): StoredTokens?
    fun write(tokens: StoredTokens)
    fun clear()
}

private class SharedPreferencesTokenStore(
    private val preferences: android.content.SharedPreferences
) : TokenStore {
    override fun read(): StoredTokens? {
        val access = preferences.getString("access_token", null)
        val refresh = preferences.getString("refresh_token", null)
        return if (access.isNullOrBlank() || refresh.isNullOrBlank()) null else StoredTokens(access, refresh)
    }

    override fun write(tokens: StoredTokens) {
        preferences.edit()
            .putString("access_token", tokens.accessToken)
            .putString("refresh_token", tokens.refreshToken)
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}
