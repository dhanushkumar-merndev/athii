package com.oki.feature.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.oki.AppContainer
import com.oki.core.ai.*
import com.oki.core.storage.*
import com.oki.core.ui.ActionViewModel
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class TasksViewModel(private val c: AppContainer) : ActionViewModel() {
    val tasks = c.tasks.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val celebrationMilestone = MutableStateFlow<Int?>(null)
    private val celebratedMilestones = mutableSetOf<Int>()
    private var lastCelebrationDate: LocalDate = LocalDate.now()

    fun dismissCelebration() {
        celebrationMilestone.value = null
    }

    fun complete(task: Task) = action {
        val willBeCompleted = !task.isCompleted
        c.tasks.complete(task.id, willBeCompleted)
        if (willBeCompleted) {
            checkDailyMilestones()
        }
    }

    private suspend fun checkDailyMilestones() {
        val today = LocalDate.now()
        if (today != lastCelebrationDate) {
            celebratedMilestones.clear()
            lastCelebrationDate = today
        }
        val currentTasks = c.tasks.tasks.first()
        val completedTodayCount =
            currentTasks.count {
                it.isCompleted &&
                    it.completedAt != null &&
                    Instant.ofEpochMilli(it.completedAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate() == today
            }
        val milestone =
            when {
                completedTodayCount >= 50 && 50 !in celebratedMilestones -> 50
                completedTodayCount >= 20 && 20 !in celebratedMilestones -> 20
                completedTodayCount >= 5 && 5 !in celebratedMilestones -> 5
                else -> null
            }
        if (milestone != null) {
            celebratedMilestones.add(milestone)
            celebrationMilestone.value = milestone
        }
    }

    fun delete(id: String) = action { c.tasks.delete(id) }
}

data class TaskAutocomplete(val titles: List<String>, val notes: List<String>)

@Serializable
data class TaskForm(
    val title: String = "",
    val notes: String = "",
    val date: String = "",
    val time: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val reminder: Boolean = true,
    val offset: String = "5",
    val source: Source = Source.MANUAL,
    val review: Boolean = false,
)

class TaskEditorViewModel(
    private val c: AppContainer,
    private val state: SavedStateHandle,
    private val id: String?,
    draft: String?,
) : ActionViewModel() {
    val form =
        state
            .getStateFlow("form", "")
            .map { if (it.isBlank()) null else aiJson.decodeFromString<TaskForm>(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val saved = MutableStateFlow(false)
    val autocomplete =
        c.tasks.tasks
            .map { tasks ->
                TaskAutocomplete(
                    titles = tasks.map { it.title }.filter { it.isNotBlank() }.distinct().sorted(),
                    notes = tasks.map { it.notes }.filter { it.isNotBlank() }.distinct().sorted(),
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                TaskAutocomplete(emptyList(), emptyList()),
            )
    private var original: Task? = null

    init {
        if (id == null && draft == null && state.get<String>("form").isNullOrBlank()) {
            val defaultTime = ZonedDateTime.now().plusHours(1).withSecond(0).withNano(0)
            val initial =
                TaskForm(
                    date = defaultTime.toLocalDate().toString(),
                    time = defaultTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    offset = "5",
                )
            change(initial)
        }
        action {
            original = id?.let { c.tasks.get(it) ?: error("This task no longer exists.") }
            if (state.get<String>("form").isNullOrBlank()) {
                val initial =
                    original?.let {
                        val local = Instant.ofEpochMilli(it.dueAt).atZone(ZoneId.systemDefault())
                        TaskForm(
                            title = it.title,
                            notes = it.notes,
                            date = local.toLocalDate().toString(),
                            time = local.format(DateTimeFormatter.ofPattern("HH:mm")),
                            startTime = it.startTime.orEmpty(),
                            endTime = it.endTime.orEmpty(),
                            reminder = it.reminderEnabled,
                            offset = it.reminderOffsetMinutes.toString(),
                            source = it.source,
                        )
                    }
                        ?: draft?.let {
                            val d = aiJson.decodeFromString<TaskDraft>(it)
                            TaskForm(
                                title = d.title.orEmpty(),
                                notes = d.notes.orEmpty(),
                                date = d.date.orEmpty(),
                                time = d.time.orEmpty(),
                                startTime = d.startTime.orEmpty(),
                                endTime = d.endTime.orEmpty(),
                                reminder = true,
                                offset =
                                    (d.reminderOffsetMinutes
                                            ?: c.settings.settings.first().defaultOffset)
                                        .toString(),
                                source =
                                    Source.valueOf(
                                        state.get<String>("draftSource") ?: "IMAGE_SCAN"
                                    ),
                                review = true,
                            )
                        }
                        ?: ZonedDateTime.now().plusHours(1).withSecond(0).withNano(0).let {
                            TaskForm(
                                date = it.toLocalDate().toString(),
                                time = it.format(DateTimeFormatter.ofPattern("HH:mm")),
                                offset = c.settings.settings.first().defaultOffset.toString(),
                            )
                        }
                change(initial)
            }
        }
    }

    fun change(form: TaskForm) {
        state["form"] = aiJson.encodeToString(form)
    }

    fun save(notifyNow: Boolean = false, withoutReminder: Boolean = false) = action {
        val f = form.value ?: return@action
        require(f.title.isNotBlank()) { "Enter a task title." }
        val due =
            try {
                TimeRules.parseDue(f.date, f.time)
            } catch (_: Exception) {
                error("Choose a valid date and time (YYYY-MM-DD and HH:mm).")
            }
        val offset =
            f.offset.toIntOrNull()?.takeIf { it in 0..525600 }
                ?: error("Enter a reminder offset from 0 to 525600 minutes.")
        val task =
            (original ?: Task(title = f.title, dueAt = due, source = f.source)).copy(
                title = f.title,
                notes = f.notes,
                dueAt = due,
                startTime = f.startTime.ifBlank { null },
                endTime = f.endTime.ifBlank { null },
                reminderEnabled = f.reminder && !withoutReminder,
                reminderOffsetMinutes = offset,
            )
        c.tasks.save(task, notifyNow)
        saved.value = true
    }

    fun reminderInPast(): Boolean =
        runCatching {
                val f = form.value!!
                f.reminder &&
                    TimeRules.reminderAt(TimeRules.parseDue(f.date, f.time), f.offset.toInt()) <=
                        System.currentTimeMillis()
            }
            .getOrDefault(false)
}
