package com.cc3301.ccplayer.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 密码落在 EncryptedSharedPreferences：键是网络源地址。 */
class EncryptedCredentialStore(private val preferences: SharedPreferences) : CredentialStore {

    override suspend fun put(key: String, secret: String) {
        preferences.edit { putString(key, secret) }
    }

    override suspend fun get(key: String): String? = preferences.getString(key, null)

    override suspend fun remove(key: String) {
        preferences.edit { remove(key) }
    }

    companion object {
        private const val FILE_NAME = "network_credentials"

        fun create(context: Context): EncryptedCredentialStore {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val preferences = EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedCredentialStore(preferences)
        }
    }
}
