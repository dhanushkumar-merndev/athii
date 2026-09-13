package com.oki.feature.tutorial

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A single step in the guided app tour.
 *
 * @param id Unique stable identifier, e.g. "welcome", "add_doctor".
 * @param title Short heading displayed in the tooltip.
 * @param description 1–2 line explanation.
 * @param screenRoute Logical route the target lives on. Used to navigate before showing the step.
 *   Values: "home/tasks", "home/doctors", "settings", "doctor_detail".
 * @param targetKey The key registered via [Modifier.tutorialTarget]. Empty for full-screen
 *   overlays.
 * @param icon Optional leading icon for the tooltip.
 * @param isWelcome True for the opening welcome overlay (no spotlight).
 * @param isFinal True for the closing overlay (no spotlight).
 * @param needsDoctor True when the target only exists once at least one doctor is saved.
 */
data class TutorialStep(
    val id: String,
    val title: String,
    val description: String,
    val screenRoute: String,
    val targetKey: String,
    val icon: ImageVector? = null,
    val isWelcome: Boolean = false,
    val isFinal: Boolean = false,
    /** Points at a doctor record, so it is skipped outright when the directory is empty. */
    val needsDoctor: Boolean = false,
    /** Points at saved tasks, so it is skipped when the task list is empty. */
    val needsTask: Boolean = false,
)

/** All guided-tour steps in order. */
val ALL_TUTORIAL_STEPS: List<TutorialStep> =
    listOf(
        TutorialStep(
            id = "welcome",
            title = "Welcome to Athii",
            description =
                "Let's quickly show you how to manage doctors, availability and reminders.",
            screenRoute = "home/tasks",
            targetKey = "",
            icon = Icons.Outlined.WavingHand,
            isWelcome = true,
        ),
        TutorialStep(
            id = "dashboard",
            title = "Your Dashboard",
            description = "See today's upcoming tasks and important information from one place.",
            screenRoute = "home/tasks",
            targetKey = "task_dashboard",
            icon = Icons.Outlined.Dashboard,
            needsTask = true,
        ),
        TutorialStep(
            id = "task_list",
            title = "Your Tasks",
            description =
                "Each task shows its start time and reminder. Tap a task to view or update it.",
            screenRoute = "home/tasks",
            targetKey = "task_card",
            icon = Icons.Outlined.TaskAlt,
            needsTask = true,
        ),
        TutorialStep(
            id = "task_complete",
            title = "Mark as Done",
            description =
                "Tap the circle when a task is finished. Completed tasks are read-only; reopen one to change it.",
            screenRoute = "home/tasks",
            targetKey = "task_checkbox",
            icon = Icons.Outlined.CheckCircleOutline,
            needsTask = true,
        ),
        TutorialStep(
            id = "task_filters",
            title = "Upcoming, Overdue, Completed",
            description = "Switch lists to see what is next, what was missed and what is done.",
            screenRoute = "home/tasks",
            targetKey = "task_filters",
            icon = Icons.Outlined.FilterList,
            needsTask = true,
        ),
        TutorialStep(
            id = "task_search",
            title = "Find Tasks Faster",
            description = "Search tasks by title or notes. Suggestions complete as you type.",
            screenRoute = "home/tasks",
            targetKey = "task_search",
            icon = Icons.Outlined.Search,
            needsTask = true,
        ),
        TutorialStep(
            id = "task_delete",
            title = "Delete Task",
            description =
                "Remove a task and its reminder. Confirmation is always shown before deletion.",
            screenRoute = "home/tasks",
            targetKey = "task_delete",
            icon = Icons.Outlined.DeleteOutline,
            needsTask = true,
        ),
        TutorialStep(
            id = "add_doctor",
            title = "Add a Doctor",
            description =
                "Add a doctor along with their qualification, department, OPD room, schedule and notes.",
            screenRoute = "home/doctors",
            targetKey = "add_fab",
            icon = Icons.Outlined.PersonAdd,
        ),
        TutorialStep(
            id = "doctor_list",
            title = "All Doctors",
            description = "View all doctors and quickly check their current availability.",
            screenRoute = "home/doctors",
            targetKey = "doctor_list_area",
            icon = Icons.Outlined.MedicalServices,
            needsDoctor = true,
        ),
        TutorialStep(
            id = "doctor_card",
            title = "Doctor Details",
            description = "Tap a doctor to view or update their complete information.",
            screenRoute = "home/doctors",
            targetKey = "doctor_card",
            icon = Icons.Outlined.Badge,
            needsDoctor = true,
        ),
        TutorialStep(
            id = "attendance",
            title = "Update Availability",
            description =
                "Mark a doctor as Present or Absent. Doctors reset to Present automatically at midnight.",
            screenRoute = "home/doctors",
            targetKey = "attendance_chip",
            icon = Icons.Outlined.EventAvailable,
            needsDoctor = true,
        ),
        TutorialStep(
            id = "edit_doctor",
            title = "Edit Doctor",
            description =
                "Update the doctor's schedule, room, qualification, contact details or other information whenever needed.",
            screenRoute = "doctor_detail",
            targetKey = "edit_doctor",
            icon = Icons.Outlined.Edit,
            needsDoctor = true,
        ),
        TutorialStep(
            id = "delete_doctor",
            title = "Delete Doctor",
            description =
                "Remove a doctor when the record is no longer required. Confirmation is always shown before deletion.",
            screenRoute = "doctor_detail",
            targetKey = "delete_doctor",
            icon = Icons.Outlined.DeleteOutline,
            needsDoctor = true,
        ),
        TutorialStep(
            id = "search",
            title = "Find Doctors Faster",
            description = "Search doctors by name, department or other supported information.",
            screenRoute = "home/doctors",
            targetKey = "doctor_search",
            icon = Icons.Outlined.Search,
            needsDoctor = true,
        ),
        TutorialStep(
            id = "reminder",
            title = "Create a Reminder",
            description = "Create a task and choose when you want the app to remind you.",
            screenRoute = "home/tasks",
            targetKey = "add_fab",
            icon = Icons.Outlined.AddAlert,
        ),
        TutorialStep(
            id = "notifications",
            title = "Smart Notifications",
            description =
                "The app respects your device sound mode. Silent mode will not suddenly play notification sounds.",
            screenRoute = "settings",
            targetKey = "notification_settings",
            icon = Icons.Outlined.NotificationsActive,
        ),
        TutorialStep(
            id = "export_report",
            title = "Download Reports",
            description =
                "Pick a date range to download an Excel report with charts: task progress on Tasks, attendance on Doctors.",
            screenRoute = "home/tasks",
            targetKey = "export_report",
            icon = Icons.Outlined.FileDownload,
        ),
        TutorialStep(
            id = "settings",
            title = "Settings",
            description =
                "Customize reminder preferences, notification behavior and app options here.",
            screenRoute = "home/tasks",
            targetKey = "settings_icon",
            icon = Icons.Outlined.Settings,
        ),
        TutorialStep(
            id = "replay",
            title = "Need Help Again?",
            description = "You can replay this guided tour anytime from Settings.",
            screenRoute = "settings",
            targetKey = "replay_tutorial",
            icon = Icons.Outlined.Replay,
        ),
        TutorialStep(
            id = "finish",
            title = "You're Ready!",
            description = "That's it. You can now start managing your doctors and reminders.",
            screenRoute = "settings",
            targetKey = "",
            icon = Icons.Outlined.Celebration,
            isFinal = true,
        ),
    )
