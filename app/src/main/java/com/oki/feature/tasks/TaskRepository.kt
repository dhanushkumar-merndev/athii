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

    companion object {
        /**
         * Measured on device: 100 and 500 active tasks both scroll acceptably, 1000 does not. 300
         * sits inside the range that was actually tested rather than extrapolated.
         */
        const val MAX_ACTIVE_TASKS = 300

        /**
         * Android caps an app at 500 concurrent alarms and a task books up to two (start + end), so
         * 300 tasks can want 600 alarms, over the ceiling. Only the soonest reminders are armed;
         * the rest are armed later by [restore], which runs on launch, on boot, and at midnight.
         * 180 tasks is at most 360 alarms, a wide margin under the ceiling.
         */
        const val ARMED_REMINDER_LIMIT = 180
    }

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
            if (!task.isCompleted && dao.activeCountExcluding(task.id) >= MAX_ACTIVE_TASKS)
                throw IllegalArgumentException(
                    "You have reached the limit of $MAX_ACTIVE_TASKS active tasks. Complete or delete a few tasks, then add this one."
                )
            scheduler.validate(task)
            val now = System.currentTimeMillis()
            val end = TimeRules.endAt(task.dueAt, task.endTime)
            val at =
                if (task.reminderEnabled && !task.isCompleted) {
                    if (notifyNow) now + 1000
                    else
                        TimeRules.reminderAt(task.dueAt, task.reminderOffsetMinutes).also {
                            require(it > now) {
                                "That reminder time has already passed. Choose Notify now or save without notifications."
                            }
                        }
                } else null
            val previous = dao.get(task.id)
            scheduler.cancel(task.id)
            val saved =
                task.copy(
                    title = task.title.trim(),
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

    /**
     * Completed tasks have already had their alarms cancelled and notifications dismissed in
     * [complete], so removal is a single set-based delete rather than one round trip per row.
     */
    suspend fun clearCompleted() = mutex.withLock { dao.deleteCompleted() }

    /**
     * Removes completed tasks older than [retentionDays]. Returns the number removed. A null
     * retention keeps everything.
     */
    suspend fun purgeCompleted(retentionDays: Int?, now: Long = System.currentTimeMillis()): Int {
        val days = retentionDays ?: return 0
        require(days > 0) { "Retention must be at least one day." }
        return mutex.withLock { dao.deleteCompletedBefore(now - days * 86_400_000L) }
    }

    suspend fun clear() {
        dao.all().forEach { delete(it.id) }
    }

    /**
     * Re-arms reminders after launch, boot, or a clock change. Only the soonest
     * [ARMED_REMINDER_LIMIT] tasks are armed, because the platform alarm budget is far smaller than
     * the task cap; later ones are picked up the next time this runs.
     */
    suspend fun restore() =
        mutex.withLock {
            val now = System.currentTimeMillis()
            val all = dao.all()
            all.forEach { scheduler.cancel(it.id) }
            all.asSequence()
                .filter { it.reminderEnabled && !it.isCompleted }
                .mapNotNull { task ->
                    listOfNotNull(task.scheduledReminderAt, task.scheduledEndReminderAt)
                        .filter { it > now }
                        .minOrNull()
                        ?.let { it to task }
                }
                .sortedBy { it.first }
                .take(ARMED_REMINDER_LIMIT)
                .forEach { scheduler.schedule(it.second) }
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
