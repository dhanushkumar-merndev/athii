package com.oki.feature.tutorial

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.tutorialStore by preferencesDataStore("oki_tutorial")

/**
 * Persists whether the user has completed (or skipped) the guided app tour. Uses a dedicated
 * DataStore so clearing app preferences in Settings does not accidentally re-trigger the tour.
 */
class TutorialPreferences(context: Context) {
    private val store = context.tutorialStore
    private val completed = booleanPreferencesKey("has_completed_app_tour")

    /** Emits `true` once the user has finished or skipped the tour. */
    val hasCompletedAppTour: Flow<Boolean> =
        store.data
            .catch {
                if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
                else throw it
            }
            .map { it[completed] ?: false }

    /** Mark the tour as completed (or reset for replay debugging). */
    suspend fun setCompleted(value: Boolean) {
        store.edit { it[completed] = value }
    }
}
