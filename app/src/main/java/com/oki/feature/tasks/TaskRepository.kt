package com.oki.feature.tasks

import com.oki.core.storage.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TaskNotificationKind {
    START,
    END,
}

interface ReminderScheduler {
    fun validate(task: Task) {}

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
            scheduler.validate(task)
            val now = System.currentTimeMillis()
            val end = TimeRules.endAt(task.dueAt, task.endTime)
            val at =
                if (task.reminderEnabled && !task.isCompleted) {
                    if (notifyNow) now + 1000
                    else
                        TimeRules.reminderAt(
                                task.dueAt,
                                if (task.alertMode == TaskAlertMode.ALARM) 0
                                else task.reminderOffsetMinutes,
                            )
                            .also {
                                require(it > now) {
                                    "The start time has passed. Choose Notify now or save without notifications."
                                }
                            }
                } else null
            val previous = dao.get(task.id)
            scheduler.cancel(task.id)
            val saved =
                task.copy(
                    title = task.title.trim(),
                    reminderOffsetMinutes =
                        if (task.alertMode == TaskAlertMode.ALARM) 0
                        else task.reminderOffsetMinutes,
                    scheduledReminderAt = at,
                    scheduledEndReminderAt =
                        end?.takeIf { task.reminderEnabled && !task.isCompleted && it > now },
                    updatedAt = now,
                )
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
            if (!completed) scheduler.validate(task.copy(isCompleted = false))
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
                    scheduledEndReminderAt =
                        if (!completed && task.reminderEnabled)
                            runCatching { TimeRules.endAt(task.dueAt, task.endTime) }
                                .getOrNull()
                                ?.takeIf { it > System.currentTimeMillis() }
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

    suspend fun deliver(
        id: String,
        expectedAt: Long,
        kind: TaskNotificationKind = TaskNotificationKind.START,
        publish: suspend (Task) -> Unit,
    ) =
        mutex.withLock {
            val task = dao.get(id) ?: return@withLock
            val scheduledAt =
                when (kind) {
                    TaskNotificationKind.START -> task.scheduledReminderAt
                    TaskNotificationKind.END -> task.scheduledEndReminderAt
                }
            if (task.isCompleted || !task.reminderEnabled || scheduledAt != expectedAt)
                return@withLock
            if (expectedAt > System.currentTimeMillis() + 1000) return@withLock
            publish(task)
            dao.put(
                when (kind) {
                    TaskNotificationKind.START -> task.copy(scheduledReminderAt = null)
                    TaskNotificationKind.END -> task.copy(scheduledEndReminderAt = null)
                }
            )
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
            scheduler.validate(updated)
            scheduler.cancel(id)
            scheduler.dismiss(id)
            dao.put(updated)
            scheduler.schedule(updated)
        }
}
