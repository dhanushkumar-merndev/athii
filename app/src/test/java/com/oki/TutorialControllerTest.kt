package com.oki

import androidx.compose.ui.geometry.Rect
import com.oki.feature.tutorial.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TutorialControllerTest {
    private class CompletionStore : TutorialCompletionStore {
        override val hasCompletedAppTour = MutableStateFlow(false)

        override suspend fun setCompleted(value: Boolean) {
            hasCompletedAppTour.value = value
        }
    }

    @Test
    fun emptyDataSkipsRecordStepsInBothDirectionsAndUsesActualCount() = runTest {
        val controller =
            TutorialController(CompletionStore(), TutorialTargetRegistry(), backgroundScope)
        controller.updateDataAvailability(hasTasks = false, hasDoctors = false)
        controller.startTour()
        val visited = mutableListOf<String>()
        while (controller.currentStep.value?.isFinal == false) {
            visited += controller.currentStep.value!!.id
            assertEquals(visited.lastIndex, controller.visibleStepIndex.value)
            controller.next()
        }
        visited += controller.currentStep.value!!.id
        assertEquals(
            listOf(
                "welcome",
                "add_doctor",
                "reminder",
                "notifications",
                "export_report",
                "settings",
                "replay",
                "finish",
            ),
            visited,
        )
        assertEquals(visited.size, controller.totalSteps.value)
        controller.previous()
        assertEquals("replay", controller.currentStep.value!!.id)
        repeat(5) { controller.previous() }
        assertEquals("add_doctor", controller.currentStep.value!!.id)
    }

    @Test
    fun taskAndDoctorAvailabilityAreIndependent() = runTest {
        val controller =
            TutorialController(CompletionStore(), TutorialTargetRegistry(), backgroundScope)
        controller.updateDataAvailability(hasTasks = true, hasDoctors = false)
        assertEquals(14, controller.totalSteps.value)
        controller.startTour()
        controller.next()
        assertEquals("dashboard", controller.currentStep.value!!.id)
        controller.next()
        assertEquals("task_list", controller.currentStep.value!!.id)
        // Losing the last task mid-tour moves past every task step, not onto an empty list.
        controller.updateDataAvailability(hasTasks = false, hasDoctors = true)
        assertEquals("add_doctor", controller.currentStep.value!!.id)
        assertEquals(14, controller.totalSteps.value)
    }

    @Test
    fun disappearingDoctorCannotLeaveTourOnAnUnavailableRecord() = runTest {
        val controller =
            TutorialController(CompletionStore(), TutorialTargetRegistry(), backgroundScope)
        controller.updateDataAvailability(hasTasks = true, hasDoctors = true)
        controller.startTour()
        repeat(9) { controller.next() }
        assertEquals("doctor_card", controller.currentStep.value!!.id)
        controller.updateDataAvailability(hasTasks = true, hasDoctors = false)
        assertEquals("reminder", controller.currentStep.value!!.id)
        controller.previous()
        assertEquals("add_doctor", controller.currentStep.value!!.id)
    }

    @Test
    fun missingTargetsStaySkippedWhenGoingBackAndReplayRetriesThem() = runTest {
        val controller =
            TutorialController(CompletionStore(), TutorialTargetRegistry(), backgroundScope)
        controller.updateDataAvailability(hasTasks = true, hasDoctors = true)
        controller.startTour()
        controller.next()
        controller.skipUnavailableTarget()
        assertEquals("task_list", controller.currentStep.value!!.id)
        assertEquals(19, controller.totalSteps.value)
        controller.previous()
        assertEquals("welcome", controller.currentStep.value!!.id)
        controller.replayTour()
        controller.next()
        assertEquals("dashboard", controller.currentStep.value!!.id)
        assertEquals(20, controller.totalSteps.value)
    }

    @Test
    fun timeoutWhileGoingBackContinuesBackInsteadOfBouncingForward() = runTest {
        val controller =
            TutorialController(CompletionStore(), TutorialTargetRegistry(), backgroundScope)
        controller.updateDataAvailability(hasTasks = true, hasDoctors = true)
        controller.startTour()
        repeat(9) { controller.next() }
        controller.previous()
        assertEquals("doctor_list", controller.currentStep.value!!.id)
        controller.skipUnavailableTarget()
        assertEquals("add_doctor", controller.currentStep.value!!.id)
    }

    @Test
    fun staleNavigationAcknowledgementDoesNotEraseTheNextStepRequest() = runTest {
        val controller =
            TutorialController(CompletionStore(), TutorialTargetRegistry(), backgroundScope)
        controller.updateDataAvailability(hasTasks = true, hasDoctors = true)
        controller.startTour()
        val first = controller.pendingNavigation.value!!
        controller.next()
        val next = controller.pendingNavigation.value!!
        assertEquals(first.route, next.route)
        assertNotEquals(first.stepId, next.stepId)
        controller.consumeNavigation(first)
        assertEquals(next, controller.pendingNavigation.value)
        controller.consumeNavigation(next)
        assertNull(controller.pendingNavigation.value)
    }

    @Test
    fun skipConfirmationPausesProgressAndCompletionClearsPendingNavigation() = runTest {
        val prefs = CompletionStore()
        val controller = TutorialController(prefs, TutorialTargetRegistry(), backgroundScope)
        controller.startTour()
        controller.requestSkip()
        controller.next()
        controller.skipUnavailableTarget()
        assertEquals("welcome", controller.currentStep.value!!.id)
        controller.cancelSkip()
        controller.next()
        assertEquals("add_doctor", controller.currentStep.value!!.id)
        controller.confirmSkip()
        runCurrent()
        assertFalse(controller.isTourActive.value)
        assertNull(controller.currentStep.value)
        assertNull(controller.pendingNavigation.value)
        assertTrue(prefs.hasCompletedAppTour.value)
        controller.next()
        assertNull(controller.currentStep.value)
        controller.startTourIfNeeded()
        runCurrent()
        assertFalse(controller.isTourActive.value)
    }

    @Test
    fun restoringAnActiveTourKeepsItsPositionWhenFirstLaunchIsChecked() = runTest {
        val controller =
            TutorialController(CompletionStore(), TutorialTargetRegistry(), backgroundScope)
        controller.updateDataAvailability(hasTasks = true, hasDoctors = true)
        controller.restoreIndex(11)
        controller.startTourIfNeeded()
        runCurrent()
        assertTrue(controller.isTourActive.value)
        assertEquals("edit_doctor", controller.currentStep.value!!.id)
        assertEquals("doctor_detail", controller.pendingNavigation.value!!.route)
    }

    @Test
    fun disposingReplacedTargetDoesNotUnregisterItsNewOwner() {
        val registry = TutorialTargetRegistry()
        val old = Any()
        val replacement = Any()
        registry.register("doctor_card", Rect(0f, 0f, 20f, 20f), old)
        val replacementBounds = Rect(10f, 10f, 40f, 40f)
        registry.register("doctor_card", replacementBounds, replacement)
        registry.unregister("doctor_card", old)
        assertEquals(replacementBounds, registry.boundsFor("doctor_card"))
        registry.unregister("doctor_card", replacement)
        assertNull(registry.boundsFor("doctor_card"))
    }
}
