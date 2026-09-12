package com.oki

import com.oki.core.ai.aiJson
import com.oki.core.storage.*
import com.oki.feature.tasks.*
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeTaskDao : TaskDao {
    val data = MutableStateFlow<List<Task>>(emptyList())

    override fun observe() = data

    override suspend fun get(id: String) = data.value.find { it.id == id }

    override suspend fun all() = data.value

    override suspend fun put(task: Task) {
        data.value = data.value.filterNot { it.id == task.id } + task
    }

    override suspend fun delete(id: String) {
        data.value = data.value.filterNot { it.id == id }
    }

    override suspend fun clear() {
        data.value = emptyList()
    }

    override suspend fun activeCountExcluding(excludeId: String) =
        data.value.count { !it.isCompleted && it.id != excludeId }

    override suspend fun deleteCompleted(): Int {
        val remaining = data.value.filterNot { it.isCompleted }
        return (data.value.size - remaining.size).also { data.value = remaining }
    }

    override suspend fun deleteCompletedBefore(cutoff: Long): Int {
        val remaining =
            data.value.filterNot {
                it.isCompleted && it.completedAt != null && it.completedAt!! < cutoff
            }
        return (data.value.size - remaining.size).also { data.value = remaining }
    }

    override suspend fun search(
        query: String,
        fromTime: Long?,
        toTime: Long?,
        completed: Boolean?,
        limit: Int,
    ) =
        data.value
            .filter {
                it.title.contains(query, true) &&
                    (fromTime == null || it.dueAt >= fromTime) &&
                    (toTime == null || it.dueAt < toTime) &&
                    (completed == null || it.isCompleted == completed)
            }
            .take(limit)
}

class FakeScheduler : ReminderScheduler {
    val calls = mutableListOf<String>()
    val alarms = mutableMapOf<String, Long>()

    override fun schedule(task: Task) {
        calls += "schedule:${task.id}"
        task.scheduledReminderAt?.let { alarms[task.id] = it }
        task.scheduledEndReminderAt?.let { alarms["${task.id}:end"] = it }
    }

    override fun cancel(id: String) {
        calls += "cancel:$id"
        alarms.remove(id)
        alarms.remove("$id:end")
    }

    override fun dismiss(id: String) {
        calls += "dismiss:$id"
    }
}

class TaskRepositoryTest {
    @Test
    fun alarmRingsAtTheChosenLeadTime() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val due = System.currentTimeMillis() + 3_600_000
        val alarm =
            aiJson.decodeFromString<Task>(
                """{"id":"alarm-mode","title":"Read","dueAt":$due,"reminderOffsetMinutes":5,"alertMode":"ALARM"}"""
            )
        repo.save(alarm)
        assertEquals(due - 300_000, scheduler.alarms[alarm.id])

        val atStart = alarm.copy(id = "alarm-at-start", reminderOffsetMinutes = 0)
        repo.save(atStart)
        assertEquals(due, scheduler.alarms[atStart.id])
    }

    @Test
    fun activeTaskCapBlocksNewButAllowsEditingAndCompleting() = runTest {
        val dao = FakeTaskDao()
        val repo = TaskRepository(dao, FakeScheduler())
        val due = System.currentTimeMillis() + 3_600_000
        dao.data.value =
            (1..TaskRepository.MAX_ACTIVE_TASKS).map {
                Task(id = "t$it", title = "Task $it", dueAt = due)
            }

        // A brand-new active task is refused at the cap.
        val overflow = runCatching {
            repo.save(Task(id = "new", title = "One too many", dueAt = due))
        }
        assertTrue(overflow.isFailure)

        // Editing one of the existing tasks must still work.
        repo.save(dao.data.value.first().copy(title = "Renamed"))
        assertEquals("Renamed", dao.get("t1")!!.title)

        // Completing frees a slot, so the next new task saves.
        repo.complete("t1", true)
        repo.save(Task(id = "new", title = "Now it fits", dueAt = due))
        assertEquals("Now it fits", dao.get("new")!!.title)
    }

    @Test
    fun restoreArmsOnlyTheSoonestRemindersToStayUnderTheAlarmBudget() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val now = System.currentTimeMillis()
        val count = TaskRepository.ARMED_REMINDER_LIMIT + 120
        // Task i is due i minutes out, so the soonest ones are the low indices.
        dao.data.value =
            (1..count).map {
                Task(
                    id = "t$it",
                    title = "Task $it",
                    dueAt = now + it * 60_000L,
                    scheduledReminderAt = now + it * 60_000L,
                )
            }

        repo.restore()

        val armed = scheduler.alarms.keys.filterNot { it.endsWith(":end") }
        assertEquals(TaskRepository.ARMED_REMINDER_LIMIT, armed.size)
        assertTrue("soonest task must be armed", "t1" in armed)
        assertTrue("furthest task must be deferred", "t$count" !in armed)
    }

    @Test
    fun autoDeleteRemovesOnlyCompletedTasksPastRetention() = runTest {
        val dao = FakeTaskDao()
        val repo = TaskRepository(dao, FakeScheduler())
        val now = System.currentTimeMillis()
        val due = now + 3_600_000
        dao.data.value =
            listOf(
                Task(
                    id = "old",
                    title = "Old",
                    dueAt = due,
                    isCompleted = true,
                    completedAt = now - 8 * 86_400_000L,
                ),
                Task(
                    id = "recent",
                    title = "Recent",
                    dueAt = due,
                    isCompleted = true,
                    completedAt = now - 2 * 86_400_000L,
                ),
                Task(id = "open", title = "Open", dueAt = due),
            )
        assertEquals(0, repo.purgeCompleted(null, now))
        assertEquals(3, dao.data.value.size)
        assertEquals(1, repo.purgeCompleted(7, now))
        assertEquals(setOf("recent", "open"), dao.data.value.map { it.id }.toSet())
    }

    @Test
    fun switchingModesReplacesOneStartAndSnoozeKeepsAlarmMode() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = timedTask()
        repo.save(task)
        scheduler.calls.clear()
        repo.save(task.copy(alertMode = TaskAlertMode.ALARM))
        assertEquals(
            listOf("cancel:${task.id}", "dismiss:${task.id}", "schedule:${task.id}"),
            scheduler.calls,
        )
        assertEquals(setOf(task.id, "${task.id}:end"), scheduler.alarms.keys)
        repo.snooze(task.id)
        val snoozed = dao.get(task.id)!!
        assertEquals(TaskAlertMode.ALARM, snoozed.alertMode)
        assertEquals(task.dueAt, snoozed.dueAt)
        assertEquals(task.dueAt + 3_600_000, snoozed.scheduledEndReminderAt)
        repo.complete(task.id, true)
        assertTrue(scheduler.alarms.isEmpty())
    }

    @Test
    fun unavailableAlarmAccessDoesNotOverwriteExistingTask() = runTest {
        val dao = FakeTaskDao()
        val scheduler =
            object : ReminderScheduler {
                override fun validate(task: Task) {
                    require(task.alertMode != TaskAlertMode.ALARM)
                }

                override fun schedule(task: Task) {}

                override fun cancel(id: String) {
                    fail("Must validate before canceling an existing alert")
                }

                override fun dismiss(id: String) {}
            }
        val original = timedTask()
        dao.put(original)
        assertTrue(
            runCatching {
                    TaskRepository(dao, scheduler)
                        .save(original.copy(alertMode = TaskAlertMode.ALARM))
                }
                .isFailure
        )
        assertEquals(original, dao.get(original.id))
    }

    @Test
    fun editingCancelsThenReplacesSameIdentity() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = Task(title = "Call", dueAt = System.currentTimeMillis() + 3_600_000)
        repo.save(task)
        scheduler.calls.clear()
        repo.save(task.copy(dueAt = task.dueAt + 60_000))
        assertEquals(
            listOf("cancel:${task.id}", "dismiss:${task.id}", "schedule:${task.id}"),
            scheduler.calls,
        )
        assertEquals(1, scheduler.alarms.size)
        assertEquals(1, dao.data.value.size)
        assertEquals(task.dueAt + 60_000, scheduler.alarms[task.id])
    }

    @Test
    fun deleteCancelsOnlySelectedAlarm() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val a = Task(title = "A", dueAt = System.currentTimeMillis() + 3_600_000)
        val b = a.copy(id = "b", title = "B")
        repo.save(a)
        repo.save(b)
        repo.delete(a.id)
        assertNull(dao.get(a.id))
        assertNotNull(dao.get(b.id))
        assertEquals(setOf("b"), scheduler.alarms.keys)
    }

    @Test
    fun completingCancelsAndPreservesTask() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = Task(title = "A", dueAt = System.currentTimeMillis() + 3_600_000)
        repo.save(task)
        repo.complete(task.id, true)
        assertTrue(dao.get(task.id)!!.isCompleted)
        assertTrue(scheduler.alarms.isEmpty())
    }

    @Test
    fun pastReminderRequiresExplicitChoice() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = Task(title = "Past", dueAt = 1)
        assertTrue(runCatching { repo.save(task) }.isFailure)
        assertTrue(dao.data.value.isEmpty())
        repo.save(task, notifyNow = true)
        assertTrue(dao.get(task.id)!!.scheduledReminderAt!! > System.currentTimeMillis())
    }

    @Test
    fun snoozeDoesNotChangeDueTime() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = Task(title = "A", dueAt = System.currentTimeMillis() + 3_600_000)
        repo.save(task)
        repo.snooze(task.id)
        assertEquals(task.dueAt, dao.get(task.id)!!.dueAt)
    }

    @Test
    fun staleAlarmDoesNotPublishAfterEdit() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = Task(title = "A", dueAt = 600_000, scheduledReminderAt = 1000)
        dao.put(task)
        var published = false
        repo.deliver(task.id, 999) { published = true }
        assertFalse(published)
        repo.deliver(task.id, 1000) { published = true }
        assertTrue(published)
        assertNull(dao.get(task.id)!!.scheduledReminderAt)
    }

    private fun timedTask() =
        Task(
            id = "timed-task",
            title = "Read",
            dueAt = TimeRules.parseDue(LocalDate.now().plusDays(1).toString(), "10:00"),
            endTime = "11:00",
        )

    @Test
    fun startAndEndSchedulesAreIndependentAndCancelledOnCompletionOrDeletion() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = timedTask()
        repo.save(task)
        assertEquals(task.dueAt, scheduler.alarms[task.id])
        assertEquals(task.dueAt + 3_600_000, scheduler.alarms["${task.id}:end"])
        repo.complete(task.id, true)
        assertTrue(scheduler.alarms.isEmpty())
        assertNull(dao.get(task.id)!!.scheduledEndReminderAt)
        repo.complete(task.id, false)
        assertEquals(2, scheduler.alarms.size)
        repo.delete(task.id)
        assertTrue(scheduler.alarms.isEmpty())
    }

    @Test
    fun restoreKeepsEndAfterStartAlreadyDeliveredAndDoesNotReviveStart() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = timedTask().copy(scheduledEndReminderAt = timedTask().dueAt + 3_600_000)
        dao.put(task)
        repo.restore()
        repo.restore()
        assertEquals(mapOf("${task.id}:end" to task.scheduledEndReminderAt), scheduler.alarms)
    }

    @Test
    fun endDeliveryChecksItsOwnTimestampAndOnlyPublishesOnce() = runTest {
        val dao = FakeTaskDao()
        val repo = TaskRepository(dao, FakeScheduler())
        val task =
            Task(
                title = "Finished",
                dueAt = 1,
                scheduledReminderAt = 1000,
                scheduledEndReminderAt = 2000,
            )
        dao.put(task)
        var published = 0
        repo.deliver(task.id, 1000, TaskNotificationKind.END) { published++ }
        assertEquals(0, published)
        repo.deliver(task.id, 2000, TaskNotificationKind.END) { published++ }
        repo.deliver(task.id, 2000, TaskNotificationKind.END) { published++ }
        assertEquals(1, published)
        assertEquals(1000L, dao.get(task.id)!!.scheduledReminderAt)
        assertNull(dao.get(task.id)!!.scheduledEndReminderAt)
    }

    @Test
    fun invalidEndTimesNeverPersistOrSchedule() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        listOf("09:00", "10:00", "25:99", "not a time").forEach { end ->
            assertTrue(runCatching { repo.save(timedTask().copy(endTime = end)) }.isFailure)
        }
        assertTrue(dao.data.value.isEmpty())
        assertTrue(scheduler.alarms.isEmpty())
    }

    @Test
    fun disablingNotificationsAndRemovingEndCancelBothPreviousSchedules() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = timedTask()
        repo.save(task)
        repo.save(task.copy(endTime = null))
        assertEquals(setOf(task.id), scheduler.alarms.keys)
        repo.save(task.copy(reminderEnabled = false))
        assertTrue(scheduler.alarms.isEmpty())
    }

    @Test
    fun legacyOffsetsArePreservedByRestore() = runTest {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val repo = TaskRepository(dao, scheduler)
        val task = timedTask().copy(reminderOffsetMinutes = 5, endTime = null)
        repo.save(task)
        repo.restore()
        assertEquals(task.dueAt - 300_000, scheduler.alarms[task.id])
    }
}
