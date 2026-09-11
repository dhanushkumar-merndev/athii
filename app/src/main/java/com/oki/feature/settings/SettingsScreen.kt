package com.oki.feature.settings

import android.Manifest
import android.content.Intent
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
        refreshKeys()
        done()
    }

    fun deleteKey(provider: Provider) = action {
        c.credentials.delete(provider)
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
            for ((label, model) in listOf("Primary" to GROQ_PRIMARY, "Fallback" to GROQ_FALLBACK)) {
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

@Composable
fun SettingsScreen(vm: SettingsViewModel, clearChat: () -> Unit, dataDeleted: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val diagnostic by vm.diagnostic.collectAsStateWithLifecycle()
    var offset by
        rememberSaveable(settings.defaultOffset) {
            mutableStateOf(settings.defaultOffset.toString())
        }
    var exact by remember { mutableStateOf(vm.c.reminders.hasExactAccess()) }
    var notifications by remember { mutableStateOf(vm.c.publisher.canNotify()) }
    var explainExact by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notifications = vm.c.publisher.canNotify()
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
        onPauseOrDispose { vm.c.sounds.stop() }
    }
    fun openNotificationSettings() {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 30.dp),
    ) {
        item { PageHeading("Settings", "A few preferences for your day.") }
        item {
            ErrorBanner(error)
            if (busy) CenterLoader()
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
            SectionLabel("REMINDERS")
            Field("Default minutes before", offset, { offset = it })
            TextButton(onClick = { vm.offset(offset) }, enabled = !busy) {
                Text("Save default reminder")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SoundMode.SYSTEM, SoundMode.SILENT).forEach { mode ->
                    FilterChip(
                        settings.soundMode == mode,
                        { vm.sound(mode) },
                        label = { Text(if (mode == SoundMode.SYSTEM) "System sound" else "Silent") },
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, enabled = !busy) {
                Icon(Icons.Outlined.MusicNote, null)
                Spacer(Modifier.width(8.dp))
                Text("Choose custom sound · ≤5 sec")
            }
        }
        if (settings.soundMode == SoundMode.CUSTOM)
            item {
                Text("Custom sound selected", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(
                        onClick = { vm.c.sounds.preview(Uri.parse(settings.customSoundUri)) }
                    ) {
                        Text("Preview")
                    }
                    TextButton(onClick = vm.c.sounds::stop) { Text("Stop") }
                    TextButton(onClick = { vm.sound(SoundMode.SYSTEM) }) { Text("Remove / reset") }
                }
            }
        item {
            OutlinedButton(onClick = vm::testNotification, enabled = !busy) {
                Text("Test notification")
            }
        }
        item {
            Text("Notifications: ${if (notifications) "Allowed" else "Blocked"}")
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !notifications)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else openNotificationSettings()
                }
            ) {
                Text("Notification permission")
            }
            TextButton(onClick = ::openNotificationSettings) {
                Text("Open notification & channel settings")
            }
        }
        item {
            Text("Exact alarms: ${if (exact) "Allowed" else "Not allowed — timing may be delayed"}")
            TextButton(onClick = { explainExact = true }) { Text("Alarms & reminders access") }
        }
        item {
            SectionLabel("AI CONFIGURATION")
            Text(
                "Your keys are encrypted on this device. Saved keys are never shown again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            SectionLabel("APPEARANCE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { appearance ->
                    FilterChip(
                        settings.appearance == appearance,
                        { vm.appearance(appearance) },
                        label = {
                            Text(appearance.name.lowercase().replaceFirstChar(Char::uppercase))
                        },
                    )
                }
            }
        }
        item {
            SectionLabel("AI REASONING")
            Text(
                "High is more thorough. Low responds faster for simple questions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasoningEffort.entries.forEach { effort ->
                    FilterChip(
                        selected = settings.reasoningEffort == effort,
                        onClick = { vm.reasoning(effort) },
                        label = { Text(effort.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor =
                                    MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                    )
                }
            }
        }
        item {
            SectionLabel("LOCAL DATA")
            TextButton(onClick = { confirmation = "completed" }) { Text("Clear completed tasks") }
            TextButton(onClick = { confirmation = "chat" }) { Text("Clear chat") }
            TextButton(onClick = { confirmation = "all" }) {
                Text("Delete all app data", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            SectionLabel("ABOUT Athii")
            Text("Version ${BuildConfig.VERSION_NAME}")
            Text(
                "Local by default. No accounts, sync, ads, or analytics. Only AI questions, matching records, and selected images leave the device when you use AI. Android controls notification sound and delivery.",
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
                    "Allow Alarms & Reminders so Athii can deliver reminders at the selected time and reset attendance at local midnight. Without access, Android may delay alarms; Athii corrects attendance when reopened."
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
                "All tasks, doctors, credentials, preferences, custom sounds, and this chat will be permanently removed from this device."
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (provider == Provider.GEMINI) "Gemini · image extraction" else "Groq · Ask AI",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (configured) "••••••••  · Key saved securely" else "No API key saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    onClick = { save(key) { key = "" } },
                    enabled = key.isNotBlank() && !busy,
                ) {
                    Text(if (configured) "Replace" else "Save")
                }
                if (configured) TextButton(onClick = delete, enabled = !busy) { Text("Delete") }
            }
            OutlinedButton(onClick = test, enabled = configured && !busy) {
                Text(
                    if (provider == Provider.GEMINI) "Test Gemini"
                    else "Test Groq · Primary + Fallback"
                )
            }
        }
    }
}
