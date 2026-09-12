package com.oki.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oki.AppContainer
import com.oki.BuildConfig
import com.oki.core.ai.*
import com.oki.core.security.Provider
import com.oki.core.storage.*
import com.oki.core.ui.*
import com.oki.feature.tasks.TaskEditorViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(val c: AppContainer) : ActionViewModel() {
    val settings =
        c.settings.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            Settings(),
        )
    val configured = MutableStateFlow<Map<Provider, Boolean>>(emptyMap())
    val diagnostic = MutableStateFlow("")

    init {
        refreshKeys()
    }

    fun refreshKeys() {
        viewModelScope.launch {
            configured.value = Provider.entries.associateWith { c.credentials.isConfigured(it) }
        }
    }

    fun saveKey(provider: Provider, key: String, done: () -> Unit) = action {
        c.credentials.save(provider, key)
        c.aiUsage.clear(provider)
        refreshKeys()
        done()
    }

    fun deleteKey(provider: Provider) = action {
        c.credentials.delete(provider)
        c.aiUsage.clear(provider)
        refreshKeys()
    }

    fun test(provider: Provider) = action {
        diagnostic.value = "Testing ${provider.name.lowercase()}…"
        if (provider == Provider.GEMINI) {
            val results = mutableListOf<String>()
            for ((label, model) in
                listOf("Primary" to GEMINI_PRIMARY, "Fallback" to GEMINI_FALLBACK)) {
                try {
                    c.gemini.test(model)
                    results += "$label ($model): Passed"
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    results += "$label ($model): Failed — ${friendlyError(e)}"
                }
                diagnostic.value = results.joinToString("\n\n")
            }
        } else {
            val results = mutableListOf<String>()
            for ((label, model) in
                listOf(
                    "Primary" to GROQ_PRIMARY,
                    "Fallback" to GROQ_FALLBACK,
                    "Tertiary" to GROQ_TERTIARY,
                )) {
                try {
                    c.groq.test(model)
                    results += "$label ($model): Passed"
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    results += "$label ($model): Failed — ${friendlyError(e)}"
                }
                diagnostic.value = results.joinToString("\n\n")
            }
        }
    }

    fun sound(mode: SoundMode) = action {
        c.settings.setSound(mode)
        c.publisher.channel(c.settings.settings.first())
    }

    fun importSound(uri: Uri) = action {
        val saved = c.sounds.import(uri)
        c.settings.setSound(SoundMode.CUSTOM, saved)
        c.publisher.channel(c.settings.settings.first())
    }

    fun offset(value: String) = action {
        val parsed = value.toIntOrNull() ?: error("Enter a whole number of minutes.")
        c.settings.setOffset(parsed)
    }

    fun alarmSound(mode: AlarmSound, uri: String = "") = action {
        c.settings.setAlarmSound(mode, uri)
        // Recreate the channel now so the next alarm rings with the tone just chosen.
        c.publisher.alarmChannel(c.settings.settings.first())
    }

    fun previewAlarm() = action {
        c.publisher.alarmSoundUri(c.settings.settings.first())?.let { c.sounds.preview(it, true) }
    }

    fun bypassDnd(value: Boolean) = action {
        c.settings.setBypassDnd(value)
        // Recreate both channels so the new bypass identity exists before the next alert.
        val current = c.settings.settings.first()
        c.publisher.channel(current)
        c.publisher.alarmChannel(current)
    }

    fun autoDelete(value: AutoDeleteCompleted) = action {
        c.settings.setAutoDeleteCompleted(value)
        c.purgeExpiredCompletedTasks()
    }

    fun appearance(value: Appearance) = action { c.settings.setAppearance(value) }

    fun reasoning(value: ReasoningEffort) = action { c.settings.setReasoningEffort(value) }

    fun testNotification() = action {
        require(c.publisher.canNotify()) { "Enable notifications in Android settings first." }
        c.publisher.publish(
            Task(
                id = "settings-test",
                title = "A little nudge from Athii",
                notes = "Your local reminders are ready.",
                dueAt = System.currentTimeMillis(),
            ),
            c.settings.settings.first(),
        )
        diagnostic.value =
            "Test notification sent. Sound follows Android silent mode, DND, and channel settings."
    }
}

// BatteryLife: reminders are the app's purpose, so the exemption is requested directly rather
// than leaving the user to hunt for Athii in a system list.
@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(vm: SettingsViewModel, clearChat: () -> Unit, dataDeleted: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val diagnostic by vm.diagnostic.collectAsStateWithLifecycle()
    var exact by remember { mutableStateOf(vm.c.reminders.hasExactAccess()) }
    var notifications by remember { mutableStateOf(vm.c.publisher.canNotify()) }
    val notificationManager = remember {
        context.getSystemService(android.app.NotificationManager::class.java)
    }
    var fullScreenAlarms by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent())
    }
    var explainExact by remember { mutableStateOf(false) }
    var batteryExempt by remember { mutableStateOf(vm.c.publisher.ignoresBatteryOptimizations()) }
    var dndAccess by remember { mutableStateOf(vm.c.publisher.canBypassDnd()) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notifications = vm.c.publisher.canNotify()
        }
    val tonePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pickedRingtone(it.data)?.let { uri -> vm.alarmSound(AlarmSound.CUSTOM, uri.toString()) }
        }
    val audioPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                vm.importSound(it)
            }
        }
    LifecycleResumeEffect(Unit) {
        vm.refreshKeys()
        exact = vm.c.reminders.hasExactAccess()
        notifications = vm.c.publisher.canNotify()
        batteryExempt = vm.c.publisher.ignoresBatteryOptimizations()
        dndAccess = vm.c.publisher.canBypassDnd()
        fullScreenAlarms =
            Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()
        onPauseOrDispose { vm.c.sounds.stop() }
    }
    fun openBatterySettings() {
        // The direct request is what actually removes the restriction; the list is the fallback.
        val direct =
            Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                .takeIf { it.resolveActivity(context.packageManager) != null }
        context.startActivity(
            direct ?: Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
    }
    fun openNotificationSettings() {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 30.dp),
    ) {
        item {
            Text("Make Athii yours.", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Sound, appearance, and a little help from AI.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ErrorBanner(error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (diagnostic.isNotBlank())
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        diagnostic,
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
        item {
            SettingsCard("Notifications", Icons.Outlined.Notifications) {
                Text(
                    "Choose Notification or Alarm on each task. An optional end time sends a normal notification with the task title.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsAction(
                    "Notifications",
                    if (notifications) "Allowed" else "Blocked",
                    if (notifications) "Manage" else "Enable",
                ) {
                    if (Build.VERSION.SDK_INT >= 33 && !notifications)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else openNotificationSettings()
                }
                HorizontalDivider()
                SettingsAction(
                    "On-time delivery",
                    if (exact) "Ready" else "Android may delay delivery",
                    if (exact) "Details" else "Enable",
                ) {
                    explainExact = true
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    HorizontalDivider()
                    SettingsAction(
                        "Alarm on lock screen",
                        if (fullScreenAlarms) "Allowed" else "Tap the alarm notification to open",
                        "Manage",
                    ) {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Alert during Do Not Disturb",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (!settings.bypassDnd)
                                "Off · Athii stays silent while Do Not Disturb is on"
                            else if (dndAccess) "On · task alerts will sound through Do Not Disturb"
                            else "Needs Do Not Disturb access before it can take effect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.bypassDnd,
                        onCheckedChange = { vm.bypassDnd(it) },
                        enabled = !busy,
                    )
                }
                if (settings.bypassDnd && !dndAccess)
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant Do Not Disturb access")
                    }
                HorizontalDivider()
                SettingsAction(
                    "Keep working when Athii is closed",
                    if (batteryExempt) "Unrestricted"
                    else "Battery saver may stop alarms once you close Athii",
                    if (batteryExempt) "Manage" else "Fix",
                ) {
                    openBatterySettings()
                }
                Text(
                    "Alarms and reminders are scheduled by Android, so they fire with Athii closed — " +
                        "but only while the system is allowed to wake it. If alerts still go missing, " +
                        "also turn on Autostart and set battery usage to Unrestricted for Athii in your " +
                        "phone's own battery settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = vm::testNotification,
                    enabled = !busy && notifications,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Send test notification")
                }
            }
        }
        item {
            SettingsCard("Default reminder lead time", Icons.Outlined.Timer) {
                Text(
                    "Used when you create a task. Every task can still choose its own lead time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskEditorViewModel.OFFSET_PRESETS.forEach { option ->
                        FilterChip(
                            selected = settings.defaultOffset == option,
                            onClick = { vm.offset(option.toString()) },
                            label = { Text(if (option == 0) "At time" else "$option min") },
                            enabled = !busy,
                        )
                    }
                }
            }
        }
        item {
            SettingsCard("Alarm sound", Icons.Outlined.Alarm) {
                Text(
                    "Plays for tasks set to Alarm, at your phone's alarm volume.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlarmSound.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.alarmSound == mode,
                            onClick = {
                                if (mode == AlarmSound.CUSTOM)
                                    tonePicker.launch(ringtonePickerIntent(settings.alarmSoundUri))
                                else vm.alarmSound(mode)
                            },
                            label = {
                                Text(
                                    when (mode) {
                                        AlarmSound.RINGTONE -> "Phone ringtone"
                                        AlarmSound.ALARM -> "Alarm tone"
                                        AlarmSound.CUSTOM -> "Pick a tone"
                                    }
                                )
                            },
                            enabled = !busy,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = vm::previewAlarm,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text("Preview")
                    }
                    TextButton(onClick = vm.c.sounds::stop, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                    if (settings.alarmSound == AlarmSound.CUSTOM)
                        TextButton(
                            onClick = { vm.alarmSound(AlarmSound.RINGTONE) },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) {
                            Text("Reset")
                        }
                }
            }
        }
        item {
            SettingsCard("Completed tasks", Icons.Outlined.AutoDelete) {
                Text(
                    "Remove completed tasks automatically once they reach this age.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoDeleteCompleted.entries.forEach { option ->
                        FilterChip(
                            selected = settings.autoDeleteCompleted == option,
                            onClick = { vm.autoDelete(option) },
                            label = {
                                Text(
                                    when (option) {
                                        AutoDeleteCompleted.NEVER -> "Never"
                                        AutoDeleteCompleted.ONE_DAY -> "After 1 day"
                                        AutoDeleteCompleted.ONE_WEEK -> "After 7 days"
                                        AutoDeleteCompleted.ONE_MONTH -> "After 1 month"
                                    }
                                )
                            },
                            enabled = !busy,
                        )
                    }
                }
                Text(
                    if (settings.autoDeleteCompleted == AutoDeleteCompleted.NEVER)
                        "Completed tasks are kept until you clear them yourself."
                    else
                        "Removal happens when Athii opens and again at midnight. Deleted tasks cannot be recovered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("Notification sound", Icons.Outlined.MusicNote) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SoundMode.SYSTEM, SoundMode.SILENT).forEach { mode ->
                        FilterChip(
                            settings.soundMode == mode,
                            { vm.sound(mode) },
                            label = {
                                Text(if (mode == SoundMode.SYSTEM) "System sound" else "Silent")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { audioPicker.launch(arrayOf("audio/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (settings.soundMode == SoundMode.CUSTOM) "Change custom sound"
                        else "Choose custom sound"
                    )
                }
                if (settings.soundMode == SoundMode.CUSTOM) {
                    Text("Custom sound selected", style = MaterialTheme.typography.bodySmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { vm.c.sounds.preview(Uri.parse(settings.customSoundUri)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Preview")
                        }
                        TextButton(onClick = vm.c.sounds::stop, modifier = Modifier.weight(1f)) {
                            Text("Stop")
                        }
                        TextButton(
                            onClick = { vm.sound(SoundMode.SYSTEM) },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) {
                            Text("Reset")
                        }
                    }
                }
                Text(
                    "Custom clips can be up to 5 seconds. Sound follows your phone’s silent mode and Do Not Disturb settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("Appearance", Icons.Outlined.Palette) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Appearance.entries.forEach { appearance ->
                        FilterChip(
                            settings.appearance == appearance,
                            { vm.appearance(appearance) },
                            label = {
                                Text(appearance.name.lowercase().replaceFirstChar(Char::uppercase))
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                    }
                }
            }
        }
        item {
            SettingsCard("AI reasoning", Icons.Outlined.AutoAwesome) {
                Text(
                    "High takes more time to work through a request. Low responds faster.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReasoningEffort.entries.forEach { effort ->
                        FilterChip(
                            selected = settings.reasoningEffort == effort,
                            onClick = { vm.reasoning(effort) },
                            label = {
                                Text(effort.name.lowercase().replaceFirstChar(Char::uppercase))
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                    }
                }
            }
        }
        item {
            SettingsCard("AI connections", Icons.Outlined.CloudDone) {
                Text(
                    "Chat and scans try Groq first, then Gemini. Tap the usage icon beside Send to see models, measured tokens and reported limits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(Provider.entries.size) { index ->
            val provider = Provider.entries[index]
            CredentialEditor(
                provider,
                configured[provider] == true,
                busy,
                { key, done -> vm.saveKey(provider, key, done) },
                { confirmation = provider.name },
                { vm.test(provider) },
            )
        }
        item {
            SettingsCard("Local data", Icons.Outlined.Storage) {
                Text(
                    "Your tasks, doctors, and chat history stay on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { confirmation = "completed" },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text("Clear completed tasks")
                }
                TextButton(
                    onClick = { confirmation = "chat" },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text("Clear chat history")
                }
                HorizontalDivider()
                TextButton(
                    onClick = { confirmation = "all" },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text("Delete all app data", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Text(
                "Athii · Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "No accounts, ads, or sync. Only your AI questions, matching records, and selected images are sent when you use AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (explainExact)
        AlertDialog(
            onDismissRequest = { explainExact = false },
            title = { Text("Right on time") },
            text = {
                Text(
                    "Android calls this permission Alarms & Reminders. It lets Athii send normal notifications at your selected start and end times. It does not play alarm-clock audio. Without access, delivery may be delayed."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        explainExact = false
                        if (Build.VERSION.SDK_INT >= 31)
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                    }
                ) {
                    Text("Open Android settings")
                }
            },
            dismissButton = { TextButton(onClick = { explainExact = false }) { Text("Later") } },
        )
    confirmation?.let { action ->
        ConfirmDelete(
            if (action == "all") "Delete all app data?" else "Delete ${action.lowercase()}?",
            if (action == "all")
                "All tasks, doctors, credentials, preferences, custom sounds, and all chat history will be permanently removed from this device."
            else "This cannot be undone.",
            { confirmation = null },
            {
                when (action) {
                    "all" ->
                        vm.action {
                            vm.c.deleteAll()
                            clearChat()
                            vm.refreshKeys()
                            dataDeleted()
                        }
                    "completed" -> vm.action { vm.c.tasks.clearCompleted() }
                    "chat" -> clearChat()
                    else -> vm.deleteKey(Provider.valueOf(action))
                }
            },
        )
    }
}

@Composable
fun CredentialEditor(
    provider: Provider,
    configured: Boolean,
    busy: Boolean,
    save: (String, () -> Unit) -> Unit,
    delete: () -> Unit,
    test: () -> Unit,
) {
    // Intentionally not rememberSaveable: credentials must never enter Android saved-instance
    // state.
    var key by remember { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (provider == Provider.GEMINI) "Gemini · chat and image fallback"
                else "Groq · chat and scans",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (configured) "••••••••  · Key saved securely" else "No API key saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (editing || !configured) {
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text(if (configured) "Replacement API key" else "API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            save(key) {
                                key = ""
                                editing = false
                            }
                        },
                        enabled = key.isNotBlank() && !busy,
                    ) {
                        Text(if (configured) "Replace" else "Save")
                    }
                    if (configured) TextButton(onClick = delete, enabled = !busy) { Text("Delete") }
                }
            }
            if (configured)
                TextButton(
                    onClick = {
                        editing = !editing
                        key = ""
                    },
                    enabled = !busy,
                ) {
                    Text(if (editing) "Cancel" else "Manage connection")
                }
            OutlinedButton(
                onClick = test,
                enabled = configured && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (provider == Provider.GEMINI) "Test Gemini"
                    else "Test Groq · Primary + Fallback"
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun SettingsAction(title: String, status: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClick) { Text(action) }
    }
}

private fun ringtonePickerIntent(current: String): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose an alarm tone")
        .putExtra(
            RingtoneManager.EXTRA_RINGTONE_TYPE,
            RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_ALARM,
        )
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        .putExtra(
            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            current.takeIf(String::isNotBlank)?.let(Uri::parse),
        )

@Suppress("DEPRECATION")
private fun pickedRingtone(data: Intent?): Uri? =
    if (data == null) null
    else if (Build.VERSION.SDK_INT >= 33)
        data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    else data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
