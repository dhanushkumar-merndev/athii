package com.oki.feature.tutorial

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oki.AppContainer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel that owns a [TutorialController] and preserves the current step index across
 * configuration changes and process death via [SavedStateHandle].
 */
class TutorialViewModel(private val c: AppContainer, private val savedState: SavedStateHandle) :
    ViewModel() {

    val registry: TutorialTargetRegistry = c.tutorialTargets

    val controller =
        TutorialController(prefs = c.tutorialPrefs, registry = registry, scope = viewModelScope)

    val isTourActive: StateFlow<Boolean> = controller.isTourActive
    val currentStepIndex: StateFlow<Int> = controller.currentStepIndex
    val currentStep: StateFlow<TutorialStep?> = controller.currentStep
    val pendingNavigation: StateFlow<NavigationRequest?> = controller.pendingNavigation
    val showSkipConfirm: StateFlow<Boolean> = controller.showSkipConfirm
    val totalSteps: Int
        get() = controller.totalSteps

    init {
        // Restore step index after process death.
        val restored = savedState.get<Int>(KEY_STEP_INDEX)
        if (restored != null) controller.restoreIndex(restored)
        // Persist step index on every change.
        viewModelScope.launch {
            controller.currentStepIndex.collect { savedState[KEY_STEP_INDEX] = it }
        }
    }

    fun startTourIfNeeded() = controller.startTourIfNeeded()

    fun startTour() = controller.startTour()

    fun replayTour() = controller.replayTour()

    fun next() = controller.next()

    fun previous() = controller.previous()

    fun requestSkip() = controller.requestSkip()

    fun cancelSkip() = controller.cancelSkip()

    fun confirmSkip() = controller.confirmSkip()

    fun finish() = controller.finish()

    fun consumeNavigation() = controller.consumeNavigation()

    fun isCurrentTargetAvailable() = controller.isCurrentTargetAvailable()

    fun skipUnavailableTarget() = controller.skipUnavailableTarget()

    companion object {
        private const val KEY_STEP_INDEX = "tutorial_step_index"
    }
}
