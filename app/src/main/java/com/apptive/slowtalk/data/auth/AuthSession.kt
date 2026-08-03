package com.apptive.slowtalk.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
)

internal interface AuthTokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

private class EncryptedPreferencesAuthTokenStore(
    private val context: Context,
) : AuthTokenStore {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        ENCRYPTED_PREFERENCES_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        // Remove credentials written by the pre-encryption implementation.
        val cleaned = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        check(cleaned) { "Could not remove the legacy authentication session." }
    }

    override fun load(): AuthTokens? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
        return if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) null
        else AuthTokens(accessToken, refreshToken)
    }

    override fun save(tokens: AuthTokens) {
        val saved = preferences.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .commit()
        check(saved) { "Could not persist the authentication session." }
    }

    override fun clear() {
        val cleared = preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .commit()
        check(cleared) { "Could not remove the persisted authentication session." }
    }

    private companion object {
        const val ENCRYPTED_PREFERENCES_NAME = "slowtalk_auth_session_encrypted"
        const val LEGACY_PREFERENCES_NAME = "slowtalk_auth_session"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

object AuthSession {
    private val _tokens = MutableStateFlow<AuthTokens?>(null)
    val tokens: StateFlow<AuthTokens?> = _tokens.asStateFlow()

    val accessToken: String?
        get() = _tokens.value?.accessToken

    val refreshToken: String?
        get() = _tokens.value?.refreshToken

    val isAuthenticated: Boolean
        get() = _tokens.value != null

    private var tokenStore: AuthTokenStore? = null

    @Synchronized
    fun initialize(context: Context) {
        initialize(EncryptedPreferencesAuthTokenStore(context.applicationContext))
    }

    @Synchronized
    internal fun initialize(store: AuthTokenStore) {
        val restored = store.load()
        tokenStore = store
        _tokens.value = restored
    }

    @Synchronized
    fun save(accessToken: String, refreshToken: String) {
        require(accessToken.isNotBlank()) { "Access token must not be blank." }
        require(refreshToken.isNotBlank()) { "Refresh token must not be blank." }
        val store = requireNotNull(tokenStore) {
            "AuthSession must be initialized before saving a session."
        }
        val updated = AuthTokens(accessToken, refreshToken)
        store.save(updated)
        _tokens.value = updated
    }

    @Synchronized
    fun clear() {
        try {
            tokenStore?.clear()
        } finally {
            // Fail closed for the current process even when persistent storage is unavailable.
            _tokens.value = null
        }
    }

    @Synchronized
    fun compareAndClear(expectedAccessToken: String): Boolean {
        if (_tokens.value?.accessToken != expectedAccessToken) return false
        try {
            tokenStore?.clear()
        } finally {
            _tokens.value = null
        }
        return true
    }

    @Synchronized
    internal fun resetForTest() {
        tokenStore = null
        _tokens.value = null
    }
}
