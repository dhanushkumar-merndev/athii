package com.oki.feature.tutorial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** A step-specific request; consecutive steps on the same screen still get acknowledged. */
data class NavigationRequest(val route: String, val stepId: String)

/** Navigation-independent state machine for the guided tour. */
class TutorialController(
    private val prefs: TutorialCompletionStore,
    private val registry: TutorialTargetRegistry,
    private val scope: CoroutineScope,
) {
    private val steps = ALL_TUTORIAL_STEPS
    private var hasTasks = false
    private var hasDoctors = false
    private var movingForward = true
    private var startJob: Job? = null
    private val unavailableTargets = mutableSetOf<String>()

    private val _isTourActive = MutableStateFlow(false)
    val isTourActive: StateFlow<Boolean> = _isTourActive.asStateFlow()
    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()
    private val _currentStep = MutableStateFlow<TutorialStep?>(null)
    val currentStep: StateFlow<TutorialStep?> = _currentStep.asStateFlow()
    private val _visibleStepIndex = MutableStateFlow(0)
    val visibleStepIndex: StateFlow<Int> = _visibleStepIndex.asStateFlow()
    private val _totalSteps = MutableStateFlow(availableIndices().size)
    val totalSteps: StateFlow<Int> = _totalSteps.asStateFlow()
    private val _pendingNavigation = MutableStateFlow<NavigationRequest?>(null)
    val pendingNavigation: StateFlow<NavigationRequest?> = _pendingNavigation.asStateFlow()
    private val _showSkipConfirm = MutableStateFlow(false)
    val showSkipConfirm: StateFlow<Boolean> = _showSkipConfirm.asStateFlow()

    /** Refresh when persisted data changes, including deletion while a tour is active. */
    fun updateDataAvailability(hasTasks: Boolean, hasDoctors: Boolean) {
        this.hasTasks = hasTasks
        this.hasDoctors = hasDoctors
        refreshProgress()
        if (_isTourActive.value && !isAvailable(steps[_currentStepIndex.value])) {
            skipUnavailableTarget()
        }
    }

    fun startTourIfNeeded() {
        if (_isTourActive.value || startJob?.isActive == true) return
        startJob =
            scope.launch {
                if (!prefs.hasCompletedAppTour.first() && !_isTourActive.value) startTour()
            }
    }

    fun startTour() {
        unavailableTargets.clear()
        movingForward = true
        _showSkipConfirm.value = false
        _isTourActive.value = true
        moveTo(0)
    }

    fun replayTour() = startTour()

    /** Restore an active tour before first-launch detection runs. */
    fun restoreIndex(index: Int) {
        if (index !in steps.indices) return
        _isTourActive.value = true
        moveTo(index)
    }

    fun next() {
        if (!_isTourActive.value || _showSkipConfirm.value) return
        movingForward = true
        val index = findNextValidIndex(_currentStepIndex.value + 1, true)
        if (index == null) finish() else moveTo(index)
    }

    fun previous() {
        if (!_isTourActive.value || _showSkipConfirm.value) return
        movingForward = false
        findNextValidIndex(_currentStepIndex.value - 1, false)?.let(::moveTo)
    }

    fun requestSkip() {
        if (_isTourActive.value) _showSkipConfirm.value = true
    }

    fun cancelSkip() {
        _showSkipConfirm.value = false
    }

    fun confirmSkip() = finish()

    fun finish() {
        if (!_isTourActive.value) return
        _isTourActive.value = false
        _currentStep.value = null
        _pendingNavigation.value = null
        _showSkipConfirm.value = false
        _currentStepIndex.value = 0
        scope.launch { prefs.setCompleted(true) }
    }

    /** Ignore late acknowledgements from a navigation that a newer step already replaced. */
    fun consumeNavigation(request: NavigationRequest) {
        if (_pendingNavigation.value == request) _pendingNavigation.value = null
    }

    fun isCurrentTargetAvailable(): Boolean {
        val step = _currentStep.value ?: return false
        return step.targetKey.isEmpty() || registry.boundsFor(step.targetKey) != null
    }

    /** Missing targets are omitted for the rest of this run, including when going Back. */
    fun skipUnavailableTarget() {
        if (!_isTourActive.value || _showSkipConfirm.value) return
        val current = _currentStepIndex.value
        unavailableTargets += steps[current].id
        refreshProgress()
        val index =
            if (movingForward) findNextValidIndex(current + 1, true)
            else findNextValidIndex(current - 1, false) ?: findNextValidIndex(current + 1, true)
        if (index == null) finish() else moveTo(index)
    }

    private fun isAvailable(step: TutorialStep): Boolean =
        (!step.needsDoctor || hasDoctors) &&
            (!step.needsTask || hasTasks) &&
            step.id !in unavailableTargets

    private fun availableIndices() = steps.indices.filter { isAvailable(steps[it]) }

    private fun refreshProgress() {
        val available = availableIndices()
        _totalSteps.value = available.size
        _visibleStepIndex.value = available.indexOf(_currentStepIndex.value).coerceAtLeast(0)
    }

    private fun findNextValidIndex(fromIndex: Int, forward: Boolean): Int? {
        val range = if (forward) fromIndex until steps.size else fromIndex downTo 0
        return range.firstOrNull { it in steps.indices && isAvailable(steps[it]) }
    }

    private fun moveTo(index: Int) {
        val step = steps[index]
        _currentStepIndex.value = index
        _currentStep.value = step
        refreshProgress()
        _pendingNavigation.value = NavigationRequest(step.screenRoute, step.id)
    }
}
