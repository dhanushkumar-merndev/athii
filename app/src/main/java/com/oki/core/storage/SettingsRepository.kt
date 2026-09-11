package com.oki.core.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.*

private val Context.settingsStore by preferencesDataStore("oki_settings")

enum class SoundMode {
    SYSTEM,
    CUSTOM,
    SILENT,
}

enum class Appearance {
    DARK,
    LIGHT,
    SYSTEM,
}

enum class ReasoningEffort {
    LOW,
    HIGH,
}

data class Settings(
    val defaultOffset: Int = 5,
    val soundMode: SoundMode = SoundMode.SYSTEM,
    val customSoundUri: String = "",
    val appearance: Appearance = Appearance.DARK,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
)

class SettingsRepository(context: Context) {
    private val store = context.settingsStore
    private val offset = intPreferencesKey("default_reminder_offset")
    private val sound = stringPreferencesKey("sound_mode")
    private val uri = stringPreferencesKey("custom_sound_uri")
    private val theme = stringPreferencesKey("appearance")
    private val reasoning = stringPreferencesKey("reasoning_effort")
    val settings: Flow<Settings> =
        store.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map {
                Settings(
                    it[offset] ?: 5,
                    runCatching { SoundMode.valueOf(it[sound] ?: "SYSTEM") }
                        .getOrDefault(SoundMode.SYSTEM),
                    it[uri].orEmpty(),
                    runCatching { Appearance.valueOf(it[theme] ?: "DARK") }
                        .getOrDefault(Appearance.DARK),
                    runCatching { ReasoningEffort.valueOf(it[reasoning] ?: "HIGH") }
                        .getOrDefault(ReasoningEffort.HIGH),
                )
            }

    suspend fun setOffset(value: Int) {
        require(value in 0..525600)
        store.edit { it[offset] = value }
    }

    suspend fun setSound(mode: SoundMode, soundUri: String = "") {
        store.edit {
            it[sound] = mode.name
            it[uri] = soundUri
        }
    }

    suspend fun setAppearance(value: Appearance) {
        store.edit { it[theme] = value.name }
    }

    suspend fun setReasoningEffort(value: ReasoningEffort) {
        store.edit { it[reasoning] = value.name }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }
}
