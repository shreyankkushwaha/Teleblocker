package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        val IS_PROTECTION_ENABLED = booleanPreferencesKey("is_protection_enabled")
        val IS_STRICT_MODE = booleanPreferencesKey("is_strict_mode")
        val CUSTOM_BLOCK_MESSAGE = stringPreferencesKey("custom_block_message")
    }

    val isProtectionEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_PROTECTION_ENABLED] ?: false }
    val isStrictMode: Flow<Boolean> = context.dataStore.data.map { it[IS_STRICT_MODE] ?: true }
    val customBlockMessage: Flow<String> = context.dataStore.data.map { it[CUSTOM_BLOCK_MESSAGE] ?: "This Telegram channel is not on the allowed list." }

    suspend fun setProtectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[IS_PROTECTION_ENABLED] = enabled }
    }

    suspend fun setStrictMode(enabled: Boolean) {
        context.dataStore.edit { it[IS_STRICT_MODE] = enabled }
    }

    suspend fun setCustomBlockMessage(message: String) {
        context.dataStore.edit { it[CUSTOM_BLOCK_MESSAGE] = message }
    }
}
