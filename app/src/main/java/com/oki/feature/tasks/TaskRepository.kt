package com.oki.feature.tasks

import com.oki.core.storage.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ReminderScheduler {
    fun schedule(task: Task)

    fun cancel(id: String)

    fun dismiss(id: String)
}

class TaskRepository(private val dao: TaskDao, private val scheduler: ReminderScheduler) {
    private val mutex = Mutex()
    val tasks = dao.observe()

    suspend fun get(id: String) = dao.get(id)

    suspend fun search(
        query: String = "",
        from: Long? = null,
        to: Long? = null,
        completed: Boolean? = null,
        limit: Int = 20,
    ) = dao.search(query, from, to, completed, limit.coerceIn(1, 30))

    suspend fun save(task: Task, notifyNow: Boolean = false) =
        mutex.withLock {
            require(task.title.isNotBlank()) { "Enter a task title." }
            val now = System.currentTimeMillis()
            val at =
                if (task.reminderEnabled && !task.isCompleted) {
                    if (notifyNow) now + 1000
                    else
                        TimeRules.reminderAt(task.dueAt, task.reminderOffsetMinutes).also {
                            require(it > now) {
                                "The reminder time has passed. Choose Notify now or save without a reminder."
                            }
                        }
                } else null
            val previous = dao.get(task.id)
            scheduler.cancel(task.id)
            val saved =
                task.copy(title = task.title.trim(), scheduledReminderAt = at, updatedAt = now)
            try {
                dao.put(saved)
            } catch (e: Exception) {
                previous?.let(scheduler::schedule)
                throw e
            }
            scheduler.dismiss(task.id)
            scheduler.schedule(saved)
        }

    suspend fun complete(id: String, completed: Boolean) =
        mutex.withLock {
            val task = dao.get(id) ?: return@withLock
            scheduler.cancel(id)
            scheduler.dismiss(id)
            val candidate = TimeRules.reminderAt(task.dueAt, task.reminderOffsetMinutes)
            val changed =
                task.copy(
                    isCompleted = completed,
                    completedAt = if (completed) System.currentTimeMillis() else null,
                    scheduledReminderAt =
                        if (
                            !completed &&
                                task.reminderEnabled &&
                                candidate > System.currentTimeMillis()
                        )
                            candidate
                        else null,
                    updatedAt = System.currentTimeMillis(),
                )
            dao.put(changed)
            scheduler.schedule(changed)
        }

    suspend fun delete(id: String) =
        mutex.withLock {
            scheduler.cancel(id)
            scheduler.dismiss(id)
            dao.delete(id)
        }

    suspend fun clearCompleted() {
        dao.all().filter { it.isCompleted }.forEach { delete(it.id) }
    }

    suspend fun clear() {
        dao.all().forEach { delete(it.id) }
    }

    suspend fun restore() =
        mutex.withLock {
            dao.all().forEach {
                scheduler.cancel(it.id)
                scheduler.schedule(it)
            }
        }

    suspend fun deliver(id: String, expectedAt: Long, publish: suspend (Task) -> Unit) =
        mutex.withLock {
            val task = dao.get(id) ?: return@withLock
            if (task.isCompleted || !task.reminderEnabled || task.scheduledReminderAt != expectedAt)
                return@withLock
            if (expectedAt > System.currentTimeMillis() + 1000) return@withLock
            publish(task)
            dao.put(task.copy(scheduledReminderAt = null))
        }

    suspend fun snooze(id: String, minutes: Int = 5) =
        mutex.withLock {
            val task = dao.get(id) ?: return@withLock
            if (task.isCompleted) return@withLock
            val updated =
                task.copy(
                    reminderEnabled = true,
                    scheduledReminderAt = System.currentTimeMillis() + minutes * 60_000L,
                    updatedAt = System.currentTimeMillis(),
                )
            scheduler.cancel(id)
            scheduler.dismiss(id)
            dao.put(updated)
            scheduler.schedule(updated)
        }
}
