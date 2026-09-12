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

    /**
     * Set by the host. Steps it rejects, such as doctor-record steps with no doctors saved, are
     * passed over without being shown, instead of flashing up and timing out one after another.
     */
    var isStepAvailable: (TutorialStep) -> Boolean = { true }
    private var movingForward = true

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
        movingForward = true
        _currentStepIndex.value = 0
        _isTourActive.value = true
        _showSkipConfirm.value = false
        emitNavigationForStep(0)
    }

    /** Replay from Settings — works even when already completed. */
    fun replayTour() {
        movingForward = true
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
        movingForward = true
        val nextIndex = findNextValidIndex(_currentStepIndex.value + 1, forward = true)
        if (nextIndex != null) {
            _currentStepIndex.value = nextIndex
            emitNavigationForStep(nextIndex)
        } else {
            finish()
        }
    }

    fun previous() {
        movingForward = false
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
     * Called by the overlay when a target never appeared. Continues in the direction the user was
     * travelling, so pressing Back onto a missing target does not bounce straight forward again.
     */
    fun skipUnavailableTarget() {
        val current = _currentStepIndex.value
        val index =
            if (movingForward) findNextValidIndex(current + 1, forward = true)
            else
                findNextValidIndex(current - 1, forward = false)
                    ?: findNextValidIndex(current + 1, forward = true)
        if (index == null) {
            finish()
            return
        }
        _currentStepIndex.value = index
        emitNavigationForStep(index)
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
        // Targets need not be measured yet (the screen may still be navigating; the overlay waits
        // for them). Only steps the host says cannot apply are passed over, and never shown.
        return range.firstOrNull { it in steps.indices && isStepAvailable(steps[it]) }
    }

    private fun emitNavigationForStep(index: Int) {
        val step = steps.getOrNull(index) ?: return
        _pendingNavigation.value = NavigationRequest(step.screenRoute)
    }
}
