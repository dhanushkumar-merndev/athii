package com.oki

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.oki.core.storage.*
import com.oki.core.ui.OkiTheme
import com.oki.feature.tasks.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TaskEditingFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun futureStartWithExpiredLeadSavesAtStartWithoutPastWarning() {
        val c = ApplicationProvider.getApplicationContext<OkiApplication>().container
        val store = ViewModelStore()
        val task =
            Task(
                title = "Reschedule fixture",
                dueAt = System.currentTimeMillis() - 60_000,
                reminderEnabled = false,
            )
        runBlocking { c.tasks.save(task) }
        try {
            lateinit var vm: TaskEditorViewModel
            rule.runOnIdle {
                vm = TaskEditorViewModel(c, SavedStateHandle(), task.id, null)
                store.put("editor", vm)
            }
            rule.waitUntil(5000) { vm.form.value != null }
            val due = ZonedDateTime.now().plusMinutes(3).withSecond(0).withNano(0)
            rule.runOnIdle {
                vm.change(
                    vm.form.value!!.copy(
                        date = due.toLocalDate().toString(),
                        time = due.format(DateTimeFormatter.ofPattern("HH:mm")),
                        reminder = true,
                        offset = "5",
                    )
                )
            }
            rule.waitForIdle()
            rule.runOnIdle {
                assertFalse(vm.reminderInPast())
                vm.save()
            }
            rule.waitUntil(5000) { vm.saved.value || vm.error.value != null }
            assertNull(vm.error.value)
            val saved = runBlocking { c.tasks.get(task.id)!! }
            assertEquals(due.toInstant().toEpochMilli(), saved.scheduledReminderAt)
            assertEquals(0, saved.reminderOffsetMinutes)
        } finally {
            rule.runOnIdle { store.clear() }
            runBlocking { c.tasks.delete(task.id) }
        }
    }

    @Test
    fun completedTaskShowsOriginalDetailsWithoutEditorControls() {
        val c = ApplicationProvider.getApplicationContext<OkiApplication>().container
        val store = ViewModelStore()
        val task =
            Task(
                title = "Completed fixture",
                notes = "Original notes",
                dueAt = System.currentTimeMillis() - 60_000,
                isCompleted = true,
                completedAt = System.currentTimeMillis(),
                reminderEnabled = false,
            )
        runBlocking { c.tasks.save(task) }
        try {
            lateinit var vm: TaskEditorViewModel
            rule.runOnIdle {
                vm = TaskEditorViewModel(c, SavedStateHandle(), task.id, null)
                store.put("editor", vm)
            }
            rule.waitUntil(5000) { vm.form.value != null }
            rule.setContent {
                OkiTheme(Appearance.DARK) { TaskEditorScreen(vm, {}, { true }, { true }, {}) }
            }
            rule.onNodeWithText("Completed fixture").assertIsDisplayed()
            rule.onNodeWithText("Original notes").assertExists()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithText("Save Task").assertDoesNotExist()
            rule.onNodeWithText("Task alert").assertDoesNotExist()
        } finally {
            rule.runOnIdle { store.clear() }
            runBlocking { c.tasks.delete(task.id) }
        }
    }
}
