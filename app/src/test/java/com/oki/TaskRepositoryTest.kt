package com.oki

import com.oki.core.storage.*
import com.oki.feature.tasks.*
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
    }

    override fun cancel(id: String) {
        calls += "cancel:$id"
        alarms.remove(id)
    }

    override fun dismiss(id: String) {
        calls += "dismiss:$id"
    }
}

class TaskRepositoryTest {
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
        assertEquals(task.dueAt + 60_000 - 300_000, scheduler.alarms[task.id])
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
}
