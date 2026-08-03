package com.apptive.slowtalk.data.auth

import android.content.Context
import android.content.SharedPreferences

data class AuthTokens(val accessToken: String, val refreshToken: String)

internal interface AuthTokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

private class SharedPreferencesAuthTokenStore(
    private val preferences: SharedPreferences,
) : AuthTokenStore {
    override fun load(): AuthTokens? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
        return if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) null
        else AuthTokens(accessToken, refreshToken)
    }

    override fun save(tokens: AuthTokens) {
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

object AuthSession {
    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var refreshToken: String? = null
        private set

    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    private var tokenStore: AuthTokenStore? = null

    @Synchronized
    fun initialize(context: Context) {
        initialize(
            SharedPreferencesAuthTokenStore(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            ),
        )
    }

    @Synchronized
    internal fun initialize(store: AuthTokenStore) {
        tokenStore = store
        val restored = store.load()
        accessToken = restored?.accessToken
        refreshToken = restored?.refreshToken
    }

    @Synchronized
    fun save(accessToken: String, refreshToken: String) {
        require(accessToken.isNotBlank()) { "Access token must not be blank." }
        require(refreshToken.isNotBlank()) { "Refresh token must not be blank." }
        val store = requireNotNull(tokenStore) {
            "AuthSession must be initialized before saving a session."
        }
        val tokens = AuthTokens(accessToken, refreshToken)
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        store.save(tokens)
    }

    @Synchronized
    fun clear() {
        accessToken = null
        refreshToken = null
        tokenStore?.clear()
    }

    @Synchronized
    internal fun resetForTest() {
        accessToken = null
        refreshToken = null
        tokenStore = null
    }

    private const val PREFERENCES_NAME = "slowtalk_auth_session"
}
