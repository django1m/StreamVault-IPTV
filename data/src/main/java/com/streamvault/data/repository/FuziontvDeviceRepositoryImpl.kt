package com.streamvault.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamvault.data.remote.fuziontv.CredentialsResponse
import com.streamvault.data.remote.fuziontv.FuziontvApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.fuziontvStore: DataStore<Preferences> by preferencesDataStore("fuziontv_device")

class FuziontvDeviceRepositoryImpl(
    private val context: Context,
    val api: FuziontvApi,
) {
    companion object {
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val DEVICE_TOKEN = stringPreferencesKey("device_token")
    }

    val deviceTokenFlow: Flow<String?> =
        context.fuziontvStore.data.map { it[DEVICE_TOKEN] }

    suspend fun isActivated(): Boolean = deviceTokenFlow.first() != null

    suspend fun deviceId(): String {
        val existing = context.fuziontvStore.data.first()[DEVICE_ID]
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        context.fuziontvStore.edit { it[DEVICE_ID] = fresh }
        return fresh
    }

    suspend fun deviceToken(): String? = deviceTokenFlow.first()

    suspend fun saveDeviceToken(token: String) {
        context.fuziontvStore.edit { it[DEVICE_TOKEN] = token }
    }

    suspend fun clearActivation() {
        context.fuziontvStore.edit { it.remove(DEVICE_TOKEN) }
    }

    suspend fun fetchCredentials(): CredentialsResponse {
        val id = deviceId()
        val token = deviceToken() ?: error("device not activated")
        return api.credentials(id, token)
    }
}
