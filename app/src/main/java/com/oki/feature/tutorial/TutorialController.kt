package com.oki.feature.tutorial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Describes which screen the tour needs the host to navigate to before the next spotlight can
 * appear. The host ([OkiApp]) translates these into actual NavController / pager actions.
 */
data class NavigationRequest(
    /** Logical route: "home/tasks", "home/doctors", "settings", "doctor_detail". */
    val route: String
)

/**
 * Central state-machine that drives the guided app tour.
 *
 * Navigation is **not** performed here — the controller emits [NavigationRequest] objects via
 * [pendingNavigation] and the host composable executes them, keeping the controller
 * navigation-framework-agnostic.
 */
class TutorialController(
    private val prefs: TutorialPreferences,
    private val registry: TutorialTargetRegistry,
    private val scope: CoroutineScope,
) {
    private val steps = ALL_TUTORIAL_STEPS

    private val _isTourActive = MutableStateFlow(false)
    val isTourActive: StateFlow<Boolean> = _isTourActive.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    val totalSteps: Int
        get() = steps.size

    val currentStep: StateFlow<TutorialStep?> =
        combine(_currentStepIndex, _isTourActive) { idx, active ->
                if (active) steps.getOrNull(idx) else null
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val _pendingNavigation = MutableStateFlow<NavigationRequest?>(null)
    val pendingNavigation: StateFlow<NavigationRequest?> = _pendingNavigation.asStateFlow()

    /** True while the overlay should show the skip-confirmation dialog. */
    private val _showSkipConfirm = MutableStateFlow(false)
    val showSkipConfirm: StateFlow<Boolean> = _showSkipConfirm.asStateFlow()

    // ---- lifecycle ----

    /** Automatically start the tour on first launch if not yet completed. */
    fun startTourIfNeeded() {
        scope.launch {
            prefs.hasCompletedAppTour.first().let { completed -> if (!completed) startTour() }
        }
    }

    /** Begin the tour from step 0. */
    fun startTour() {
        _currentStepIndex.value = 0
        _isTourActive.value = true
        _showSkipConfirm.value = false
        emitNavigationForStep(0)
    }

    /** Replay from Settings — works even when already completed. */
    fun replayTour() {
        _currentStepIndex.value = 0
        _isTourActive.value = true
        _showSkipConfirm.value = false
        emitNavigationForStep(0)
    }

    /** Restore the index after process death (called from ViewModel). */
    fun restoreIndex(index: Int) {
        if (_isTourActive.value && index in steps.indices) {
            _currentStepIndex.value = index
        }
    }

    // ---- navigation within tour ----

    fun next() {
        val nextIndex = findNextValidIndex(_currentStepIndex.value + 1, forward = true)
        if (nextIndex != null) {
            _currentStepIndex.value = nextIndex
            emitNavigationForStep(nextIndex)
        } else {
            finish()
        }
    }

    fun previous() {
        val prevIndex = findNextValidIndex(_currentStepIndex.value - 1, forward = false)
        if (prevIndex != null) {
            _currentStepIndex.value = prevIndex
            emitNavigationForStep(prevIndex)
        }
        // If no previous exists, stay on current step.
    }

    fun requestSkip() {
        _showSkipConfirm.value = true
    }

    fun cancelSkip() {
        _showSkipConfirm.value = false
    }

    fun confirmSkip() {
        _showSkipConfirm.value = false
        markCompleteAndClose()
    }

    fun finish() {
        markCompleteAndClose()
    }

    /** Acknowledge that the host has performed the navigation. */
    fun consumeNavigation() {
        _pendingNavigation.value = null
    }

    /**
     * Check whether the current step's target is available. Steps with an empty targetKey (welcome
     * / finish overlays) are always available.
     */
    fun isCurrentTargetAvailable(): Boolean {
        val step = steps.getOrNull(_currentStepIndex.value) ?: return false
        if (step.targetKey.isEmpty()) return true
        return registry.boundsFor(step.targetKey) != null
    }

    /**
     * Called by the overlay when it detects the target is unavailable after a brief wait.
     * Auto-advance to the next valid step.
     */
    fun skipUnavailableTarget() {
        next()
    }

    // ---- internals ----

    private fun markCompleteAndClose() {
        _isTourActive.value = false
        _currentStepIndex.value = 0
        scope.launch { prefs.setCompleted(true) }
    }

    /**
     * Starting from [fromIndex], walk in [forward] direction and return the first step whose target
     * is available or whose targetKey is empty (full-screen overlays are always valid). Stops at
     * list boundaries.
     */
    private fun findNextValidIndex(fromIndex: Int, forward: Boolean): Int? {
        val range = if (forward) fromIndex until steps.size else fromIndex downTo 0
        for (i in range) {
            val step = steps[i]
            // Full-screen overlays are always valid.
            if (step.targetKey.isEmpty()) return i
            // For targeted steps, we don't require the target to already be measured here because
            // the screen might not have navigated yet. The overlay will handle waiting.
            return i
        }
        return null
    }

    private fun emitNavigationForStep(index: Int) {
        val step = steps.getOrNull(index) ?: return
        _pendingNavigation.value = NavigationRequest(step.screenRoute)
    }
}
