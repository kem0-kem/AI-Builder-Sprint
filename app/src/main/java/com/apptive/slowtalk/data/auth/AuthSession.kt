package com.apptive.slowtalk.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class StoredTokens(val accessToken: String, val refreshToken: String)

object AuthSession {
    private const val PREFS_NAME = "slowtalk_auth"

    @Volatile private var preferences: android.content.SharedPreferences? = null
    @Volatile private var tokens: StoredTokens? = null

    val accessToken: String? get() = tokens?.accessToken
    val refreshToken: String? get() = tokens?.refreshToken
    val isSignedIn: Boolean get() = accessToken != null && refreshToken != null

    fun initialize(context: Context) {
        if (preferences != null) return
        synchronized(this) {
            if (preferences == null) {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                preferences = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { prefs ->
                    val access = prefs.getString("access_token", null)
                    val refresh = prefs.getString("refresh_token", null)
                    tokens = if (access.isNullOrBlank() || refresh.isNullOrBlank()) null
                    else StoredTokens(access, refresh)
                }
            }
        }
    }

    fun save(accessToken: String, refreshToken: String) {
        val prefs = checkNotNull(preferences) { "AuthSession is not initialized" }
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .apply()
        tokens = StoredTokens(accessToken, refreshToken)
    }

    fun clear() {
        preferences?.edit()?.clear()?.apply()
        tokens = null
    }
}
