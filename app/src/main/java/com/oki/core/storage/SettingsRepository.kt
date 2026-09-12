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

/** Which audio an ALARM-mode task rings with. RINGTONE is the phone's incoming-call tone. */
enum class AlarmSound {
    RINGTONE,
    ALARM,
    CUSTOM,
}

/** How long a completed task is kept before Athii removes it on its own. */
enum class AutoDeleteCompleted(val days: Int?) {
    NEVER(null),
    ONE_DAY(1),
    ONE_WEEK(7),
    ONE_MONTH(30),
}

data class Settings(
    val defaultOffset: Int = 5,
    val soundMode: SoundMode = SoundMode.SYSTEM,
    val customSoundUri: String = "",
    val appearance: Appearance = Appearance.DARK,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val alarmSound: AlarmSound = AlarmSound.RINGTONE,
    val alarmSoundUri: String = "",
    val autoDeleteCompleted: AutoDeleteCompleted = AutoDeleteCompleted.NEVER,
    /** Off by default: alerts stay quiet during Do Not Disturb unless explicitly allowed. */
    val bypassDnd: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val store = context.settingsStore
    private val offset = intPreferencesKey("default_reminder_offset")
    private val sound = stringPreferencesKey("sound_mode")
    private val uri = stringPreferencesKey("custom_sound_uri")
    private val theme = stringPreferencesKey("appearance")
    private val reasoning = stringPreferencesKey("reasoning_effort")
    private val alarmSound = stringPreferencesKey("alarm_sound")
    private val alarmUri = stringPreferencesKey("alarm_sound_uri")
    private val autoDelete = stringPreferencesKey("auto_delete_completed")
    private val dnd = booleanPreferencesKey("bypass_dnd")
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
                    runCatching { AlarmSound.valueOf(it[alarmSound] ?: "RINGTONE") }
                        .getOrDefault(AlarmSound.RINGTONE),
                    it[alarmUri].orEmpty(),
                    runCatching { AutoDeleteCompleted.valueOf(it[autoDelete] ?: "NEVER") }
                        .getOrDefault(AutoDeleteCompleted.NEVER),
                    it[dnd] ?: false,
                )
            }

    suspend fun setSound(mode: SoundMode, soundUri: String = "") {
        store.edit {
            it[sound] = mode.name
            it[uri] = soundUri
        }
    }

    suspend fun setAlarmSound(mode: AlarmSound, soundUri: String = "") {
        store.edit {
            it[alarmSound] = mode.name
            it[alarmUri] = soundUri
        }
    }

    suspend fun setBypassDnd(value: Boolean) {
        store.edit { it[dnd] = value }
    }

    suspend fun setAutoDeleteCompleted(value: AutoDeleteCompleted) {
        store.edit { it[autoDelete] = value.name }
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
